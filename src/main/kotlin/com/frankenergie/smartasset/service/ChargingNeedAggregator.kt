package com.frankenergie.smartasset.service

import com.frankenergie.smartasset.model.ChargingGroup
import org.springframework.stereotype.Component
import java.math.BigDecimal
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
 * The optimizer only sees these two numbers – it never looks at individual
 * groups directly.
 */
@Component
class ChargingNeedAggregator {

    private val QUARTER_HOURS = BigDecimal("0.25")

    val groups: List<ChargingGroup> = listOf(
        ChargingGroup("A", LocalTime.of(0,  0),  LocalTime.of(8,  30), BigDecimal("5"),  BigDecimal("2")),
        ChargingGroup("B", LocalTime.of(0,  0),  LocalTime.of(11, 0),  BigDecimal("10"), BigDecimal("3")),
        ChargingGroup("C", LocalTime.of(13, 0),  LocalTime.of(18, 0),  BigDecimal("4"),  BigDecimal("1")),
        ChargingGroup("D", LocalTime.of(13, 0),  LocalTime.of(21, 0),  BigDecimal("20"), BigDecimal("6")),
        ChargingGroup("E", LocalTime.of(17, 30), LocalTime.of(22, 0),  BigDecimal("5"),  BigDecimal("2")),
        ChargingGroup("F", LocalTime.of(17, 30), LocalTime.of(23, 59), BigDecimal("15"), BigDecimal("5"))
    )  // total = 59 MWh

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
