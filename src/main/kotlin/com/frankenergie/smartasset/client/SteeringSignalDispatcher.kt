package com.frankenergie.smartasset.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.frankenergie.smartasset.model.QuarterBestLevel
import com.frankenergie.smartasset.model.SteeringSignal
import com.frankenergie.smartasset.service.ChargingNeedAggregator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

/**
 * Framework client for EV-group steering signals (Requirement 4).
 *
 * After each position update, derives per-group charging commands from the
 * current energy inventory and sends only the signals that changed since the
 * last dispatch (incremental / delta update).
 *
 * Allocation strategy (basic – greedy cheapest quarter first per group):
 *   For each group, iterate over quarters in its window that have available
 *   position, sorted by best-ask price ascending.  Assign up to
 *   min(remaining need, maxPower × 0.25 h, available position) per quarter.
 *
 * Every emitted signal is appended as a JSON line to [signalsFile].
 *
 * TODO: refine allocation logic (fairness, priority, partial-fill handling).
 */
@Component
class SteeringSignalDispatcher(
    private val objectMapper: ObjectMapper,
    private val chargingNeedAggregator: ChargingNeedAggregator
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val signalsFile = File("steering_signals.jsonl")

    // Incremental tracking: (group, deliveryStart) → last commanded energy MWh
    private val lastSignals = HashMap<Pair<String, LocalDateTime>, BigDecimal>()

    fun dispatch(
        positions: Map<LocalDateTime, BigDecimal>,
        overview: Map<LocalDateTime, QuarterBestLevel>
    ) {
        val newSignals = deriveSignals(positions, overview)
        emitChanged(newSignals)
    }

    private fun deriveSignals(
        positions: Map<LocalDateTime, BigDecimal>,
        overview: Map<LocalDateTime, QuarterBestLevel>
    ): Map<Pair<String, LocalDateTime>, SteeringSignal> {

        val signals = mutableMapOf<Pair<String, LocalDateTime>, SteeringSignal>()

        for (group in chargingNeedAggregator.groups) {
            var remaining: BigDecimal = group.neededChargeMWh
            val maxPerQuarter: BigDecimal = group.maxPowerMW * BigDecimal("0.25")

            // Quarters in this group's window with available energy, cheapest first
            val quartersInWindow = positions.entries
                .filter { (q, qty) ->
                    qty > BigDecimal.ZERO && chargingNeedAggregator.isInGroupWindow(q, group)
                }
                .sortedBy { (q, _) -> overview[q]?.bestAskPrice ?: BigDecimal("999999") }

            for ((quarter, pos) in quartersInWindow) {
                if (remaining <= BigDecimal.ZERO) break
                val quarterInfo = overview[quarter] ?: continue
                val allocationMWh = remaining.min(maxPerQuarter).min(pos)
                val powerMW = allocationMWh.divide(BigDecimal("0.25"), 4, RoundingMode.HALF_UP)

                signals[group.name to quarter] = SteeringSignal(
                    group = group.name,
                    deliveryStart = quarter,
                    deliveryEnd = quarterInfo.deliveryEndTime,
                    commandedPowerMw = powerMW,
                    commandedEnergyMwh = allocationMWh
                )
                remaining -= allocationMWh
            }
        }
        return signals
    }

    private fun emitChanged(newSignals: Map<Pair<String, LocalDateTime>, SteeringSignal>) {
        for ((key, signal) in newSignals) {
            val prev = lastSignals[key]
            if (prev == null || prev.compareTo(signal.commandedEnergyMwh) != 0) {
                signalsFile.appendText(objectMapper.writeValueAsString(signal) + "\n")
                lastSignals[key] = signal.commandedEnergyMwh
                log.info(
                    "[SteeringSignal] group={} quarter={} power={}MW energy={}MWh",
                    signal.group, signal.deliveryStart,
                    signal.commandedPowerMw, signal.commandedEnergyMwh
                )
            }
        }
    }
}
