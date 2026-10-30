package com.kyusun.pleos.scoring

import com.kyusun.pleos.signal.GearPosition
import com.kyusun.pleos.signal.Sample
import com.kyusun.pleos.signal.TurnSignal
import kotlin.math.abs
import kotlin.math.max

/**
 * Signalling for the turns the navigation guides (별표26 30~33): left and right turns and U-turns, as
 * [Sample.NextTurn] gives them. Lane changes are not judged: nothing in the car knows them (10/29 decision).
 *
 * A turn sits at [Approach.atM], in metres driven forward (not in R): each guidance update puts it at the distance
 * driven by then plus the distance the navigation gives, and in between the car's own speed carries the car on. Its
 * signal must be on from [SIGNAL_FROM_M] before it (도로교통법 시행령 별표2: 30 m before the junction) to the turn and
 * off within [OFF_WITHIN_M] past it. Never on in that stretch is 30 (at the turn); on only after the stretch began is
 * 31 (as it goes on), unless the navigation first gave the turn inside it (a new route: the driver could not know); on and then off, or the other way, before the turn is 32 (as it goes off); still on
 * [OFF_WITHIN_M] past the turn is 33, unless the car is in the stretch of a next turn the same way by then. With the
 * signal not read when the stretch begins, that turn is not judged.
 *
 * Like [RoadTest.PullOut], signal changes and guidance (callback times) wait for the speeds (HAL times) up to them and
 * are taken in time order, with the distance driven at their time. Guidance within [NEAR_M] of the turn ahead (or the
 * one just passed) the same way is that turn; the navigation moving on to another one with this one under [NEAR_M]
 * ahead means the car reached it, farther ahead that the route changed (not judged).
 * ponytail: the navigation puts a turn about the middle of the junction, so it is taken to end within 20 m of it
 * (33 at 30 m); the steering straightening out could tell. Calibrate both on real drives. Two turns the same way
 * within [NEAR_M] of each other count as one (the second not judged): telling them apart would also split one turn
 * when the speed and the navigation disagree by that much (the simulator stalling).
 */
data class Turns(
    private val signal: TurnSignal? = null, // as of [atNs]; null: not read yet
    private val metres: Float = 0f, // driven forward, as of [atNs]
    private val atNs: Long = 0,
    private val last: Sample.Speed? = null,
    private val pending: List<Sample> = emptyList(), // signal changes and guidance after [last]
    private val ahead: Approach? = null,
    private val passed: Passed? = null,
) {
    /** Takes a turn signal change or guidance; they count once the speeds up to their time have come. */
    fun take(sample: Sample): Turns = copy(pending = pending + sample)

    /** Whether [sample] is still waiting for the speeds up to it. */
    fun holds(sample: Sample): Boolean = sample in pending

    fun undo(sample: Sample): Turns = copy(pending = pending - sample)

    /** Whether a turn ahead, in its stretch, or just passed with the signal still on calls for [signal]. */
    fun callsFor(signal: TurnSignal): Boolean =
        callsAhead(signal) || passed?.let { it.watch && it.signal == signal } == true

    private fun callsAhead(signal: TurnSignal) = ahead?.let { it.inStretch && it.signal == signal } == true

    /** These turns after the speed [s] in [gear], with the penalties since the last speed. */
    fun drive(s: Sample.Speed, gear: GearPosition): Pair<Turns, List<Penalty>> {
        val from = last ?: s
        val forward = gear != GearPosition.REVERSE
        val step = if (forward) (max(from.mps, 0f) + max(s.mps, 0f)) / 2 * (s.tNs - from.tNs) / NS_PER_S else 0f
        val toM = metres + step
        fun metresAt(t: Long) = if (s.tNs <= from.tNs) {
            toM
        } else {
            metres + step * ((t - from.tNs).toFloat() / (s.tNs - from.tNs)).coerceIn(0f, 1f)
        }
        var state = copy(pending = pending.filter { it.tNs > s.tNs }, last = s, atNs = max(atNs, from.tNs))
        val out = mutableListOf<Penalty>()
        for (e in pending.filter { it.tNs <= s.tNs }.sortedBy { it.tNs }) {
            val t = max(e.tNs, state.atNs)
            state = state.advance(metresAt(t), t, out).apply(e, out)
        }
        return state.advance(toM, s.tNs, out) to out
    }

    /** Drives on to [toM] at [toNs], crossing the marks on the way in order. */
    private fun advance(toM: Float, toNs: Long, out: MutableList<Penalty>): Turns {
        var state = this
        while (true) {
            val (mark, kind) = state.nextMark() ?: break
            if (mark > toM) break
            val t = if (mark <= state.metres || toM <= state.metres) {
                state.atNs
            } else {
                state.atNs + ((mark - state.metres) / (toM - state.metres) * (toNs - state.atNs)).toLong()
            }
            state = state.copy(metres = max(mark, state.metres), atNs = t).cross(kind, out)
        }
        return state.copy(metres = max(toM, state.metres), atNs = toNs)
    }

    private fun nextMark(): Pair<Float, Mark>? = listOfNotNull(
        ahead?.let { if (it.inStretch) it.atM to Mark.TURN else it.atM - SIGNAL_FROM_M to Mark.STRETCH },
        passed?.takeIf { it.watch }?.let { it.atM + OFF_WITHIN_M to Mark.OFF_BY },
    ).minByOrNull { it.first }

    private fun cross(kind: Mark, out: MutableList<Penalty>): Turns = when (kind) {
        Mark.STRETCH -> copy(
            ahead = ahead?.let { it.copy(inStretch = true, judged = signal != null, signalled = signal == it.signal) },
        )
        Mark.TURN -> reach(out)
        Mark.OFF_BY -> {
            val keptOn = passed?.let { signal == it.signal && !callsAhead(it.signal) } == true
            if (keptOn) out += Penalty.Deduction(atNs, RoadItem.TURN_SIGNAL_NOT_OFF)
            copy(passed = passed?.copy(watch = false))
        }
    }

    /** The car at the turn ahead, at [atNs]. */
    private fun reach(out: MutableList<Penalty>): Turns {
        val a = ahead ?: return this
        if (a.judged && !a.signalled) out += Penalty.Deduction(atNs, RoadItem.TURN_NO_SIGNAL)
        return copy(ahead = null, passed = Passed(a.signal, a.atM, watch = a.judged && signal == a.signal))
    }

    private fun apply(e: Sample, out: MutableList<Penalty>): Turns = when (e) {
        is Sample.Turn -> onSignal(e, out)
        is Sample.NextTurn -> onGuidance(e, out)
        else -> this
    }

    private fun onSignal(e: Sample.Turn, out: MutableList<Penalty>): Turns {
        val a = ahead?.takeIf { it.inStretch && it.judged }
        val next = when {
            a == null -> ahead
            e.signal == a.signal && !a.signalled -> {
                if (a.heardBefore) out += Penalty.Deduction(e.tNs, RoadItem.TURN_SIGNAL_LATE)
                a.copy(signalled = true)
            }
            e.signal != a.signal && signal == a.signal && !a.dropped -> {
                out += Penalty.Deduction(e.tNs, RoadItem.TURN_SIGNAL_NOT_KEPT)
                a.copy(dropped = true)
            }
            else -> a
        }
        val stillOn = passed?.let { it.copy(watch = it.watch && e.signal == it.signal) }
        return copy(signal = e.signal, ahead = next, passed = stillOn)
    }

    private fun onGuidance(e: Sample.NextTurn, out: MutableList<Penalty>): Turns {
        val at = metres + e.remainM
        fun same(signal: TurnSignal, atM: Float) = e.signal == signal && abs(at - atM) <= NEAR_M
        val a = ahead
        if (a != null && same(a.signal, a.atM)) return copy(ahead = a.copy(atM = at))
        if (a == null && passed != null && same(passed.signal, passed.atM)) return copy(passed = passed.copy(atM = at))
        val movedOn = when {
            a == null -> this
            a.atM - metres <= NEAR_M -> reach(out)
            else -> copy(ahead = null) // the route changed
        }
        val heardBefore = at - SIGNAL_FROM_M > metres
        return if (e.signal == TurnSignal.OFF) movedOn else movedOn.copy(ahead = Approach(e.signal, at, heardBefore))
    }

    /**
     * The next turn, at [atM]; [heardBefore]: the navigation gave it before its stretch. From [SIGNAL_FROM_M] before it
     * ([inStretch]): [judged] the signal was read then, [signalled] its signal on at some time since, [dropped] turned
     * off (or the other way) after being on.
     */
    data class Approach(
        val signal: TurnSignal,
        val atM: Float,
        val heardBefore: Boolean,
        val inStretch: Boolean = false,
        val judged: Boolean = false,
        val signalled: Boolean = false,
        val dropped: Boolean = false,
    )

    /** The last turn reached, at [atM]; [watch]: its signal still on, to be off within [OFF_WITHIN_M] past it. */
    data class Passed(val signal: TurnSignal, val atM: Float, val watch: Boolean)

    private enum class Mark { STRETCH, TURN, OFF_BY }

    private companion object {
        const val SIGNAL_FROM_M = 30f
        const val OFF_WITHIN_M = 30f
        const val NEAR_M = 30f
    }
}
