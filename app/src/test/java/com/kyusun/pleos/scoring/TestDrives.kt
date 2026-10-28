package com.kyusun.pleos.scoring

import com.kyusun.pleos.signal.GearPosition
import com.kyusun.pleos.signal.Sample

/** Building blocks for test drives. Times are ms from 0; [merge] puts the pieces in time order. */
internal const val NS_PER_MS = 1_000_000L

/** Speeds every 100 ms from [startMs]. */
internal fun speeds(kph: List<Float>, startMs: Long = 0): List<Sample.Speed> = kph.mapIndexed { i, v ->
    val t = (startMs + i * 100L) * NS_PER_MS
    Sample.Speed(t, v / 3.6f, t)
}

/** From [from] to [to] km/h at [perSecond] km/h per second, in 100 ms steps, then 1 s at [to]. */
internal fun ramp(from: Float, to: Float, perSecond: Float): List<Float> {
    val step = perSecond / 10 * if (to >= from) 1 else -1
    val n = ((to - from) / step).toInt()
    return List(n) { from + it * step } + List(10) { to }
}

internal fun belt(buckled: Boolean, atMs: Long) = listOf(Sample.SeatBelt(atMs * NS_PER_MS, buckled))
internal fun buckled() = belt(true, 0)
internal fun limit(kph: Int, atMs: Long = 0) = listOf(Sample.SpeedLimit(atMs * NS_PER_MS, kph))
internal fun gear(position: GearPosition, atMs: Long) = listOf(Sample.Gear(atMs * NS_PER_MS, position))
internal fun brake(pressed: Boolean, atMs: Long) = listOf(Sample.Brake(atMs * NS_PER_MS, pressed))

/** In time order; at the same time, in the order given. */
internal fun merge(vararg parts: List<Sample>): List<Sample> = parts.toList().flatten().sortedBy { it.tNs }

/** The 8th stage-1 run (10/28 18:50): CARLA autopilot, 98 s; limit and belt unreadable on the emulator. */
internal fun recordedCarlaDrive(): List<Sample> =
    checkNotNull(object {}.javaClass.getResource("/carla-drive-1028.csv")).readText().lines().mapNotNull(::parse)

/** SignalRecordTest's CSV, the lines scoring reads. */
private fun parse(line: String): Sample? {
    val f = line.split(",")
    if (f.size < 3 || line.startsWith("#")) return null
    val t = f[0].toLong() * NS_PER_MS
    return when (f[1]) {
        "speed" -> Sample.Speed(t, f[2].toFloat(), t + (f.getOrNull(3)?.toLong() ?: 0) * NS_PER_MS)
        "gear" -> Sample.Gear(t, GearPosition.valueOf(f[2]))
        "brake" -> Sample.Brake(t, f[2].toBoolean())
        "limit" -> Sample.SpeedLimit(t, f[2].toInt())
        "belt" -> Sample.SeatBelt(t, f[2].toBoolean())
        else -> null
    }
}
