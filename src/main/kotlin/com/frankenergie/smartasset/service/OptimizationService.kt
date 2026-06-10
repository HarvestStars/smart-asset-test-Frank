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
 *  │              Per-quarter cap: maxBuyable(q) - currentPosition(q).         │
 *  │              Per-order cap: min(toFill, headroom, askEntry.quantity).      │
 *  │ Exit:        totalPosition ≥ totalEVRemainingNeed                          │
 *  └────────────────────────────────────────────────────────────────────────────┘
 *  ┌─ ARBITRAGE ────────────────────────────────────────────────────────────────┐
 *  │ Active when: totalPosition ≥ totalEVRemainingNeed                          │
 *  │ Anchor:      only the quarter that triggered this event (O(n) not O(n²))   │
 *  │ Early-exit:  if neither ask improved nor bid improved → skip               │
 *  │ If ask dropped:  BUY here (capped by bestAskQty),                         │
 *  │                  SELL at candidates (capped by min(pos, bestBidQty))       │
 *  │ If bid rose:     SELL here (capped by min(pos, bestBidQty)),               │
 *  │                  BUY at candidates (capped by min(headroom, bestAskQty))   │
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

        // ── Step 1: full overview + flat ask list ─────────────────────────────
        val currentOverview = orderBookService.getQuarterOverviews()
        val overviewMap = currentOverview.associateBy { it.deliveryStartTime }

        // ── Step 2: phase selection ───────────────────────────────────────────
        val totalNeed = chargingNeedAggregator.totalRemainingNeed()
        val totalPos  = positionManager.totalPosition()

        log.info("[Optimizer] trigger={} totalPos={} totalNeed={}", updatedQuarter, totalPos, totalNeed)

        if (totalPos < totalNeed) {
            val allSellOrders = orderBookService.getAllSellOrdersSorted()
            executeFillUp(totalNeed - totalPos, allSellOrders)
        } else {
            executeArbitrage(updatedQuarter, overviewMap)
        }

        // ── Step 3: persist snapshot for next event comparison ────────────────
        overviewMap.forEach { (k, v) -> previousSnapshot[k] = v }

        // ── Step 4: dispatch incremental steering signals to EV groups ────────
        steeringSignalDispatcher.dispatch(positionManager.getAllPositions(), overviewMap)
    }

    // ─── Fill-up phase ────────────────────────────────────────────────────────

    /**
     * Iterates over every individual sell order across all quarters (sorted cheapest
     * first) and buys as much as possible, respecting:
     *   - [shortfall]: total MWh still needed
     *   - maxBuyable(q) - currentPos(q): physical power headroom per quarter
     *   - askEntry.quantity: market depth of each individual ask level
     */
    private fun executeFillUp(shortfall: BigDecimal, allSellOrders: List<Pair<LocalDateTime, OrderBookEntry>>) {
        var toFill = shortfall

        for ((quarter, askEntry) in allSellOrders) {
            if (toFill <= BigDecimal.ZERO) break

            val headroom = (chargingNeedAggregator.maxBuyable(quarter)
                    - positionManager.getPosition(quarter)).max(BigDecimal.ZERO)
            val qty = toFill.min(headroom).min(askEntry.quantity)

            if (qty > BigDecimal.ZERO) {
                marketOrderClient.placeBuy(quarter, askEntry.deliveryEndTime, qty, askEntry.price)
                positionManager.adjustPosition(quarter, qty)
                toFill -= qty
            }
        }

        if (toFill > BigDecimal.ZERO) {
            log.warn("[FillUp] Insufficient market supply – still short {} MWh after scanning all asks", toFill)
        }
    }

    // ─── Arbitrage phase ──────────────────────────────────────────────────────

    private fun executeArbitrage(updatedQuarter: LocalDateTime, overview: Map<LocalDateTime, QuarterBestLevel>) {
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
        // Cap remainingToBuy by the available ask quantity to avoid committing
        // more sells than we can actually cover with the buy at this quarter.
        if (askImproved && newAsk != null) {
            val askQtyAtUpdated = qtBook.bestAskQuantity ?: BigDecimal.ZERO
            val maxBuyable = (chargingNeedAggregator.maxBuyable(updatedQuarter)
                    - positionManager.getPosition(updatedQuarter)).max(BigDecimal.ZERO)
            var remainingToBuy = maxBuyable.min(askQtyAtUpdated)

            val sellCandidates = overview.values
                .filter { it.deliveryStartTime != updatedQuarter }
                .filter { it.bestBidPrice != null && it.bestBidPrice > newAsk }
                .sortedByDescending { it.bestBidPrice!! }

            for (candidate in sellCandidates) {
                if (remainingToBuy <= BigDecimal.ZERO) break
                // Cap sellable by bestBidQty: selling more than the bid can absorb is pointless
                val bidQtyAtCandidate = candidate.bestBidQuantity ?: BigDecimal.ZERO
                val sellable = positionManager.getPosition(candidate.deliveryStartTime).min(bidQtyAtCandidate)
                val qty = remainingToBuy.min(sellable)
                if (qty > BigDecimal.ZERO) {
                    log.info(
                        "[Arb/AskDrop] SELL {}MWh@{} q={} | BUY {}MWh@{} q={} | spread={}",
                        qty, candidate.bestBidPrice, candidate.deliveryStartTime,
                        qty, newAsk, updatedQuarter,
                        candidate.bestBidPrice!! - newAsk
                    )
                    marketOrderClient.placeSell(candidate.deliveryStartTime, candidate.deliveryEndTime, qty, candidate.bestBidPrice!!)
                    marketOrderClient.placeBuy(updatedQuarter, qtBook.deliveryEndTime, qty, newAsk)
                    positionManager.adjustPosition(candidate.deliveryStartTime, qty.negate())
                    positionManager.adjustPosition(updatedQuarter, qty)
                    remainingToBuy -= qty
                }
            }
        }

        // ── Case B: bid rose → SELL here, BUY at other quarters with lowest ask ───
        // Cap remainingToSell by bestBidQty: we can only hit as much of the bid as exists.
        if (bidImproved && newBid != null) {
            val bidQtyAtUpdated = qtBook.bestBidQuantity ?: BigDecimal.ZERO
            var remainingToSell = positionManager.getPosition(updatedQuarter).min(bidQtyAtUpdated)

            val buyCandidates = overview.values
                .filter { it.deliveryStartTime != updatedQuarter }
                .filter { it.bestAskPrice != null && it.bestAskPrice < newBid }
                .sortedBy { it.bestAskPrice!! }

            for (candidate in buyCandidates) {
                if (remainingToSell <= BigDecimal.ZERO) break
                // Cap buyable by bestAskQty: buying more than the ask offers is impossible
                val askQtyAtCandidate = candidate.bestAskQuantity ?: BigDecimal.ZERO
                val buyable = (chargingNeedAggregator.maxBuyable(candidate.deliveryStartTime)
                        - positionManager.getPosition(candidate.deliveryStartTime)).max(BigDecimal.ZERO)
                        .min(askQtyAtCandidate)
                val qty = remainingToSell.min(buyable)
                if (qty > BigDecimal.ZERO) {
                    log.info(
                        "[Arb/BidRise] SELL {}MWh@{} q={} | BUY {}MWh@{} q={} | spread={}",
                        qty, newBid, updatedQuarter,
                        qty, candidate.bestAskPrice, candidate.deliveryStartTime,
                        newBid - candidate.bestAskPrice!!
                    )
                    marketOrderClient.placeSell(updatedQuarter, qtBook.deliveryEndTime, qty, newBid)
                    marketOrderClient.placeBuy(candidate.deliveryStartTime, candidate.deliveryEndTime, qty, candidate.bestAskPrice!!)
                    positionManager.adjustPosition(updatedQuarter, qty.negate())
                    positionManager.adjustPosition(candidate.deliveryStartTime, qty)
                    remainingToSell -= qty
                }
            }
        }
    }
}
