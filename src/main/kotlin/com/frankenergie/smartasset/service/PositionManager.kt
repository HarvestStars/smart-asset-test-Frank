package com.frankenergie.smartasset.service

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Tracks the net energy position (MWh) held per delivery quarter.
 * Positive value = we own that energy (bought, not yet delivered).
 * This is the single source of truth for the market inventory layer;
 * EV-group concerns are kept entirely separate.
 */
@Component
class PositionManager {

    private val positions = HashMap<LocalDateTime, BigDecimal>()

    @Synchronized
    fun getPosition(quarter: LocalDateTime): BigDecimal =
        positions.getOrDefault(quarter, BigDecimal.ZERO)

    @Synchronized
    fun adjustPosition(quarter: LocalDateTime, delta: BigDecimal) {
        positions[quarter] = getPosition(quarter) + delta
    }

    @Synchronized
    fun totalPosition(): BigDecimal =
        positions.values.fold(BigDecimal.ZERO, BigDecimal::add)

    @Synchronized
    fun getAllPositions(): Map<LocalDateTime, BigDecimal> = positions.toMap()
}
