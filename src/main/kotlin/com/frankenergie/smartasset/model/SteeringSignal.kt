package com.frankenergie.smartasset.model

import com.fasterxml.jackson.annotation.JsonProperty
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

data class SteeringSignal(
    @JsonProperty("signal_id")              val signalId: String = UUID.randomUUID().toString(),
    @JsonProperty("group")                  val group: String,
    @JsonProperty("delivery_start")         val deliveryStart: LocalDateTime,
    @JsonProperty("delivery_end")           val deliveryEnd: LocalDateTime,
    @JsonProperty("commanded_power_mw")     val commandedPowerMw: BigDecimal,
    @JsonProperty("commanded_energy_mwh")   val commandedEnergyMwh: BigDecimal,
    @JsonProperty("timestamp")              val timestamp: Instant = Instant.now()
)
