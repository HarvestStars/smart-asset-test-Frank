package com.frankenergie.smartasset.model

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.LocalDateTime

/** Full order book for a single quarter, returned by GET /api/orderbook. */
data class QuarterOrderBookSnapshot(
    @JsonProperty("delivery_start_time") val deliveryStartTime: LocalDateTime,
    @JsonProperty("delivery_end_time") val deliveryEndTime: LocalDateTime,
    /** BUY orders sorted by price descending (best bid first). */
    @JsonProperty("bids") val bids: List<OrderBookEntry>,
    /** SELL orders sorted by price ascending (best ask first). */
    @JsonProperty("asks") val asks: List<OrderBookEntry>
)
