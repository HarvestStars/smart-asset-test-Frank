package com.frankenergie.smartasset.model

import java.math.BigDecimal
import java.time.LocalTime

data class ChargingGroup(
    val name: String,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val neededChargeMWh: BigDecimal,
    val maxPowerMW: BigDecimal
)
