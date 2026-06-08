package com.frankenergie.smartasset.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HealthController {
    @GetMapping("/")
    fun hello() = mapOf("message" to "smart-asset-test-Frank is running")
}
