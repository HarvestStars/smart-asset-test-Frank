package com.frankenergie.smartasset.service

import com.frankenergie.smartasset.client.MarketOrderClient
import com.frankenergie.smartasset.client.SteeringSignalDispatcher
import com.frankenergie.smartasset.event.OrderBookUpdatedEvent
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
 *  │ Action:      greedy buy-only; cheapest ask first across all quarters;      │
 *  │              respects per-quarter maxBuyable limit (physical power cap).   │
 *  │ Exit:        totalPosition ≥ totalEVRemainingNeed                          │
 *  └────────────────────────────────────────────────────────────────────────────┘
 *  ┌─ ARBITRAGE ────────────────────────────────────────────────────────────────┐
 *  │ Active when: totalPosition ≥ totalEVRemainingNeed                          │
 *  │ Anchor:      only the quarter that triggered this event (O(n) not O(n²))   │
 *  │ Early-exit:  if neither ask improved nor bid improved → skip               │
 *  │ If ask dropped (better buy):  BUY here, SELL at quarters with highest bid  │
 *  │ If bid rose  (better sell):   SELL here, BUY at quarters with lowest ask   │
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

        // ── Step 1: full overview of all quarters ─────────────────────────────
        val currentOverview = orderBookService.getQuarterOverviews()
        val overviewMap = currentOverview.associateBy { it.deliveryStartTime }

        // ── Step 2: phase selection ───────────────────────────────────────────
        val totalNeed = chargingNeedAggregator.totalRemainingNeed()
        val totalPos  = positionManager.totalPosition()

        log.info("[Optimizer] trigger={} totalPos={} totalNeed={}", updatedQuarter, totalPos, totalNeed)

        if (totalPos < totalNeed) {
            executeFillUp(totalNeed - totalPos, overviewMap)
        } else {
            executeArbitrage(updatedQuarter, overviewMap)
        }

        // ── Step 3: persist snapshot for next event comparison ────────────────
        overviewMap.forEach { (k, v) -> previousSnapshot[k] = v }

        // ── Step 4: dispatch incremental steering signals to EV groups ────────
        steeringSignalDispatcher.dispatch(positionManager.getAllPositions(), overviewMap)
    }

    // ─── Fill-up phase ────────────────────────────────────────────────────────

    private fun executeFillUp(shortfall: BigDecimal, overview: Map<LocalDateTime, QuarterBestLevel>) {
        val sortedAsks = overview.values
            .filter { it.bestAskPrice != null }
            .sortedBy { it.bestAskPrice!! }

        var toFill = shortfall

        for (level in sortedAsks) {
            if (toFill <= BigDecimal.ZERO) break

            val headroom = (chargingNeedAggregator.maxBuyable(level.deliveryStartTime)
                    - positionManager.getPosition(level.deliveryStartTime)).max(BigDecimal.ZERO)
            val qty = toFill.min(headroom)

            if (qty > BigDecimal.ZERO) {
                marketOrderClient.placeBuy(level.deliveryStartTime, level.deliveryEndTime, qty, level.bestAskPrice!!)
                positionManager.adjustPosition(level.deliveryStartTime, qty)
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
        if (askImproved && newAsk != null) {
            val maxBuyable = (chargingNeedAggregator.maxBuyable(updatedQuarter)
                    - positionManager.getPosition(updatedQuarter)).max(BigDecimal.ZERO)
            var remainingToBuy = maxBuyable

            val sellCandidates = overview.values
                .filter { it.deliveryStartTime != updatedQuarter }
                .filter { it.bestBidPrice != null && it.bestBidPrice > newAsk }
                .sortedByDescending { it.bestBidPrice!! }

            for (candidate in sellCandidates) {
                if (remainingToBuy <= BigDecimal.ZERO) break
                val sellable = positionManager.getPosition(candidate.deliveryStartTime)
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
        if (bidImproved && newBid != null) {
            var remainingToSell = positionManager.getPosition(updatedQuarter)

            val buyCandidates = overview.values
                .filter { it.deliveryStartTime != updatedQuarter }
                .filter { it.bestAskPrice != null && it.bestAskPrice < newBid }
                .sortedBy { it.bestAskPrice!! }

            for (candidate in buyCandidates) {
                if (remainingToSell <= BigDecimal.ZERO) break
                val buyable = (chargingNeedAggregator.maxBuyable(candidate.deliveryStartTime)
                        - positionManager.getPosition(candidate.deliveryStartTime)).max(BigDecimal.ZERO)
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
