package com.frankenergie.smartasset.controller

import com.frankenergie.smartasset.model.QuarterBestLevel
import com.frankenergie.smartasset.service.OrderBookService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class OrderBookOverviewController(private val orderBookService: OrderBookService) {

    /**
     * Returns the best bid (highest BUY price) and best ask (lowest SELL price)
     * for every quarter currently present in the order book, sorted by delivery time.
     *
     * GET /api/overview
     */
    @GetMapping("/overview")
    fun getOverview(): ResponseEntity<List<QuarterBestLevel>> {
        return ResponseEntity.ok(orderBookService.getQuarterOverviews())
    }
}
