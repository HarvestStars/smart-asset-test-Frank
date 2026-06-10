package com.frankenergie.smartasset.service

import com.frankenergie.smartasset.config.SmartAssetConfig
import com.frankenergie.smartasset.model.ChargingGroup
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Translates EV-fleet constraints into position-limit numbers for the optimizer.
 *
 * Static limits (from config):
 *   maxBuyable(q)  – physical ceiling per quarter (sum of overlapping group max-powers × 0.25 h)
 *
 * Dynamic state (updated at runtime):
 *   totalRemainingNeed() – sum of all group remaining needs
 *
 * [groupRemaining] starts equal to each group's configured neededChargeMWh and shrinks
 * as quarters expire and their charged energy is consumed via [consumeChargedEnergy].
 */
@Component
class ChargingNeedAggregator(private val config: SmartAssetConfig) {

    private val QUARTER_HOURS = BigDecimal("0.25")

    val tradingDate: LocalDate = LocalDate.parse(config.tradingDate)

    val groups: List<ChargingGroup> = config.chargingGroups.map { it.toChargingGroup() }

    // Dynamic remaining need per group — decremented when past quarters expire
    private val groupRemaining: MutableMap<String, BigDecimal> =
        groups.associate { it.name to it.neededChargeMWh }.toMutableMap()

    /**
     * All 96 quarter start times for the configured trading date (00:00 … 23:45).
     */
    fun getAllQuarters(): List<LocalDateTime> = (0 until 96).map { i ->
        LocalDateTime.of(tradingDate, LocalTime.MIDNIGHT).plusMinutes(i * 15L)
    }

    /**
     * Current snapshot of remaining charge need per group.
     * Callers should treat this as read-only; mutations go through [consumeChargedEnergy].
     */
    @Synchronized
    fun getGroupRemaining(): Map<String, BigDecimal> = groupRemaining.toMap()

    /**
     * Total MWh still required across all groups.
     * Decreases as quarters expire and their charged energy is accounted for.
     */
    @Synchronized
    fun totalRemainingNeed(): BigDecimal =
        groupRemaining.values.fold(BigDecimal.ZERO, BigDecimal::add)

    /**
     * Deducts charged energy from [groupRemaining] when a past quarter expires.
     *
     * [chargedMap] maps (groupName, quarter) → commandedEnergyMwh from the last
     * steering signal for that slot.  We assume EV groups comply with the last
     * received signal, so commanded energy equals actual energy consumed.
     */
    @Synchronized
    fun consumeChargedEnergy(chargedMap: Map<Pair<String, LocalDateTime>, BigDecimal>) {
        for ((key, charged) in chargedMap) {
            val (groupName, _) = key
            val current = groupRemaining[groupName] ?: continue
            groupRemaining[groupName] = (current - charged).max(BigDecimal.ZERO)
        }
    }

    /**
     * Hard upper bound: maximum energy physically consumable in [quarter].
     * Assumes every overlapping group still needs its full quota.
     * Returns ZERO when no group has a window covering this quarter.
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
