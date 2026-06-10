package com.frankenergie.smartasset.model

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal

/**
 * Response body for GET /api/sourcing-cost.
 * All figures are derived from the market_orders.jsonl audit log (BUY side only).
 */
data class SourcingCostResponse(
    /** Volume-weighted average price paid per MWh purchased; null if no buys yet. */
    @JsonProperty("average_buy_price_eur_per_mwh") val averageBuyPriceEurPerMwh: BigDecimal?,
    /** Total energy purchased across all BUY orders (MWh). */
    @JsonProperty("total_buy_volume_mwh")          val totalBuyVolumeMwh: BigDecimal,
    /** Total money spent: sum(price × qty) across all BUY orders (EUR). */
    @JsonProperty("total_cost_eur")                val totalCostEur: BigDecimal,
    /** Number of individual BUY order log entries analysed. */
    @JsonProperty("buy_order_count")               val buyOrderCount: Int
)
