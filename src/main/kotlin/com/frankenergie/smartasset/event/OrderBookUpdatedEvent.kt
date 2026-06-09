package com.frankenergie.smartasset.event

import org.springframework.context.ApplicationEvent
import java.time.LocalDateTime

/**
 * Published every time a quarter's order book is mutated (order added or matched).
 * Downstream components (e.g. OptimizationService) can subscribe with @EventListener
 * without creating a compile-time dependency on OrderBookService.
 */
class OrderBookUpdatedEvent(
    source: Any,
    val deliveryStartTime: LocalDateTime
) : ApplicationEvent(source)
