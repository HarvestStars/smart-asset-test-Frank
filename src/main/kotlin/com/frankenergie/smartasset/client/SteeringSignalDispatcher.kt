package com.frankenergie.smartasset.client

import com.fasterxml.jackson.databind.ObjectMapper
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
    private val chargingNeedAggregator: ChargingNeedAggregator
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

    fun dispatch(positions: Map<LocalDateTime, BigDecimal>) {
        val newSignals = deriveSignals(positions)
        emitChanged(newSignals)
    }

    /**
     * Quarter-centric proportional allocation.
     *
     * Iterates quarters in time order.  For each quarter, all groups that are
     * active (window covers this quarter, remaining need > 0) compete for the
     * available position.  Their shares are scaled by their desire so that a
     * group with larger maxPower gets a proportionally larger slice of scarce
     * capacity, rather than being starved by a lower-power group that happened
     * to be processed first.
     */
    private fun deriveSignals(
        positions: Map<LocalDateTime, BigDecimal>
    ): Map<Pair<String, LocalDateTime>, SteeringSignal> {

        // Mutable remaining-need register, initialised from group config
        val remaining: MutableMap<String, BigDecimal> = chargingNeedAggregator.groups
            .associate { it.name to it.neededChargeMWh }
            .toMutableMap()

        val signals = mutableMapOf<Pair<String, LocalDateTime>, SteeringSignal>()

        for (quarter in positions.keys.sorted()) {
            val pos = positions[quarter] ?: continue
            if (pos <= BigDecimal.ZERO) continue

            // Groups whose window covers this quarter and still need charge
            val active = chargingNeedAggregator.groups.filter { g ->
                chargingNeedAggregator.isInGroupWindow(quarter, g) &&
                (remaining[g.name] ?: BigDecimal.ZERO) > BigDecimal.ZERO
            }
            if (active.isEmpty()) continue

            // Desire: how much each active group would ideally take from this quarter
            val desires: Map<String, BigDecimal> = active.associate { g ->
                g.name to (remaining[g.name]!!).min(g.maxPowerMW * BigDecimal("0.25"))
            }
            val totalDesired = desires.values.fold(BigDecimal.ZERO, BigDecimal::add)

            // Scale factor < 1 only when position is the scarce resource
            val scaleFactor = if (totalDesired > pos)
                pos.divide(totalDesired, 10, RoundingMode.HALF_UP)
            else BigDecimal.ONE

            for (g in active) {
                val allocation = (desires[g.name]!! * scaleFactor)
                    .setScale(4, RoundingMode.HALF_UP)
                if (allocation <= BigDecimal.ZERO) continue

                signals[g.name to quarter] = SteeringSignal(
                    group             = g.name,
                    deliveryStart     = quarter,
                    deliveryEnd       = quarter.plusMinutes(15),
                    commandedPowerMw  = allocation.divide(BigDecimal("0.25"), 4, RoundingMode.HALF_UP),
                    commandedEnergyMwh = allocation
                )
                remaining[g.name] = (remaining[g.name]!! - allocation).max(BigDecimal.ZERO)
            }
        }
        return signals
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
