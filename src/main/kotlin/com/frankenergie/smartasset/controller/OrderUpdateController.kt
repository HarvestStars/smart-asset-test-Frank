package com.frankenergie.smartasset.controller

import com.frankenergie.smartasset.model.OrderUpdateRequest
import com.frankenergie.smartasset.model.OrderUpdateResponse
import com.frankenergie.smartasset.service.OrderBookService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class OrderUpdateController(private val orderBookService: OrderBookService) {

    @PostMapping("/orderupdate")
    fun orderUpdate(@RequestBody request: OrderUpdateRequest): ResponseEntity<OrderUpdateResponse> {
        val response = orderBookService.processOrder(request)
        return ResponseEntity.ok(response)
    }
}
