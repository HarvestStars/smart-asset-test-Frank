package com.frankenergie.smartasset.model

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

data class MarketOrder(
    @JsonProperty("order_id")           val orderId: String = UUID.randomUUID().toString(),
    @JsonProperty("delivery_start")     val deliveryStart: LocalDateTime,
    @JsonProperty("delivery_end")       val deliveryEnd: LocalDateTime,
    @JsonProperty("side")               val side: OrderSide,
    @JsonProperty("quantity_mwh")       val quantityMwh: BigDecimal,
    @JsonProperty("price_eur_per_mwh")  val priceEurPerMwh: BigDecimal,
    // Confirmation status returned by the order processing API ("ACCEPTED", etc.)
    @JsonProperty("status")             val status: String = "ACCEPTED",
    @JsonProperty("timestamp")          val timestamp: Instant = Instant.now()
)
