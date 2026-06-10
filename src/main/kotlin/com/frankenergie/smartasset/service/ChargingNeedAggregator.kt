package com.frankenergie.smartasset.service

import com.frankenergie.smartasset.config.SmartAssetConfig
import com.frankenergie.smartasset.model.ChargingGroup
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Translates EV-fleet constraints into two position-limit numbers that the
 * market trading layer consumes:
 *
 *   maxBuyable(q)      – physical ceiling: how much energy can actually be
 *                        consumed in quarter q (sum of max-power across all
 *                        groups whose window covers q × 0.25 h).
 *                        Prevents over-buying energy that cannot be used.
 *
 *   totalRemainingNeed – total MWh still required across all groups.
 *                        Used as the fill-up target; arbitrage keeps total
 *                        position at or above this floor.
 *
 * Groups and trading date are loaded from application.properties via SmartAssetConfig.
 * The optimizer only sees these two numbers – it never looks at individual
 * groups directly.
 */
@Component
class ChargingNeedAggregator(private val config: SmartAssetConfig) {

    private val QUARTER_HOURS = BigDecimal("0.25")

    val tradingDate: LocalDate = LocalDate.parse(config.tradingDate)

    val groups: List<ChargingGroup> = config.chargingGroups.map { it.toChargingGroup() }

    /**
     * All 96 quarter start times for the configured trading date (00:00 … 23:45),
     * useful for iterating over every delivery slot regardless of whether the
     * order book currently has orders for that slot.
     */
    fun getAllQuarters(): List<LocalDateTime> = (0 until 96).map { i ->
        LocalDateTime.of(tradingDate, LocalTime.MIDNIGHT).plusMinutes(i * 15L)
    }

    /** Sum of all group needs (simplified: no time-passing simulation). */
    fun totalRemainingNeed(): BigDecimal =
        groups.sumOf { it.neededChargeMWh }

    /**
     * Maximum energy physically consumable in [quarter].
     * Returns ZERO when no group has a window covering this quarter,
     * which prevents the optimizer from buying "stranded" energy.
     */
    fun maxBuyable(quarter: LocalDateTime): BigDecimal {
        return groups
            .filter { isInGroupWindow(quarter, it) }
            .fold(BigDecimal.ZERO) { acc, g -> acc + g.maxPowerMW * QUARTER_HOURS }
    }

    fun isInGroupWindow(quarter: LocalDateTime, group: ChargingGroup): Boolean {
        val t = quarter.toLocalTime()
        return !t.isBefore(group.startTime) && t.isBefore(group.endTime)
    }
}
