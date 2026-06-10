package com.frankenergie.smartasset.allocation

import com.frankenergie.smartasset.model.ChargingGroup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class PowerWeightedAllocationStrategyTests {

    private val strategy = PowerWeightedAllocationStrategy()
    private val date = LocalDate.of(2025, 1, 1)

    @Test
    fun `allocates scarce quarter energy proportionally to group power demand`() {
        val groups = listOf(
            group("standard", "00:00", "02:00", need = "2.0", power = "2.0"),
            group("fast", "00:00", "02:00", need = "2.0", power = "6.0")
        )

        val result = strategy.allocateQuarter(
            groups = groups,
            remainingNeed = mapOf(
                "standard" to BigDecimal("2.0"),
                "fast" to BigDecimal("2.0")
            ),
            quarter = quarter("00:00"),
            availableEnergy = BigDecimal("1.0")
        )

        assertDecimalEquals("0.2500", result.allocations.getValue("standard"))
        assertDecimalEquals("0.7500", result.allocations.getValue("fast"))
        assertDecimalEquals("1.7500", result.remainingByGroup.getValue("standard"))
        assertDecimalEquals("1.2500", result.remainingByGroup.getValue("fast"))
    }

    @Test
    fun `existing positions are reflected in uncovered remaining demand`() {
        val groups = listOf(
            group("flexible", "00:00", "02:00", need = "1.0", power = "4.0"),
            group("early-only", "00:00", "01:00", need = "1.0", power = "4.0")
        )

        val plan = strategy.allocatePositions(
            groups = groups,
            remainingNeed = mapOf(
                "flexible" to BigDecimal("1.0"),
                "early-only" to BigDecimal("1.0")
            ),
            positions = mapOf(quarter("01:00") to BigDecimal("1.0"))
        )

        assertDecimalEquals("0.0000", plan.remainingByGroup.getValue("flexible"))
        assertDecimalEquals("1.0", plan.remainingByGroup.getValue("early-only"))

        val earlyCapacity = strategy.allocateQuarter(
            groups = groups,
            remainingNeed = plan.remainingByGroup,
            quarter = quarter("00:00"),
            availableEnergy = BigDecimal("2.0")
        )

        assertEquals(setOf("early-only"), earlyCapacity.allocations.keys)
        assertDecimalEquals("1.0000", earlyCapacity.allocatedEnergy)
    }

    @Test
    fun `allocation never exceeds available energy after rounding`() {
        val groups = listOf(
            group("a", "00:00", "01:00", need = "1.0", power = "1.0"),
            group("b", "00:00", "01:00", need = "1.0", power = "1.0"),
            group("c", "00:00", "01:00", need = "1.0", power = "1.0")
        )

        val result = strategy.allocateQuarter(
            groups = groups,
            remainingNeed = groups.associate { it.name to it.neededChargeMWh },
            quarter = quarter("00:00"),
            availableEnergy = BigDecimal("0.0001")
        )

        assertDecimalEquals("0.0001", result.allocatedEnergy)
    }

    private fun group(
        name: String,
        start: String,
        end: String,
        need: String,
        power: String
    ) = ChargingGroup(
        name = name,
        startTime = LocalTime.parse(start),
        endTime = LocalTime.parse(end),
        neededChargeMWh = BigDecimal(need),
        maxPowerMW = BigDecimal(power)
    )

    private fun quarter(time: String): LocalDateTime =
        LocalDateTime.of(date, LocalTime.parse(time))

    private fun assertDecimalEquals(expected: String, actual: BigDecimal) {
        assertEquals(0, BigDecimal(expected).compareTo(actual))
    }
}
