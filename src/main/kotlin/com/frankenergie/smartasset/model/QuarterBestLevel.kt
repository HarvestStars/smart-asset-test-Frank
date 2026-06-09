package com.frankenergie.smartasset.model

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.LocalDateTime

data class QuarterBestLevel(
    @JsonProperty("delivery_start_time") val deliveryStartTime: LocalDateTime,
    @JsonProperty("delivery_end_time") val deliveryEndTime: LocalDateTime,
    // Highest price among all BUY orders in this quarter
    @JsonProperty("best_bid_price") val bestBidPrice: BigDecimal?,
    // Lowest price among all SELL orders in this quarter
    @JsonProperty("best_ask_price") val bestAskPrice: BigDecimal?
)
