package com.kyusun.driveteacher.signal

/**
 * One observation of the car. Pure Kotlin, so the scoring engine and its tests do not depend on Android.
 */
sealed interface Sample {
    /**
     * SystemClock.elapsedRealtimeNanos() clock: the HAL timestamp for [Speed] and [Gear] (gear's first value, which
     * carries the time of its last change, is moved up to when the app subscribed), the moment the callback fired for
     * the rest. Never decreases within one signal. Samples are delivered in arrival order, so across signals tNs can be
     * out of order by how late each arrives: 0.6 s seen on the emulator while AAOS property events came through the
     * main looper (before 11/1 they come on the collector's thread), seconds while it stalls. Sort by tNs within such a
     * window when merging signals.
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

    /** NaviHelper's enforcement cameras ahead (onCameraAlert), nearest first as given; empty = none ahead. */
    data class SpeedCameras(override val tNs: Long, val cameras: List<SpeedCamera>) : Sample

    /**
     * The guided route's next turn (NaviHelper onTBTInfo, the nearest entry): the signal it calls for, [TurnSignal.OFF]
     * for none (straight on, arriving, no route), and the distance to it as the navigation gives it (unit assumed m).
     */
    data class NextTurn(override val tNs: Long, val signal: TurnSignal, val remainM: Float) : Sample
}

enum class GearPosition { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

enum class TurnSignal { OFF, LEFT, RIGHT }

/**
 * One NaviHelper CameraAlert. [remainM] and [limitKph] units are assumed (undocumented); [overSpeed] is the
 * navigation's own verdict. Section-average fields are left out: the simulator does not compute them.
 */
data class SpeedCamera(val id: String, val type: SpeedCameraType, val limitKph: Int, val remainM: Int, val overSpeed: Boolean) {
    /** `id/type/limit/remain/over`; [id] must not contain `,` `/` `;` (the line's separators). */
    fun toCsv() = "$id/$type/$limitKph/$remainM/$overSpeed"
}

/** NaviHelper CameraType's names, so the car's alerts and the simulator's map the same way. */
enum class SpeedCameraType {
    SPEED_CAMERA_FIX, SPEED_CAMERA_MOV, SPEED_CAMERA_SECTION_START, SECTION_SPEED_ALERT, SPEED_CAMERA_SECTION_END,
    SPEED_CAMERA_TRAFFIC_LIGHT, SPEED_CAMERA_REAR, VARIABLE_SPEED_CAMERA, SPEED_CAMERA_SCHOOL_ZONE,
    SPEED_CAMERA_SCHOOL_ZONE_TRAFFIC_LIGHT,
}

/**
 * One recording line, `t_ms,kind,value[,value2]`. tools/carla/compare_signals.py reads this format, and
 * tools/carla/bridge.py sends the same lines without the time on the simulator channel (③).
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
        is Sample.SpeedCameras -> "cameras," + cameras.joinToString(";") { it.toCsv() }
        is Sample.NextTurn -> "nextturn,$signal,$remainM"
    }
    return "${tNs / NANOS_PER_MS},$values"
}

/** A [toCsv] line back as a sample (time to ms precision), or null for a comment or a line it cannot read. */
fun parseSample(line: String): Sample? {
    val fields = line.split(",")
    val ms = fields.first().toLongOrNull() ?: return null
    return sampleOf(ms * NANOS_PER_MS, fields.drop(1))
}

/** A line from the simulator channel (③): a [toCsv] line without the time, stamped [tNs] on receipt. */
fun simSample(line: String, tNs: Long): Sample? = sampleOf(tNs, line.split(","))

/** `kind, values...` -> sample. Null for an unknown kind or a value it cannot read: never a guessed value. */
private fun sampleOf(t: Long, fields: List<String>): Sample? = runCatching {
    val v = fields.drop(1)
    when (fields.firstOrNull()) {
        "speed" -> Sample.Speed(t, v[0].toFloat().also { require(it.isFinite()) }, t + (v.getOrNull(1)?.toLong() ?: 0) * NANOS_PER_MS)
        "gear" -> Sample.Gear(t, GearPosition.valueOf(v[0]))
        "brake" -> Sample.Brake(t, v[0].toBooleanStrict())
        "turn" -> Sample.Turn(t, TurnSignal.valueOf(v[0]))
        "steering" -> Sample.Steering(t, v[0].toFloat())
        "belt" -> Sample.SeatBelt(t, v[0].toBooleanStrict())
        "limit" -> Sample.SpeedLimit(t, v[0].toInt())
        "loc" -> Sample.Location(t, v[0].toDouble(), v[1].toDouble())
        "cameras" -> Sample.SpeedCameras(t, v[0].split(";").filter { it.isNotEmpty() }.map(::cameraOf))
        "nextturn" -> Sample.NextTurn(t, TurnSignal.valueOf(v[0]), v[1].toFloat().also { require(it in 0f..MAX_M) })
        else -> null
    }
}.getOrNull()

private fun cameraOf(csv: String): SpeedCamera {
    val (id, type, limit, remain, over) = csv.split("/").also { require(it.size == 5) { "camera: $csv" } }
    return SpeedCamera(id, SpeedCameraType.valueOf(type), limit.toInt(), remain.toInt(), over.toBooleanStrict())
}

private const val NANOS_PER_MS = 1_000_000L
private const val MAX_M = Float.MAX_VALUE // finite, so a NaN or infinite distance is not read
