package com.frankenergie.smartasset.allocation

import com.frankenergie.smartasset.model.ChargingGroup
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

/**
 * Default charging-allocation policy.
 *
 * For each quarter, an active group's desired energy is:
 *
 * `min(group remaining need, group max power * 0.25 hours)`
 *
 * If the quarter contains less energy than all active groups desire, its energy
 * is divided proportionally to those desired amounts. Because desired energy is
 * capped by max power, this is effectively a power-weighted allocation while
 * every group still has substantial demand.
 *
 * The strategy is stateless: it returns new maps and never changes persistent
 * demand or positions. This allows Fill-up, arbitrage simulation and signal
 * dispatch to use exactly the same interpretation of a position layout.
 */
@Component
class PowerWeightedAllocationStrategy : ChargingAllocationStrategy {

    private val quarterHours = BigDecimal("0.25")

    /**
     * Walks the supplied positions in delivery-time order and repeatedly assigns
     * each quarter against the demand left by earlier quarters.
     *
     * Time ordering matters: once an earlier position covers part of a group's
     * demand, later positions only see that group's still-uncovered amount.
     */
    override fun allocatePositions(
        groups: List<ChargingGroup>,
        remainingNeed: Map<String, BigDecimal>,
        positions: Map<LocalDateTime, BigDecimal>
    ): ChargingAllocationPlan {
        var remaining = remainingNeed.withDefaultsFor(groups)
        val allocations = mutableMapOf<Pair<String, LocalDateTime>, BigDecimal>()

        for ((quarter, position) in positions.toSortedMap()) {
            val result = allocateQuarter(groups, remaining, quarter, position)
            result.allocations.forEach { (groupName, energy) ->
                allocations[groupName to quarter] = energy
            }
            remaining = result.remainingByGroup
        }

        return ChargingAllocationPlan(
            allocations = allocations,
            remainingByGroup = remaining
        )
    }

    /**
     * Calculates the useful allocation capacity of one quarter.
     *
     * Groups outside the quarter's charging window are ignored. Active groups
     * receive up to their quarter power limit and remaining need. When energy is
     * scarce, each active group receives the same proportion of its desired amount.
     *
     * Allocations use four decimal places of MWh. Values are rounded down first,
     * then leftover 0.0001 MWh units are assigned by largest fractional remainder.
     * This preserves the proportional result without allocating more energy than
     * [availableEnergy].
     */
    override fun allocateQuarter(
        groups: List<ChargingGroup>,
        remainingNeed: Map<String, BigDecimal>,
        quarter: LocalDateTime,
        availableEnergy: BigDecimal
    ): QuarterAllocation {
        val remaining = remainingNeed.withDefaultsFor(groups).toMutableMap()
        if (availableEnergy <= BigDecimal.ZERO) {
            return QuarterAllocation(emptyMap(), remaining)
        }

        // Only groups that can physically charge now and still need energy compete.
        val active = groups.filter { group ->
            isInGroupWindow(quarter, group) &&
                remaining.getValue(group.name) > BigDecimal.ZERO
        }
        if (active.isEmpty()) {
            return QuarterAllocation(emptyMap(), remaining)
        }

        // A group's quarter desire is limited by both demand and charging power.
        val desires = active.associate { group ->
            group.name to remaining.getValue(group.name)
                .min(group.maxPowerMW * quarterHours)
        }
        val totalDesired = desires.values.fold(BigDecimal.ZERO, BigDecimal::add)
        if (totalDesired <= BigDecimal.ZERO) {
            return QuarterAllocation(emptyMap(), remaining)
        }

        val targetEnergy = totalDesired.min(availableEnergy).setScale(4, RoundingMode.DOWN)
        val scale = if (totalDesired > targetEnergy) {
            targetEnergy.divide(totalDesired, 20, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ONE
        }

        // Calculate exact proportional shares before applying storage precision.
        val rawAllocations = active.associate { group ->
            group.name to desires.getValue(group.name) * scale
        }
        val allocations = active.associate { group ->
            group.name to rawAllocations.getValue(group.name)
                .setScale(4, RoundingMode.DOWN)
        }.toMutableMap()

        val unit = BigDecimal("0.0001")
        var undistributed = targetEnergy -
            allocations.values.fold(BigDecimal.ZERO, BigDecimal::add)
        val largestRemainders = active.sortedByDescending { group ->
            rawAllocations.getValue(group.name) - allocations.getValue(group.name)
        }
        // Return rounding residue to the groups that lost the largest fractions.
        for (group in largestRemainders) {
            if (undistributed < unit) break
            val current = allocations.getValue(group.name)
            if (current + unit <= desires.getValue(group.name)) {
                allocations[group.name] = current + unit
                undistributed -= unit
            }
        }

        val nonZeroAllocations = linkedMapOf<String, BigDecimal>()
        var energyLeft = targetEnergy
        for (group in active) {
            val allocation = allocations.getValue(group.name).min(energyLeft)
            if (allocation <= BigDecimal.ZERO) continue
            nonZeroAllocations[group.name] = allocation
            remaining[group.name] = (remaining.getValue(group.name) - allocation)
                .max(BigDecimal.ZERO)
            energyLeft -= allocation
        }

        return QuarterAllocation(nonZeroAllocations, remaining)
    }

    /**
     * Normalizes a demand snapshot so every configured group has an entry.
     * Missing entries mean zero remaining demand.
     */
    private fun Map<String, BigDecimal>.withDefaultsFor(
        groups: List<ChargingGroup>
    ): Map<String, BigDecimal> =
        groups.associate { group ->
            group.name to (this[group.name] ?: BigDecimal.ZERO)
        }

    /** Returns whether this quarter starts inside the group's charging window. */
    private fun isInGroupWindow(
        quarter: LocalDateTime,
        group: ChargingGroup
    ): Boolean {
        val time = quarter.toLocalTime()
        return !time.isBefore(group.startTime) && time.isBefore(group.endTime)
    }
}
