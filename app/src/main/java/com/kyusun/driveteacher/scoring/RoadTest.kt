package com.kyusun.driveteacher.scoring

import com.kyusun.driveteacher.signal.GearPosition
import com.kyusun.driveteacher.signal.Sample
import com.kyusun.driveteacher.signal.TurnSignal
import kotlin.math.max

/**
 * Road test items (도로교통법 시행규칙 별표26, 2026. 2. 24.) judged from the car's own signals. [number] is the
 * item's number in the annex, [label] its name there. PLAN 5절 lists all 46 and how each is judged.
 */
enum class RoadItem(val number: Int, val label: String, val points: Int) {
    /** Automatic: stopped 10 s or more without the lever in neutral. */
    GEAR_NOT_NEUTRAL(4, "정지 중 기어 미중립", 5),

    /** A hard acceleration while pulling away. On an EV the annex means the pedal-misuse safety brake, not readable. */
    HARD_START(9, "급조작ㆍ급출발", 7),

    /** Pulling out from park without the left signal (see [RoadTest.PullOut]). */
    START_NO_SIGNAL(11, "신호 안함", 5),

    /** Pulling out from park, the left signal turned off before the car is in the lane. */
    START_SIGNAL_OFF_EARLY(12, "신호 중지", 5),

    /** Pulling out from park, the left signal still on once the car is in the lane. */
    START_SIGNAL_KEPT_ON(13, "신호 계속", 5),

    /** A turn the navigation guides, its signal never on from 30 m before it to it (see [Turns]). */
    TURN_NO_SIGNAL(30, "진로변경 신호 불이행", 7),

    /** The turn's signal on only after 30 m before it. */
    TURN_SIGNAL_LATE(31, "진로변경 30미터 전 미신호", 7),

    /** The turn's signal on and then off before the turn. */
    TURN_SIGNAL_NOT_KEPT(32, "진로변경 신호 미유지", 7),

    /** The turn's signal still on 30 m past the turn. */
    TURN_SIGNAL_NOT_OFF(33, "진로변경 신호 미중지", 7),

    /** Automatic: the lever put in neutral before the car stopped. */
    NEUTRAL_BEFORE_STOP(18, "엔진 브레이크 사용 미숙", 5),

    /** Automatic: standing without the brake pressed. */
    NO_BRAKE_WHEN_STOPPED(20, "정지 때 미제동", 5),

    /** The annex excuses braking to avoid danger; nothing tells that apart, so every hard brake counts. */
    HARD_BRAKE(21, "급브레이크 사용", 7),
}

/** Disqualifications (별표26 2.나) judged from the car's own signals; [number] is the clause. */
enum class Disqualification(val number: Int, val label: String) {
    SPEEDING(8, "제한속도 시속 10km 초과"),

    /** Judged only once the belt has read buckled (see [DrivingEvent.BeltUnreadable]). */
    NO_SEAT_BELT(11, "좌석안전띠 미착용"),
}

sealed interface Penalty {
    val tNs: Long

    data class Deduction(override val tNs: Long, val item: RoadItem) : Penalty

    data class Disqualified(override val tNs: Long, val reason: Disqualification) : Penalty
}

/**
 * Scores a road test from the [Sample] stream: 100 points, passed at 70 or more without a disqualification. Immutable
 * like the [EventDetector] it runs inside; a disqualification ends the test, so later samples change nothing.
 * Items can repeat (별표26 비고 다): each stop and each hard brake is scored on its own, a start at most once.
 *
 * A stop starts under 0.5 km/h and ends only at 3 km/h, so a reading flickering near zero stays one stop. Gear and
 * speed carry HAL times but can arrive up to ~0.6 s out of order (see [Sample.tNs]), so a shift is judged against
 * the speed at its own time. Waiting in D right after leaving P (the start of the test) is not a stop out of neutral.
 * Moving off after the car was in P is pulling out from the road edge, which needs the left signal ([PullOut]). Turns
 * the navigation guides need theirs ([Turns]).
 */
data class RoadTest(
    val penalties: List<Penalty> = emptyList(),
    private val detector: EventDetector = EventDetector(Thresholds(speedingMarginKph = DISQUALIFYING_MARGIN_KPH)),
    private val gear: GearPosition = GearPosition.UNKNOWN,
    private val braking: Boolean? = null, // not read yet
    private val stop: Stop? = null,
    private val neutralAtNs: Long? = null, // a shift to N not yet judged: the first speed from then on decides
    private val movedOffNs: Long? = null, // when the car last pulled away from a stop
    private val startScored: Boolean = false,
    private val turn: TurnSignal? = null, // not read yet
    private val turnChange: TurnChange? = null, // the last change of [turn], while it can still be undone
    private val pullOut: PullOut? = null, // from the car's last time in P until 11~13 are judged
    private val turns: Turns = Turns(),
) {
    val score: Int get() = FULL_MARKS - penalties.sumOf { if (it is Penalty.Deduction) it.item.points else 0 }
    val disqualified: Boolean get() = penalties.any { it is Penalty.Disqualified }
    val passed: Boolean get() = !disqualified && score >= PASS_MARK

    fun next(sample: Sample): Pair<RoadTest, List<Penalty>> {
        if (disqualified) return this to emptyList()
        val (nextDetector, events) = detector.next(sample)
        val (observed, own) = copy(detector = nextDetector).observe(sample)
        val (judged, fromEvents) = observed.judge(events)
        val new = own + fromEvents
        return judged.copy(penalties = penalties + new) to new
    }

    private fun observe(sample: Sample): Pair<RoadTest, List<Penalty>> = when (sample) {
        is Sample.Speed -> onSpeed(sample)
        is Sample.Gear -> copy(
            gear = sample.position,
            stop = stop?.copy(shifted = stop.shifted || outOfGear(sample.position)),
            neutralAtNs = sample.tNs.takeIf { sample.position == GearPosition.NEUTRAL && gear != GearPosition.NEUTRAL }
                ?: neutralAtNs,
            pullOut = if (sample.position == GearPosition.PARK) PullOut(turn) else pullOut,
        ) to emptyList()
        is Sample.Brake -> copy(
            braking = sample.pressed,
            stop = stop?.copy(brakeOffNs = if (sample.pressed) null else stop.brakeOffNs ?: sample.tNs),
        ) to emptyList()
        is Sample.Turn -> onTurn(sample) to emptyList()
        is Sample.NextTurn -> copy(turns = turns.take(sample)) to emptyList()
        is Sample.Steering, is Sample.SeatBelt, is Sample.SpeedLimit, is Sample.Location, is Sample.SpeedCameras ->
            this to emptyList()
    }

    /**
     * A change undone within [BLIP_NS] was never made, if no check has taken it yet (they take it once the speeds up
     * to its time have come, which arrive later than the signal): a glitch, or on the emulator the Virtual Console
     * resetting the signal to off for a moment.
     */
    private fun onTurn(t: Sample.Turn): RoadTest {
        val undone = turnChange?.change?.takeIf {
            t.tNs - it.tNs < BLIP_NS && t.signal == turnChange.before && turns.holds(it)
        }
        return if (undone != null) {
            copy(turn = t.signal, turnChange = null, pullOut = pullOut?.undo(undone), turns = turns.undo(undone))
        } else {
            copy(turn = t.signal, turnChange = TurnChange(t, turn), pullOut = pullOut?.signal(t), turns = turns.take(t))
        }
    }

    private fun onSpeed(s: Sample.Speed): Pair<RoadTest, List<Penalty>> {
        val (nextTurns, turning) = turns.drive(s, gear)
        val (next, item) = pullOut?.drive(s, gear) ?: (null to null)
        // The left signal still on at 30 m can be for a left turn just ahead, not the pull-out. ponytail: asked at this
        // speed, not when the car passed 30 m: off by a long gap in the speeds.
        val pulled = item.takeUnless { it == RoadItem.START_SIGNAL_KEPT_ON && nextTurns.callsFor(PULL_OUT_SIGNAL) }
        val (judged, stops) = copy(pullOut = next, turns = nextTurns).onStop(s)
        return judged to stops + deduction(s.tNs, pulled) + turning
    }

    private fun deduction(tNs: Long, item: RoadItem?) = listOfNotNull(item?.let { Penalty.Deduction(tNs, it) })

    private fun onStop(s: Sample.Speed): Pair<RoadTest, List<Penalty>> {
        val shiftedAt = neutralAtNs?.takeIf { s.tNs >= it }
        val early = listOfNotNull(
            shiftedAt?.takeIf { s.kph >= MOVING_KPH }?.let { Penalty.Deduction(it, RoadItem.NEUTRAL_BEFORE_STOP) },
        )
        val judged = if (shiftedAt == null) this else copy(neutralAtNs = null)
        val standing = s.kph < STOPPED_KPH
        if (if (stop == null) !standing else s.kph >= MOVING_KPH) {
            val pulledAway = stop != null
            return judged.copy(
                stop = null,
                movedOffNs = if (pulledAway) s.tNs else movedOffNs,
                startScored = startScored && !pulledAway,
            ) to early
        }
        val current = stop ?: Stop(s.tNs, brakeOffNs = s.tNs.takeIf { braking == false }, shifted = outOfGear(gear))
        val due = listOfNotNull(
            RoadItem.GEAR_NOT_NEUTRAL.takeIf {
                !current.shifted && inGear(gear) && s.tNs - current.sinceNs >= NEUTRAL_WITHIN_NS + SKEW_GRACE_NS
            },
            RoadItem.NO_BRAKE_WHEN_STOPPED.takeIf {
                standing && gear != GearPosition.PARK && gear != GearPosition.UNKNOWN &&
                    current.brakeOffNs?.let { s.tNs - it >= BRAKE_GRACE_NS } == true
            },
        ) - current.scored
        return judged.copy(stop = current.copy(scored = current.scored + due)) to
            early + due.map { Penalty.Deduction(s.tNs, it) }
    }

    private fun judge(events: List<DrivingEvent>): Pair<RoadTest, List<Penalty>> {
        val start = events.firstOrNull { it is DrivingEvent.HardAccel && pullingAway(it.tNs) }
        val penalties = events.mapNotNull {
            when (it) {
                is DrivingEvent.HardAccel -> Penalty.Deduction(it.tNs, RoadItem.HARD_START).takeIf { _ -> it == start }
                is DrivingEvent.HardBrake -> Penalty.Deduction(it.tNs, RoadItem.HARD_BRAKE)
                is DrivingEvent.Speeding -> Penalty.Disqualified(it.tNs, Disqualification.SPEEDING)
                is DrivingEvent.BeltOff -> Penalty.Disqualified(it.tNs, Disqualification.NO_SEAT_BELT)
                is DrivingEvent.BeltUnreadable -> null
            }
        }
        return copy(startScored = startScored || start != null) to penalties
    }

    private fun pullingAway(tNs: Long) = !startScored && movedOffNs?.let { tNs - it <= START_WINDOW_NS } == true

    /** The turn signal's last change to [change] from [before]. */
    data class TurnChange(val change: Sample.Turn, val before: TurnSignal?)

    /**
     * The car standing since [sinceNs]: [brakeOffNs] since when without the brake (null while pressed or unknown),
     * [shifted] once the lever has been in N or P, [scored] the items this stop already cost.
     */
    data class Stop(
        val sinceNs: Long,
        val brakeOffNs: Long?,
        val shifted: Boolean,
        val scored: Set<RoadItem> = emptySet(),
    )

    /**
     * Pulling out of a stop in P into the lane (11~13). Cars stop at the right edge of the road (도로교통법 제34조), so
     * the signal for it is LEFT; a right one is for something else, a turn ahead. Nothing tells when the car is fully
     * in the lane: it counts as in at [ENTRY_MIN_M] driven forward in D, and the signal must be off by [ENTRY_MAX_M].
     * No LEFT at 10 m is 11, LEFT turned off after moving off ([MOVING_KPH] in D) and before 10 m is 12 (before moving
     * off it is as if never on), LEFT still on at 30 m is 13. With the signal not read by 10 m, nothing is judged.
     * Between two speeds, the signal changes, moving off and passing 10 and 30 m are taken in the order they
     * happened: a turn signal (callback time) can be handled before speeds (HAL time) from before it, and speeds have
     * gaps. [signal] as of [last], the speed counted to; [changes] the turn signals not yet taken; [metres] driven
     * forward in D; [signalledMoving]: LEFT at some time since moving off.
     * ponytail: fixed distances stand in for "in the lane" (the steering straightening out could tell; calibrate on
     * real drives). A left signal still on at 30 m for a left turn the guidance says is near is not 13 ([Turns]).
     */
    data class PullOut(
        val signal: TurnSignal?,
        val changes: List<Sample.Turn> = emptyList(),
        val metres: Float = 0f,
        val movedOff: Boolean = false,
        val signalledMoving: Boolean = false,
        val last: Sample.Speed? = null,
    ) {
        fun signal(t: Sample.Turn) = copy(changes = changes + t)

        fun undo(t: Sample.Turn) = copy(changes = changes - t)

        /** The pull-out after [s], or null once judged, with the item it cost if any. */
        fun drive(s: Sample.Speed, gear: GearPosition): Pair<PullOut?, RoadItem?> {
            val forward = gear == GearPosition.DRIVE && s.mps >= 0f // rolling back in D is not pulling out
            val from = last?.tNs ?: s.tNs
            val lastMps = max(last?.mps ?: 0f, 0f)
            val step = if (forward) (lastMps + s.mps) / 2 * (s.tNs - from) / NS_PER_S else 0f
            val driven = metres + step
            fun timeAt(fraction: Float) = from + (fraction.coerceIn(0f, 1f) * (s.tNs - from)).toLong()
            fun passed(m: Float) = if (metres < m && driven >= m) Passed(timeAt((m - metres) / step), m) else null
            val events = changes.filter { it.tNs <= s.tNs }.map { Signalled(max(it.tNs, from), it.signal) } +
                listOfNotNull(
                    if (!movedOff && forward && s.kph >= MOVING_KPH) {
                        MovedOff(timeAt((MOVING_MPS - lastMps) / (s.mps - lastMps)))
                    } else {
                        null
                    },
                    passed(ENTRY_MIN_M),
                    passed(ENTRY_MAX_M),
                )
            var signal = signal
            var moved = movedOff
            var signalling = signalledMoving
            var inLane = metres >= ENTRY_MIN_M
            for (e in events.sortedBy { it.tNs }) {
                when (e) {
                    is Signalled -> {
                        signal = e.signal
                        when {
                            e.signal == PULL_OUT_SIGNAL -> signalling = signalling || moved
                            inLane -> return null to null // off in time
                            signalling -> return null to RoadItem.START_SIGNAL_OFF_EARLY
                        }
                    }
                    is MovedOff -> {
                        moved = true
                        signalling = signal == PULL_OUT_SIGNAL
                    }
                    is Passed -> when {
                        e.metres >= ENTRY_MAX_M -> return null to RoadItem.START_SIGNAL_KEPT_ON // on since 10 m
                        signal == null -> return null to null
                        signal != PULL_OUT_SIGNAL -> return null to RoadItem.START_NO_SIGNAL
                        else -> inLane = true
                    }
                }
            }
            return PullOut(signal, changes.filter { it.tNs > s.tNs }, driven, moved, signalling, s) to null
        }
    }

    private companion object {
        const val FULL_MARKS = 100
        const val PASS_MARK = 70
        const val DISQUALIFYING_MARGIN_KPH = 10f
        const val STOPPED_KPH = 0.5f
        const val NEUTRAL_WITHIN_NS = 10_000_000_000L

        /** A shift into N just inside the 10 s can arrive after speeds past it; it is not penalized for that. */
        const val SKEW_GRACE_NS = 1_000_000_000L

        /** Pulling away releases the brake a moment before the car moves; not a stop without the brake. */
        const val BRAKE_GRACE_NS = 3_000_000_000L

        /** A hard acceleration this soon after moving off is part of the start. */
        const val START_WINDOW_NS = 3_000_000_000L

        /** No one turns the signal off and back on within a second. */
        const val BLIP_NS = 1_000_000_000L

        val PULL_OUT_SIGNAL = TurnSignal.LEFT
        const val ENTRY_MIN_M = 10f
        const val ENTRY_MAX_M = 30f
        const val MOVING_MPS = MOVING_KPH / 3.6f

        fun inGear(position: GearPosition) = position == GearPosition.DRIVE || position == GearPosition.REVERSE
        fun outOfGear(position: GearPosition) = position == GearPosition.NEUTRAL || position == GearPosition.PARK
    }
}

internal const val NS_PER_S = 1_000_000_000f

/** What happened between two speeds of a [RoadTest.PullOut], at its own time. */
private sealed interface PullOutEvent {
    val tNs: Long
}

private data class Signalled(override val tNs: Long, val signal: TurnSignal) : PullOutEvent

private data class MovedOff(override val tNs: Long) : PullOutEvent

private data class Passed(override val tNs: Long, val metres: Float) : PullOutEvent
