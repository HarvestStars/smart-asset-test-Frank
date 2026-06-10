package com.frankenergie.smartasset.service

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
    private val steeringSignalDispatcher: SteeringSignalDispatcher
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // Best ask/bid snapshot from the previous event; used for arbitrage delta detection
    private val previousSnapshot = HashMap<LocalDateTime, QuarterBestLevel>()

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
     * Two-level headroom per quarter:
     *   Hard limit  = maxBuyable(Q) - position(Q)   [physical power ceiling]
     *   Soft limit  = softBuyable(Q, groupRemaining) [actual remaining group needs]
     *
     * The soft limit is tighter whenever some groups have already been satisfied
     * from cheaper quarters further up the sorted list.  Without it, we could buy
     * energy for a quarter that no group still needs — stranded inventory.
     *
     * groupRemaining is updated proportionally after each purchase (same weighting
     * as SteeringSignalDispatcher.deriveSignals), keeping buying and signal-dispatch
     * consistent.
     *
     * Position is updated BEFORE placeBuy so that re-entrant events see the correct
     * totalPos and terminate naturally.
     */
    private fun executeFillUp(now: LocalDateTime, allSellOrders: List<Pair<LocalDateTime, OrderBookEntry>>) {
        val totalNeed = chargingNeedAggregator.totalRemainingNeed()
        // Seed from current remaining (not initial config) so past-quarter consumption is reflected
        val groupRemaining: MutableMap<String, BigDecimal> = chargingNeedAggregator.getGroupRemaining().toMutableMap()

        for ((quarter, askEntry) in allSellOrders) {
            if (quarter.isBefore(now)) continue   // skip past-quarter asks (stale order book)

            val shortfall = totalNeed - positionManager.totalPosition()
            if (shortfall <= BigDecimal.ZERO) break

            val hardHeadroom = (chargingNeedAggregator.maxBuyable(quarter)
                    - positionManager.getPosition(quarter)).max(BigDecimal.ZERO)
            val softLimit = chargingNeedAggregator.softBuyable(quarter, groupRemaining)
            val qty = shortfall.min(hardHeadroom).min(softLimit).min(askEntry.quantity)

            if (qty > BigDecimal.ZERO) {
                applyGroupAllocation(quarter, qty, groupRemaining)
                positionManager.adjustPosition(quarter, qty)
                marketOrderClient.placeBuy(quarter, askEntry.deliveryEndTime, qty, askEntry.price)
            }
        }

        val remaining = totalNeed - positionManager.totalPosition()
        if (remaining > BigDecimal.ZERO) {
            log.warn("[FillUp] Insufficient market supply – still short {} MWh after scanning all asks", remaining)
        }
    }

    /**
     * Mirrors SteeringSignalDispatcher's proportional allocation: deducts from
     * [groupRemaining] in proportion to each active group's desire for [quarter].
     * Called after each fill-up purchase so the soft limit stays accurate.
     */
    private fun applyGroupAllocation(
        quarter: LocalDateTime,
        qtyBought: BigDecimal,
        groupRemaining: MutableMap<String, BigDecimal>
    ) {
        val active = chargingNeedAggregator.groups.filter { g ->
            chargingNeedAggregator.isInGroupWindow(quarter, g) &&
            (groupRemaining[g.name] ?: BigDecimal.ZERO) > BigDecimal.ZERO
        }
        if (active.isEmpty()) return

        val desires = active.associate { g ->
            g.name to (groupRemaining[g.name]!!).min(g.maxPowerMW * BigDecimal("0.25"))
        }
        val totalDesired = desires.values.fold(BigDecimal.ZERO, BigDecimal::add)
        if (totalDesired <= BigDecimal.ZERO) return

        val scale = if (totalDesired > qtyBought)
            qtyBought.divide(totalDesired, 10, java.math.RoundingMode.HALF_UP)
        else BigDecimal.ONE

        for (g in active) {
            val alloc = (desires[g.name]!! * scale).setScale(4, java.math.RoundingMode.HALF_UP)
            groupRemaining[g.name] = (groupRemaining[g.name]!! - alloc).max(BigDecimal.ZERO)
        }
    }

    // ─── Arbitrage phase ──────────────────────────────────────────────────────

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

        // Snapshot of actual remaining need — used as soft cap for arbitrage buys
        val currentGroupRemaining = chargingNeedAggregator.getGroupRemaining()

        // ── Case A: ask dropped → BUY here, SELL at other quarters with highest bid ──
        if (askImproved && newAsk != null) {
            val askQtyAtUpdated = qtBook.bestAskQuantity ?: BigDecimal.ZERO
            val maxBuyable = (chargingNeedAggregator.maxBuyable(updatedQuarter)
                    - positionManager.getPosition(updatedQuarter)).max(BigDecimal.ZERO)
            val softLimit = chargingNeedAggregator.softBuyable(updatedQuarter, currentGroupRemaining)
            var remainingToBuy = maxBuyable.min(askQtyAtUpdated).min(softLimit)

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
                val qty = remainingToBuy.min(sellable)
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
            var remainingToSell = positionManager.getPosition(updatedQuarter).min(bidQtyAtUpdated)

            val buyCandidates = overview.values
                .filter { it.deliveryStartTime != updatedQuarter }
                .filter { !it.deliveryStartTime.isBefore(now) }   // skip expired quarters
                .filter { it.bestAskPrice != null && it.bestAskPrice < newBid }
                .sortedBy { it.bestAskPrice!! }

            for (candidate in buyCandidates) {
                if (remainingToSell <= BigDecimal.ZERO) break
                val askPrice = candidate.bestAskPrice ?: continue
                val askQtyAtCandidate = candidate.bestAskQuantity ?: BigDecimal.ZERO
                val softLimitAtCandidate = chargingNeedAggregator.softBuyable(candidate.deliveryStartTime, currentGroupRemaining)
                val buyable = (chargingNeedAggregator.maxBuyable(candidate.deliveryStartTime)
                        - positionManager.getPosition(candidate.deliveryStartTime)).max(BigDecimal.ZERO)
                        .min(askQtyAtCandidate)
                        .min(softLimitAtCandidate)
                val qty = remainingToSell.min(buyable)
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
}
