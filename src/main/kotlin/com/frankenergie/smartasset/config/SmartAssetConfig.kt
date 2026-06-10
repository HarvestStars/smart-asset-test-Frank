package com.frankenergie.smartasset.config

import com.frankenergie.smartasset.model.ChargingGroup
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalTime

@Component
@ConfigurationProperties(prefix = "smart-asset")
class SmartAssetConfig {

    /** ISO-8601 date string, e.g. "2025-01-01". Defines which calendar day the 96 quarters belong to. */
    var tradingDate: String = "2025-01-01"

    val chargingGroups: MutableList<ChargingGroupConfig> = mutableListOf()

    data class ChargingGroupConfig(
        var name: String = "",
        var startTime: String = "00:00",
        var endTime: String = "23:59",
        var neededChargeMwh: BigDecimal = BigDecimal.ZERO,
        var maxPowerMw: BigDecimal = BigDecimal.ZERO
    ) {
        fun toChargingGroup() = ChargingGroup(
            name = name,
            startTime = LocalTime.parse(startTime),
            endTime = LocalTime.parse(endTime),
            neededChargeMWh = neededChargeMwh,
            maxPowerMW = maxPowerMw
        )
    }
}
