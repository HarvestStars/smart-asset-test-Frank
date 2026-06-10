package com.frankenergie.smartasset.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.frankenergie.smartasset.allocation.ChargingAllocationStrategy
import com.frankenergie.smartasset.model.SteeringSignal
import com.frankenergie.smartasset.service.ChargingNeedAggregator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDateTime

/**
 * Client for EV-group steering signals.
 *
 * Allocation strategy — quarter-centric, proportional by maxPower:
 *
 *   For each quarter Q (in time order), find all groups whose window covers Q
 *   and still have remaining need.  Each such group states a "desire":
 *
 *     desire_g = min(remaining_g, maxPower_g × 0.25 h)
 *
 *   If the total desire fits within position[Q], every group gets its desire.
 *   If position[Q] is scarce (partially-filled quarter), each group's allocation
 *   is scaled proportionally:
 *
 *     allocation_g = desire_g × (position[Q] / totalDesired)
 *
 *   This prevents the "greedy-order" problem where a group processed first could
 *   consume all available position in a quarter, starving groups with a larger
 *   maxPower that happen to be processed later.
 *
 * Cancellation: if a (group, quarter) pair was previously commanded but no
 * longer has an allocation, a zero-power signal is emitted so the charging
 * group knows to stop consuming in that slot.
 *
 * Every emitted signal is appended as a JSON line to [signalsFile].
 * deliveryEnd is computed as deliveryStart + 15 min (fixed quarter duration).
 */
@Component
class SteeringSignalDispatcher(
    private val objectMapper: ObjectMapper,
    private val chargingNeedAggregator: ChargingNeedAggregator,
    private val allocationStrategy: ChargingAllocationStrategy
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val signalsFile = File("steering_signals.jsonl")

    // Incremental tracking: (group, deliveryStart) → last emitted SteeringSignal
    private val lastSignals = HashMap<Pair<String, LocalDateTime>, SteeringSignal>()

    /**
     * Returns the last commanded energy (MWh) for every (group, quarter) pair
     * whose quarter falls in [quarters].
     *
     * Used by the optimizer when positions expire: the commanded energy is the
     * best available proxy for how much each group actually charged in that slot
     * (mock assumption: EV groups comply with the last received signal).
     */
    fun getLastSignalsForQuarters(quarters: Set<LocalDateTime>): Map<Pair<String, LocalDateTime>, BigDecimal> =
        lastSignals
            .filterKeys { (_, quarter) -> quarter in quarters }
            .mapValues { (_, signal) -> signal.commandedEnergyMwh }

    /**
     * Converts the latest position layout into group commands and emits only
     * signals whose assigned energy changed since the previous dispatch.
     *
     * @param positions currently owned energy in MWh per delivery quarter.
     */
    fun dispatch(positions: Map<LocalDateTime, BigDecimal>) {
        val newSignals = deriveSignals(positions)
        emitChanged(newSignals)
    }

    /**
     * Uses the configured allocation strategy to turn market-level positions
     * into `(group, quarter)` energy assignments, then converts those assignments
     * to external steering-signal objects.
     */
    private fun deriveSignals(
        positions: Map<LocalDateTime, BigDecimal>
    ): Map<Pair<String, LocalDateTime>, SteeringSignal> {
        val plan = allocationStrategy.allocatePositions(
            groups = chargingNeedAggregator.groups,
            remainingNeed = chargingNeedAggregator.getGroupRemaining(),
            positions = positions
        )

        return plan.allocations.mapValues { (key, allocation) ->
            val (groupName, quarter) = key
            SteeringSignal(
                group = groupName,
                deliveryStart = quarter,
                deliveryEnd = quarter.plusMinutes(15),
                commandedPowerMw = allocation.divide(
                    BigDecimal("0.25"),
                    4,
                    RoundingMode.HALF_UP
                ),
                commandedEnergyMwh = allocation
            )
        }
    }

    private fun emitChanged(newSignals: Map<Pair<String, LocalDateTime>, SteeringSignal>) {
        // Emit new or changed signals
        for ((key, signal) in newSignals) {
            val prev = lastSignals[key]
            if (prev == null || prev.commandedEnergyMwh.compareTo(signal.commandedEnergyMwh) != 0) {
                writeSignal(signal)
                lastSignals[key] = signal
            }
        }

        // Emit zero-power cancellations for pairs that were previously commanded
        // but no longer appear in the current allocation (position sold off, etc.)
        for ((key, prev) in lastSignals.entries.toList()) {
            if (key !in newSignals && prev.commandedEnergyMwh.compareTo(BigDecimal.ZERO) != 0) {
                val cancellation = prev.copy(
                    commandedPowerMw   = BigDecimal.ZERO,
                    commandedEnergyMwh = BigDecimal.ZERO,
                    timestamp          = Instant.now()
                )
                writeSignal(cancellation)
                lastSignals[key] = cancellation
            }
        }
    }

    private fun writeSignal(signal: SteeringSignal) {
        signalsFile.appendText(objectMapper.writeValueAsString(signal) + "\n")
        log.info(
            "[SteeringSignal] group={} quarter={} power={}MW energy={}MWh",
            signal.group, signal.deliveryStart,
            signal.commandedPowerMw, signal.commandedEnergyMwh
        )
    }
}
