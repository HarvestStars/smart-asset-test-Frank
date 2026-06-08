package com.frankenergie.smartasset.model

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.LocalDateTime

data class OrderUpdateRequest(
    @JsonProperty("delivery_start_time") val deliveryStartTime: LocalDateTime,
    @JsonProperty("delivery_end_time") val deliveryEndTime: LocalDateTime,
    @JsonProperty("order_side") val orderSide: OrderSide,
    @JsonProperty("quantity") val quantity: BigDecimal,
    @JsonProperty("price") val price: BigDecimal
)
