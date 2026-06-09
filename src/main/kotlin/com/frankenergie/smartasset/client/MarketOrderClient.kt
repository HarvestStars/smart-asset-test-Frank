package com.frankenergie.smartasset.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.frankenergie.smartasset.model.MarketOrder
import com.frankenergie.smartasset.model.OrderSide
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

/**
 * Framework client for market order placement (Requirement 5).
 *
 * In this mock all orders are assumed to be immediately and fully filled
 * at the requested price (no slippage, no partial fills).
 * Every placed order is appended as a JSON line to [ordersFile] for audit.
 *
 * TODO: replace file I/O with a real exchange adapter when needed.
 */
@Component
class MarketOrderClient(private val objectMapper: ObjectMapper) {

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
        val order = MarketOrder(
            deliveryStart = deliveryStart,
            deliveryEnd = deliveryEnd,
            side = side,
            quantityMwh = qty,
            priceEurPerMwh = price
        )
        ordersFile.appendText(objectMapper.writeValueAsString(order) + "\n")
        log.info("[MarketOrder] {} {} MWh @ {} EUR/MWh  quarter={}", side, qty, price, deliveryStart)
        return order
    }

    // ── Requirement 6: average sourcing cost ──────────────────────────────────

    fun getAllOrders(): List<MarketOrder> {
        if (!ordersFile.exists()) return emptyList()
        return ordersFile.readLines()
            .filter { it.isNotBlank() }
            .map { objectMapper.readValue(it, MarketOrder::class.java) }
    }

    /**
     * Volume-weighted average purchase price across all BUY orders.
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
