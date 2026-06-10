package com.frankenergie.smartasset.controller

import com.frankenergie.smartasset.model.QuarterBestLevel
import com.frankenergie.smartasset.model.QuarterOrderBookSnapshot
import com.frankenergie.smartasset.service.OrderBookService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

@RestController
@RequestMapping("/api")
class OrderBookOverviewController(private val orderBookService: OrderBookService) {

    /**
     * Returns the best bid/ask price and quantity for every quarter currently
     * present in the order book, sorted by delivery time.
     *
     * GET /api/overview
     */
    @GetMapping("/overview")
    fun getOverview(): ResponseEntity<List<QuarterBestLevel>> =
        ResponseEntity.ok(orderBookService.getQuarterOverviews())

    /**
     * Returns the full order book (all bid and ask levels) for the given quarter.
     * Bids are sorted best (highest) price first; asks are sorted best (lowest) price first.
     *
     * GET /api/orderbook?deliveryStartTime=2025-01-01T13:00:00
     */
    @GetMapping("/orderbook")
    fun getQuarterOrderBook(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) deliveryStartTime: LocalDateTime
    ): ResponseEntity<QuarterOrderBookSnapshot> {
        val snapshot = orderBookService.getQuarterOrderBook(deliveryStartTime)
        return if (snapshot != null) ResponseEntity.ok(snapshot)
        else ResponseEntity.notFound().build()
    }
}
