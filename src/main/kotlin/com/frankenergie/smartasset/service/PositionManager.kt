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

    /**
     * Removes all quarters whose delivery start is strictly before [cutoff] and
     * returns the entries that were removed.
     *
     * Called at the start of each optimizer cycle to expire positions for
     * quarters whose delivery window has already passed — the energy they
     * represented has been consumed by EV groups and is no longer part of
     * our tradeable inventory.
     */
    @Synchronized
    fun expireBeforeTime(cutoff: LocalDateTime): Map<LocalDateTime, BigDecimal> {
        val expired = positions.entries
            .filter { (quarter, _) -> quarter.isBefore(cutoff) }
            .associate { (quarter, qty) -> quarter to qty }
        expired.keys.forEach { positions.remove(it) }
        return expired
    }
}
