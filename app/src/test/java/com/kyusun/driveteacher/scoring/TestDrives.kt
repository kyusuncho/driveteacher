package com.kyusun.driveteacher.scoring

import com.kyusun.driveteacher.signal.GearPosition
import com.kyusun.driveteacher.signal.Sample
import com.kyusun.driveteacher.signal.TurnSignal
import com.kyusun.driveteacher.signal.parseSample

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
internal fun turn(signal: TurnSignal, atMs: Long) = listOf(Sample.Turn(atMs * NS_PER_MS, signal))
internal fun nextTurn(signal: TurnSignal, remainM: Float, atMs: Long) =
    listOf(Sample.NextTurn(atMs * NS_PER_MS, signal, remainM))

/** In time order; at the same time, in the order given. */
internal fun merge(vararg parts: List<Sample>): List<Sample> = parts.toList().flatten().sortedBy { it.tNs }

/**
 * App recordings (SignalRecordTest) of CARLA drives; limit and belt are unreadable on the emulator itself.
 * - carla-drive-1028.csv: the 8th stage-1 run (10/28 18:50), CARLA autopilot, 98 s.
 * - carla-careful-drive-1028.csv: tools/carla/driver.py (10/28 23:55), 120 s simulated.
 * - carla-careful-drive-1029.csv: the same driver (10/29 05:40), 100 s simulated, filmed with --record.
 * - carla-pull-out-kept-on-1029.csv: the same driver signalling as it pulls out (10/29 16:58), Gangnam map, 60 s;
 *   limit and belt came over the simulator channel (③). The signal stayed on through a junction (driver bug).
 * - carla-careful-drive-gangnam-1029.csv: the same, fixed (10/29 17:09).
 * - carla-turns-gangnam-1030.csv: the same turning left, then right (--turns left,right), 120 s; the navigation's next
 *   turn came over ③ too.
 * - carla-turns-no-signal-gangnam-1030.csv: the same route forgetting the turn signal (--no-turn-signal).
 * - carla-turns-stalls-gangnam-1101.csv: the same route signalled, after the bridge re-sends guidance through console
 *   stalls and the driver's launch throttle (11/1, check_signals.sh PASS with 38% console outages left out).
 */
internal fun recording(name: String): List<Sample> =
    checkNotNull(object {}.javaClass.getResource("/$name")).readText().lines().mapNotNull(::parseSample)
