package com.frankenergie.smartasset.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.frankenergie.smartasset.model.MarketOrder
import com.frankenergie.smartasset.model.OrderSide
import com.frankenergie.smartasset.model.OrderUpdateRequest
import com.frankenergie.smartasset.service.OrderBookService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

/**
 * Client for market order placement.
 *
 * Flow for each trade:
 *   1. Submit order to OrderBookService.processOrder() (fromOptimizer=true so the
 *      resulting OrderBookUpdatedEvent is ignored by OptimizationService).
 *   2. On ACCEPTED response, persist a MarketOrder line to [ordersFile] using the
 *      order-id returned by the book service (ensures log ↔ book consistency).
 *
 * The log format (JSON Lines) is the source of truth for the sourcing-cost analysis
 * endpoint.  All numeric fields use their natural scale; timestamps are ISO-8601.
 */
@Component
class MarketOrderClient(
    private val objectMapper: ObjectMapper,
    private val orderBookService: OrderBookService
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val ordersFile = File("market_orders.jsonl")

    fun placeBuy(
        deliveryStart: LocalDateTime,
        deliveryEnd: LocalDateTime,
        qty: BigDecimal,
        price: BigDecimal
    ): MarketOrder = place(deliveryStart, deliveryEnd, OrderSide.BUY, qty, price)

    fun placeSell(
        deliveryStart: LocalDateTime,
        deliveryEnd: LocalDateTime,
        qty: BigDecimal,
        price: BigDecimal
    ): MarketOrder = place(deliveryStart, deliveryEnd, OrderSide.SELL, qty, price)

    private fun place(
        deliveryStart: LocalDateTime,
        deliveryEnd: LocalDateTime,
        side: OrderSide,
        qty: BigDecimal,
        price: BigDecimal
    ): MarketOrder {
        // Step 1: submit to the order book and wait for confirmation
        val request = OrderUpdateRequest(
            deliveryStartTime = deliveryStart,
            deliveryEndTime   = deliveryEnd,
            orderSide         = side,
            quantity          = qty,
            price             = price
        )
        val response = orderBookService.processOrder(request, fromOptimizer = true)

        // Step 2: build audit record using the book-assigned orderId
        val order = MarketOrder(
            orderId        = response.orderId,
            deliveryStart  = deliveryStart,
            deliveryEnd    = deliveryEnd,
            side           = side,
            quantityMwh    = qty,
            priceEurPerMwh = price,
            status         = response.status
        )
        ordersFile.appendText(objectMapper.writeValueAsString(order) + "\n")
        log.info(
            "[MarketOrder] {} {} {} MWh @ {} EUR/MWh  quarter={}  orderId={}",
            response.status, side, qty, price, deliveryStart, response.orderId
        )
        return order
    }

    // ── Log readers ───────────────────────────────────────────────────────────

    fun getAllOrders(): List<MarketOrder> {
        if (!ordersFile.exists()) return emptyList()
        return ordersFile.readLines()
            .filter { it.isNotBlank() }
            .map { objectMapper.readValue(it, MarketOrder::class.java) }
    }

    /**
     * Volume-weighted average purchase price across all BUY orders in the log.
     * Returns null when no buy orders have been placed yet.
     */
    fun averageBuyPriceEurPerMwh(): BigDecimal? {
        val buys = getAllOrders().filter { it.side == OrderSide.BUY }
        if (buys.isEmpty()) return null
        val totalCost = buys.fold(BigDecimal.ZERO) { acc, o -> acc + o.priceEurPerMwh * o.quantityMwh }
        val totalQty  = buys.fold(BigDecimal.ZERO) { acc, o -> acc + o.quantityMwh }
        return if (totalQty > BigDecimal.ZERO)
            totalCost.divide(totalQty, 4, RoundingMode.HALF_UP)
        else null
    }
}
