package com.frankenergie.smartasset.allocation

import com.frankenergie.smartasset.model.ChargingGroup
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Result of assigning energy from one delivery quarter to charging groups.
 *
 * @property allocations energy assigned in this quarter, keyed by group name.
 * @property remainingByGroup demand still not covered after this quarter is assigned.
 */
data class QuarterAllocation(
    val allocations: Map<String, BigDecimal>,
    val remainingByGroup: Map<String, BigDecimal>
) {
    /** Total energy from the quarter that could be assigned to a group. */
    val allocatedEnergy: BigDecimal =
        allocations.values.fold(BigDecimal.ZERO, BigDecimal::add)
}

/**
 * Result of assigning an entire position layout to charging groups.
 *
 * @property allocations planned energy per `(group name, delivery quarter)`.
 * This map is consumed directly by the steering-signal dispatcher.
 * @property remainingByGroup demand not covered by any supplied position.
 * The optimizer uses this as its `uncovered remaining` state when deciding what
 * additional energy is useful to buy.
 */
data class ChargingAllocationPlan(
    val allocations: Map<Pair<String, LocalDateTime>, BigDecimal>,
    val remainingByGroup: Map<String, BigDecimal>
)

/**
 * Stateless policy for mapping market positions to charging-group demand.
 *
 * Alternative policies, such as membership priority, can replace this component
 * without changing procurement or signal dispatch.
 */
interface ChargingAllocationStrategy {

    /**
     * Rebuilds a complete charging plan from the current market positions.
     *
     * Positions are market inventory grouped by delivery quarter; they do not
     * permanently belong to a charging group. This method temporarily maps them
     * to group demand so callers can answer:
     *
     * 1. Which group should receive energy in each quarter?
     * 2. Which group demand is not yet covered by the current position layout?
     *
     * Implementations must not mutate any input collection.
     *
     * @param groups charging groups, including charging windows and power limits.
     * @param remainingNeed actual uncharged demand in MWh, keyed by group name.
     * @param positions owned energy in MWh, keyed by delivery-quarter start.
     * @return the derived allocation plan and demand left uncovered.
     */
    fun allocatePositions(
        groups: List<ChargingGroup>,
        remainingNeed: Map<String, BigDecimal>,
        positions: Map<LocalDateTime, BigDecimal>
    ): ChargingAllocationPlan

    /**
     * Assigns energy available in one quarter against the supplied demand snapshot.
     *
     * Fill-up uses this first as a capacity query (`allocatedEnergy`) and then
     * again after buying to obtain the next `remainingByGroup` snapshot.
     * No persistent charging state is changed.
     *
     * @param groups charging groups, including charging windows and power limits.
     * @param remainingNeed demand still available for assignment before this quarter.
     * @param quarter delivery-quarter start whose energy is being assigned.
     * @param availableEnergy maximum energy in MWh available from this quarter.
     * @return per-group assignment and demand remaining after that assignment.
     */
    fun allocateQuarter(
        groups: List<ChargingGroup>,
        remainingNeed: Map<String, BigDecimal>,
        quarter: LocalDateTime,
        availableEnergy: BigDecimal
    ): QuarterAllocation
}
