package com.frankenergie.smartasset.model

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

data class OrderBookEntry(
    val orderId: String = UUID.randomUUID().toString(),
    val deliveryStartTime: LocalDateTime,
    val deliveryEndTime: LocalDateTime,
    val orderSide: OrderSide,
    var quantity: BigDecimal,
    val price: BigDecimal,
    val createdAt: Instant = Instant.now()
)
