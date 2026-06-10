package com.frankenergie.smartasset.service

import com.frankenergie.smartasset.event.OrderBookUpdatedEvent
import com.frankenergie.smartasset.model.OrderBookEntry
import com.frankenergie.smartasset.model.OrderSide
import com.frankenergie.smartasset.model.OrderUpdateRequest
import com.frankenergie.smartasset.model.OrderUpdateResponse
import com.frankenergie.smartasset.model.QuarterBestLevel
import com.frankenergie.smartasset.model.QuarterOrderBookSnapshot
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

@Service
class OrderBookService(private val eventPublisher: ApplicationEventPublisher) {

    /**
     * Holds BUY and SELL order lists for a single 15-minute delivery quarter.
     * deliveryEndTime is stored here so the overview endpoint can return it
     * without having to recompute it from the start time.
     */
    private data class QuarterBook(
        val deliveryStartTime: LocalDateTime,
        val deliveryEndTime: LocalDateTime,
        val buys: MutableList<OrderBookEntry> = mutableListOf(),
        val sells: MutableList<OrderBookEntry> = mutableListOf()
    )

    // Up to 96 quarters per day; keyed by delivery_start_time
    private val books = HashMap<LocalDateTime, QuarterBook>()

    /**
     * Processes an incoming order update:
     * 1. Locate (or create) the quarter's order book.
     * 2. Match against opposite-side orders, cancelling quantities.
     * 3. Any remaining quantity is added to the book.
     * 4. Publish OrderBookUpdatedEvent so downstream listeners can react.
     *
     * [fromOptimizer] is forwarded to the event so that OptimizationService
     * can ignore events triggered by its own trades and avoid re-entrant loops.
     */
    @Synchronized
    fun processOrder(request: OrderUpdateRequest, fromOptimizer: Boolean = false): OrderUpdateResponse {
        val book = books.getOrPut(request.deliveryStartTime) {
            QuarterBook(request.deliveryStartTime, request.deliveryEndTime)
        }

        var remaining = request.quantity
        val removedIds = mutableSetOf<String>()

        // Select matchable opposite orders, best price first
        val candidates: List<OrderBookEntry> = when (request.orderSide) {
            OrderSide.BUY ->
                // For a BUY we want the cheapest SELLs where sell_price <= buy_price
                book.sells
                    .filter { it.price <= request.price }
                    .sortedBy { it.price }
            OrderSide.SELL ->
                // For a SELL we want the most expensive BUYs where buy_price >= sell_price
                book.buys
                    .filter { it.price >= request.price }
                    .sortedByDescending { it.price }
        }

        for (entry in candidates) {
            if (remaining <= BigDecimal.ZERO) break

            if (entry.quantity <= remaining) {
                // Opposite order fully consumed — schedule for removal
                remaining -= entry.quantity
                removedIds += entry.orderId
            } else {
                // Opposite order partially consumed — reduce its quantity in-place
                entry.quantity -= remaining
                remaining = BigDecimal.ZERO
            }
        }

        // Remove fully-matched opposite orders (use removeIf to avoid Collection overload ambiguity)
        when (request.orderSide) {
            OrderSide.BUY -> book.sells.removeIf { it.orderId in removedIds }
            OrderSide.SELL -> book.buys.removeIf { it.orderId in removedIds }
        }

        // Any unmatched quantity enters the book on the incoming side
        if (remaining > BigDecimal.ZERO) {
            val entry = OrderBookEntry(
                deliveryStartTime = request.deliveryStartTime,
                deliveryEndTime = request.deliveryEndTime,
                orderSide = request.orderSide,
                quantity = remaining,
                price = request.price
            )
            when (request.orderSide) {
                OrderSide.BUY -> book.buys.add(entry)
                OrderSide.SELL -> book.sells.add(entry)
            }
        }

        // Notify interested listeners (Optimization, metrics, etc.)
        eventPublisher.publishEvent(OrderBookUpdatedEvent(this, request.deliveryStartTime, fromOptimizer))

        return OrderUpdateResponse(
            orderId = UUID.randomUUID().toString(),
            status = "ACCEPTED",
            timestamp = Instant.now()
        )
    }

    /**
     * Returns the best bid/ask price and quantity for every quarter that has at
     * least one order in the book, sorted by delivery time.
     */
    @Synchronized
    fun getQuarterOverviews(): List<QuarterBestLevel> {
        return books.values
            .map { book ->
                val bestBid = book.buys.maxByOrNull { it.price }
                val bestAsk = book.sells.minByOrNull { it.price }
                QuarterBestLevel(
                    deliveryStartTime = book.deliveryStartTime,
                    deliveryEndTime = book.deliveryEndTime,
                    bestBidPrice = bestBid?.price,
                    bestBidQuantity = bestBid?.quantity,
                    bestAskPrice = bestAsk?.price,
                    bestAskQuantity = bestAsk?.quantity
                )
            }
            .sortedBy { it.deliveryStartTime }
    }

    /**
     * Returns the full order book for a specific quarter (bids sorted best-first,
     * asks sorted best-first), or null when no orders exist for that quarter.
     */
    @Synchronized
    fun getQuarterOrderBook(deliveryStartTime: LocalDateTime): QuarterOrderBookSnapshot? {
        val book = books[deliveryStartTime] ?: return null
        return QuarterOrderBookSnapshot(
            deliveryStartTime = book.deliveryStartTime,
            deliveryEndTime = book.deliveryEndTime,
            bids = book.buys.sortedByDescending { it.price },
            asks = book.sells.sortedBy { it.price }
        )
    }

    /**
     * Returns every individual SELL order across all quarters, sorted by price
     * ascending. Used by the fill-up phase to iterate over the full ask depth
     * rather than just the best ask per quarter.
     */
    @Synchronized
    fun getAllSellOrdersSorted(): List<Pair<LocalDateTime, OrderBookEntry>> =
        books.values
            .flatMap { book -> book.sells.map { entry -> book.deliveryStartTime to entry } }
            .sortedBy { (_, entry) -> entry.price }
}
