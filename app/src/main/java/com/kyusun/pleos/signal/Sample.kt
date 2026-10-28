package com.kyusun.pleos.signal

/**
 * One observation of the car. Pure Kotlin, so the scoring engine and its tests do not depend on Android.
 */
sealed interface Sample {
    /**
     * SystemClock.elapsedRealtimeNanos() clock: the HAL timestamp for [Speed] and [Gear] (gear's first value, which
     * carries the time of its last change, is moved up to when the app subscribed), the moment the callback fired for
     * the rest. Never decreases within one signal. Samples are delivered in arrival order, and AAOS property events
     * come through the main looper, so across signals tNs can be out of order by that delay: 0.6 s seen on the
     * emulator, seconds while it stalls. Sort by tNs within such a window when merging signals.
     */
    val tNs: Long

    /** [receivedNs]: when the app's callback got it, same clock. Unlike the HAL time it shows the app stalling. */
    data class Speed(override val tNs: Long, val mps: Float, val receivedNs: Long) : Sample

    data class Gear(override val tNs: Long, val position: GearPosition) : Sample

    /** Pressed or not: the SDK gives no pedal travel. */
    data class Brake(override val tNs: Long, val pressed: Boolean) : Sample

    data class Turn(override val tNs: Long, val signal: TurnSignal) : Sample

    /** Pleos SDK angle in degrees as reported. On the emulator it is −(Virtual Console value); real-car sign unverified. */
    data class Steering(override val tNs: Long, val deg: Float) : Sample

    /** Driver seat belt. Always false on the emulator. */
    data class SeatBelt(override val tNs: Long, val buckled: Boolean) : Sample

    /** NaviHelper current road speed limit, about 1 Hz. 0 = unknown (always 0 on the emulator); unit assumed km/h. */
    data class SpeedLimit(override val tNs: Long, val kph: Int) : Sample

    data class Location(override val tNs: Long, val lat: Double, val lon: Double) : Sample
}

enum class GearPosition { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

enum class TurnSignal { OFF, LEFT, RIGHT }

/**
 * One recording line, `t_ms,kind,value[,value2]`. tools/carla/compare_signals.py reads this format.
 * Speed's value2 is the receipt delay, receivedNs − tNs in ms.
 */
fun Sample.toCsv(): String {
    val values = when (this) {
        is Sample.Speed -> "speed,$mps,${(receivedNs - tNs) / NANOS_PER_MS}"
        is Sample.Gear -> "gear,$position"
        is Sample.Brake -> "brake,$pressed"
        is Sample.Turn -> "turn,$signal"
        is Sample.Steering -> "steering,$deg"
        is Sample.SeatBelt -> "belt,$buckled"
        is Sample.SpeedLimit -> "limit,$kph"
        is Sample.Location -> "loc,$lat,$lon"
    }
    return "${tNs / NANOS_PER_MS},$values"
}

private const val NANOS_PER_MS = 1_000_000L
