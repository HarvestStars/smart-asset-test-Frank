package com.frankenergie.smartasset.controller

import com.frankenergie.smartasset.client.MarketOrderClient
import com.frankenergie.smartasset.model.OrderSide
import com.frankenergie.smartasset.model.SourcingCostResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode

@RestController
@RequestMapping("/api")
class SourcingCostController(private val marketOrderClient: MarketOrderClient) {

    /**
     * Reads market_orders.jsonl and computes the volume-weighted average price
     * paid per MWh across all BUY orders placed by the optimizer.
     *
     * This reflects the actual sourcing cost for the EVs: each BUY order in the
     * log was confirmed by the order-processing API before being written, so the
     * log is the authoritative record of executed trades.
     *
     * GET /api/sourcing-cost
     */
    @GetMapping("/sourcing-cost")
    fun getSourcingCost(): ResponseEntity<SourcingCostResponse> {
        val buys = marketOrderClient.getAllOrders().filter { it.side == OrderSide.BUY }

        val totalVolume = buys.fold(BigDecimal.ZERO) { acc, o -> acc + o.quantityMwh }
        val totalCost   = buys.fold(BigDecimal.ZERO) { acc, o -> acc + o.priceEurPerMwh * o.quantityMwh }
        val vwap = if (totalVolume > BigDecimal.ZERO)
            totalCost.divide(totalVolume, 4, RoundingMode.HALF_UP)
        else null

        return ResponseEntity.ok(
            SourcingCostResponse(
                averageBuyPriceEurPerMwh = vwap,
                totalBuyVolumeMwh        = totalVolume,
                totalCostEur             = totalCost,
                buyOrderCount            = buys.size
            )
        )
    }
}
