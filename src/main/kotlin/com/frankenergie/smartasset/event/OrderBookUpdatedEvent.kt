package com.frankenergie.smartasset.event

import org.springframework.context.ApplicationEvent
import java.time.LocalDateTime

/**
 * Published every time a quarter's order book is mutated (order added or matched).
 * Downstream components (e.g. OptimizationService) can subscribe with @EventListener
 * without creating a compile-time dependency on OrderBookService.
 *
 * [fromOptimizer] is true when the triggering order was placed by the optimizer itself
 * (via MarketOrderClient). OptimizationService ignores such events to prevent
 * re-entrant optimization loops on its own trades.
 */
class OrderBookUpdatedEvent(
    source: Any,
    val deliveryStartTime: LocalDateTime,
    val fromOptimizer: Boolean = false
) : ApplicationEvent(source)
