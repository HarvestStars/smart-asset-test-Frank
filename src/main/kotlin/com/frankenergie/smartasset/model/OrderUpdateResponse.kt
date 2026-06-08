package com.frankenergie.smartasset.model

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

data class OrderUpdateResponse(
    @JsonProperty("order_id") val orderId: String,
    @JsonProperty("status") val status: String,
    @JsonProperty("timestamp") val timestamp: Instant
)
