package com.frankenergie.smartasset.service

import com.frankenergie.smartasset.allocation.ChargingAllocationStrategy
import com.frankenergie.smartasset.client.MarketOrderClient
import com.frankenergie.smartasset.client.SteeringSignalDispatcher
import com.frankenergie.smartasset.event.OrderBookUpdatedEvent
import com.frankenergie.smartasset.model.OrderBookEntry
import com.frankenergie.smartasset.model.QuarterBestLevel
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Core optimizer – listens for order-book changes and manages the energy position.
 *
 * Two-phase state machine (strictly separated, never mixed):
 *
 *  ┌─ FILL-UP ──────────────────────────────────────────────────────────────────┐
 *  │ Active when: totalPosition < totalEVRemainingNeed                          │
 *  │ Action:      greedy buy-only; iterates ALL individual ask orders across    │
 *  │              all quarters sorted by price ascending.                       │
 *  │              Per-quarter cap: maxBuyable(q) - currentPosition(q).          │
 *  │              Per-order cap: min(shortfall, headroom, askEntry.quantity).   │
 *  │              Position is updated BEFORE placeBuy so that any re-entrant    │
 *  │              event triggered by our own order sees the correct state and   │
 *  │              terminates when shortfall reaches zero.                       │
 *  │ Exit:        totalPosition ≥ totalEVRemainingNeed                          │
 *  └────────────────────────────────────────────────────────────────────────────┘
 *  ┌─ ARBITRAGE ────────────────────────────────────────────────────────────────┐
 *  │ Active when: totalPosition ≥ totalEVRemainingNeed                          │
 *  │ Anchor:      only the quarter that triggered this event (O(n) not O(n²))   │
 *  │ Early-exit:  if neither ask improved nor bid improved → skip               │
 *  │ If ask dropped:  BUY here (capped by bestAskQty),                          │
 *  │                  SELL at candidates (capped by min(pos, bestBidQty))       │
 *  │ If bid rose:     SELL here (capped by min(pos, bestBidQty)),               │
 *  │                  BUY at candidates (capped by min(headroom, bestAskQty))   │
 *  │ Natural guard:   after the optimizer consumes the ask/bid, the next        │
 *  │                  re-triggered event finds no price improvement → exits.    │
 *  │ Invariant:   every trade is paired → totalPosition stays constant          │
 *  └────────────────────────────────────────────────────────────────────────────┘
 *
 * After each phase, steering signals are dispatched to EV groups (incremental).
 */
@Service
class OptimizationService(
    private val orderBookService: OrderBookService,
    private val positionManager: PositionManager,
    private val chargingNeedAggregator: ChargingNeedAggregator,
    private val marketOrderClient: MarketOrderClient,
    private val steeringSignalDispatcher: SteeringSignalDispatcher,
    private val allocationStrategy: ChargingAllocationStrategy
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // Best ask/bid snapshot from the previous event; used for arbitrage delta detection
    private val previousSnapshot = HashMap<LocalDateTime, QuarterBestLevel>()

    /**
     * Runs one complete optimization cycle for an order-book change.
     *
     * The cycle first expires delivered positions and records their charged energy.
     * It then chooses exactly one mode:
     *
     * - Fill-up when total inventory is below actual remaining fleet demand.
     * - Arbitrage when enough total inventory exists and positions may be relocated.
     *
     * Finally, the resulting position layout is converted to group-level steering
     * signals through the shared [allocationStrategy].
     */
    @EventListener
    @Synchronized
    fun onOrderBookUpdated(event: OrderBookUpdatedEvent) {
        val updatedQuarter = event.deliveryStartTime
        val now = LocalDateTime.now()

        // ── Step 1: expire past positions and account for what was charged ────
        val expired = positionManager.expireBeforeTime(now)
        if (expired.isNotEmpty()) {
            val charged = steeringSignalDispatcher.getLastSignalsForQuarters(expired.keys)
            chargingNeedAggregator.consumeChargedEnergy(charged)
            log.info("[Optimizer] Expired {} past quarters, consumed charged energy for {} signals", expired.size, charged.size)
        }

        // ── Step 2: reject stale order-book events (market delay / manipulation) ─
        if (updatedQuarter.isBefore(now)) {
            log.warn("[Optimizer] Ignoring stale event for past quarter {} (now={})", updatedQuarter, now)
            return
        }

        // ── Step 3: full overview + flat ask list ─────────────────────────────
        val currentOverview = orderBookService.getQuarterOverviews()
        val overviewMap = currentOverview.associateBy { it.deliveryStartTime }

        // ── Step 4: phase selection ───────────────────────────────────────────
        val totalNeed = chargingNeedAggregator.totalRemainingNeed()
        val totalPos  = positionManager.totalPosition()

        log.info(
            "[Optimizer] trigger={} fromOptimizer={} totalPos={} totalNeed={}",
            updatedQuarter, event.fromOptimizer, totalPos, totalNeed
        )

        if (totalPos < totalNeed) {
            val allSellOrders = orderBookService.getAllSellOrdersSorted()
            executeFillUp(now, allSellOrders)
        } else {
            executeArbitrage(now, updatedQuarter, overviewMap)
        }

        // ── Step 5: persist snapshot for next event comparison ────────────────
        overviewMap.forEach { (k, v) -> previousSnapshot[k] = v }

        // ── Step 6: dispatch incremental steering signals to EV groups ────────
        steeringSignalDispatcher.dispatch(positionManager.getAllPositions())
    }

    // ─── Fill-up phase ────────────────────────────────────────────────────────

    /**
     * Iterates the globally sorted ask list and buys the cheapest available energy.
     *
     * Each ask is first capped by total shortfall, physical quarter headroom and
     * market quantity. The allocation strategy then determines how much of that
     * candidate quantity can actually serve currently uncovered group demand.
     *
     * Existing positions are allocated first, producing the demand not yet covered
     * by the current layout. Each purchase updates that temporary state through the
     * same strategy used by steering-signal dispatch.
     *
     * Position is updated BEFORE placeBuy so that re-entrant events see the correct
     * totalPos and terminate naturally.
     *
     * @param now current wall-clock time; asks for earlier quarters are ignored.
     * @param allSellOrders every available ask as `(quarter, order)`, sorted globally
     * by price from cheapest to most expensive.
     */
    private fun executeFillUp(now: LocalDateTime, allSellOrders: List<Pair<LocalDateTime, OrderBookEntry>>) {
        val totalNeed = chargingNeedAggregator.totalRemainingNeed()

        // Start from actual EV demand, then subtract whatever the current position
        // layout can already cover. This state is temporary and may be rebuilt after
        // any future arbitrage move; it does not permanently bind inventory to groups.
        var uncoveredRemaining = allocationStrategy.allocatePositions(
            groups = chargingNeedAggregator.groups,
            remainingNeed = chargingNeedAggregator.getGroupRemaining(),
            positions = positionManager.getAllPositions()
        ).remainingByGroup

        for ((quarter, askEntry) in allSellOrders) {
            if (quarter.isBefore(now)) continue   // skip past-quarter asks (stale order book)

            val shortfall = totalNeed - positionManager.totalPosition()
            if (shortfall <= BigDecimal.ZERO) break

            val currentQuarterPos   = positionManager.getPosition(quarter)
            val hardHeadroom = (chargingNeedAggregator.maxBuyable(quarter) - currentQuarterPos).max(BigDecimal.ZERO)
            val candidateQuantity = shortfall.min(hardHeadroom).min(askEntry.quantity)

            // One allocation call both limits the purchase to useful energy and
            // produces the uncovered-demand snapshot for the next ask.
            val allocation = allocationStrategy.allocateQuarter(
                groups = chargingNeedAggregator.groups,
                remainingNeed = uncoveredRemaining,
                quarter = quarter,
                availableEnergy = candidateQuantity
            )
            val qty = allocation.allocatedEnergy

            if (qty > BigDecimal.ZERO) {
                uncoveredRemaining = allocation.remainingByGroup
                positionManager.adjustPosition(quarter, qty)
                marketOrderClient.placeBuy(quarter, askEntry.deliveryEndTime, qty, askEntry.price)
            }
        }

        val remaining = totalNeed - positionManager.totalPosition()
        if (remaining > BigDecimal.ZERO) {
            log.warn("[FillUp] Insufficient market supply – still short {} MWh after scanning all asks", remaining)
        }
    }

    // ─── Arbitrage phase ──────────────────────────────────────────────────────

    /**
     * Relocates existing position to a more profitable quarter without changing
     * total inventory or making the charging layout infeasible.
     *
     * If the updated quarter has a cheaper ask, position may be sold from another
     * quarter and bought here. If it has a better bid, position may be sold here
     * and replaced in a cheaper quarter. Every proposed move is capped by
     * [transferableQuantity], which verifies the destination can cover the demand
     * exposed by removing energy from the source.
     *
     * @param now current time used to exclude expired candidate quarters.
     * @param updatedQuarter quarter whose best price changed.
     * @param overview current best bid/ask and quantities for every quarter.
     */
    private fun executeArbitrage(now: LocalDateTime, updatedQuarter: LocalDateTime, overview: Map<LocalDateTime, QuarterBestLevel>) {
        val qtBook   = overview[updatedQuarter] ?: return
        val prevBook = previousSnapshot[updatedQuarter]

        val newAsk  = qtBook.bestAskPrice
        val newBid  = qtBook.bestBidPrice
        val prevAsk = prevBook?.bestAskPrice
        val prevBid = prevBook?.bestBidPrice

        val askImproved = newAsk != null && (prevAsk == null || newAsk < prevAsk)
        val bidImproved = newBid != null && (prevBid == null || newBid > prevBid)

        if (!askImproved && !bidImproved) {
            log.debug("[Arb] No price improvement in quarter {}, skipping", updatedQuarter)
            return
        }

        // ── Case A: ask dropped → BUY here, SELL at other quarters with highest bid ──
        if (askImproved && newAsk != null) {
            val askQtyAtUpdated  = qtBook.bestAskQuantity ?: BigDecimal.ZERO
            var remainingToBuy   = askQtyAtUpdated

            val sellCandidates = overview.values
                .filter { it.deliveryStartTime != updatedQuarter }
                .filter { !it.deliveryStartTime.isBefore(now) }   // skip expired quarters
                .filter { it.bestBidPrice != null && it.bestBidPrice > newAsk }
                .sortedByDescending { it.bestBidPrice!! }

            for (candidate in sellCandidates) {
                if (remainingToBuy <= BigDecimal.ZERO) break
                val bidPrice = candidate.bestBidPrice ?: continue
                val bidQtyAtCandidate = candidate.bestBidQuantity ?: BigDecimal.ZERO
                val sellable = positionManager.getPosition(candidate.deliveryStartTime).min(bidQtyAtCandidate)
                val qty = transferableQuantity(
                    sourceQuarter = candidate.deliveryStartTime,
                    destinationQuarter = updatedQuarter,
                    maxQuantity = remainingToBuy.min(sellable)
                )
                if (qty > BigDecimal.ZERO) {
                    log.info(
                        "[Arb/AskDrop] SELL {}MWh@{} q={} | BUY {}MWh@{} q={} | spread={}",
                        qty, bidPrice, candidate.deliveryStartTime,
                        qty, newAsk, updatedQuarter,
                        bidPrice - newAsk
                    )
                    positionManager.adjustPosition(candidate.deliveryStartTime, qty.negate())
                    positionManager.adjustPosition(updatedQuarter, qty)
                    marketOrderClient.placeSell(candidate.deliveryStartTime, candidate.deliveryEndTime, qty, bidPrice)
                    marketOrderClient.placeBuy(updatedQuarter, qtBook.deliveryEndTime, qty, newAsk)
                    remainingToBuy -= qty
                }
            }
        }

        // ── Case B: bid rose → SELL here, BUY at other quarters with lowest ask ───
        if (bidImproved && newBid != null) {
            val bidQtyAtUpdated = qtBook.bestBidQuantity ?: BigDecimal.ZERO
            var remainingToSell = positionManager.getPosition(updatedQuarter).min(bidQtyAtUpdated) // TODO: Ensure this sell safe

            val buyCandidates = overview.values
                .filter { it.deliveryStartTime != updatedQuarter }
                .filter { !it.deliveryStartTime.isBefore(now) }   // skip expired quarters
                .filter { it.bestAskPrice != null && it.bestAskPrice < newBid }
                .sortedBy { it.bestAskPrice!! }

            for (candidate in buyCandidates) {
                if (remainingToSell <= BigDecimal.ZERO) break
                val askPrice = candidate.bestAskPrice ?: continue
                val askQtyAtCandidate    = candidate.bestAskQuantity ?: BigDecimal.ZERO
                val qty = transferableQuantity(
                    sourceQuarter = updatedQuarter,
                    destinationQuarter = candidate.deliveryStartTime,
                    maxQuantity = remainingToSell.min(askQtyAtCandidate)
                )
                if (qty > BigDecimal.ZERO) {
                    log.info(
                        "[Arb/BidRise] SELL {}MWh@{} q={} | BUY {}MWh@{} q={} | spread={}",
                        qty, newBid, updatedQuarter,
                        qty, askPrice, candidate.deliveryStartTime,
                        newBid - askPrice
                    )
                    positionManager.adjustPosition(updatedQuarter, qty.negate())
                    positionManager.adjustPosition(candidate.deliveryStartTime, qty)
                    marketOrderClient.placeSell(updatedQuarter, qtBook.deliveryEndTime, qty, newBid)
                    marketOrderClient.placeBuy(candidate.deliveryStartTime, candidate.deliveryEndTime, qty, askPrice)
                    remainingToSell -= qty
                }
            }
        }
    }

    /**
     * Calculates how much position can move without making the resulting charging
     * layout infeasible. The source position is reduced hypothetically, then the
     * shared strategy determines which demand became uncovered and whether the
     * destination quarter can cover it.
     *
     * This is a read-only simulation. The real [positionManager] is changed only
     * after the caller receives a positive quantity and executes the paired trade.
     *
     * @param sourceQuarter quarter from which inventory would be sold.
     * @param destinationQuarter quarter in which replacement inventory would be bought.
     * @param maxQuantity upper bound from market liquidity and source inventory.
     * @return the MWh that can safely be moved while respecting destination power
     * capacity, charging windows and currently uncovered group demand.
     */
    private fun transferableQuantity(
        sourceQuarter: LocalDateTime,
        destinationQuarter: LocalDateTime,
        maxQuantity: BigDecimal
    ): BigDecimal {
        if (maxQuantity <= BigDecimal.ZERO) return BigDecimal.ZERO

        val positionsAfterSale = positionManager.getAllPositions().toMutableMap()
        val sourcePosition = positionsAfterSale[sourceQuarter] ?: BigDecimal.ZERO
        val removed = sourcePosition.min(maxQuantity)
        if (removed <= BigDecimal.ZERO) return BigDecimal.ZERO

        positionsAfterSale[sourceQuarter] = sourcePosition - removed
        val uncovered = allocationStrategy.allocatePositions(
            groups = chargingNeedAggregator.groups,
            remainingNeed = chargingNeedAggregator.getGroupRemaining(),
            positions = positionsAfterSale
        ).remainingByGroup

        val destinationPosition = positionsAfterSale[destinationQuarter] ?: BigDecimal.ZERO
        val hardHeadroom = (
            chargingNeedAggregator.maxBuyable(destinationQuarter) - destinationPosition
            ).max(BigDecimal.ZERO)
        val coverableAtDestination = allocationStrategy.allocateQuarter(
            groups = chargingNeedAggregator.groups,
            remainingNeed = uncovered,
            quarter = destinationQuarter,
            availableEnergy = removed
        ).allocatedEnergy

        return removed.min(hardHeadroom).min(coverableAtDestination)
    }
}
