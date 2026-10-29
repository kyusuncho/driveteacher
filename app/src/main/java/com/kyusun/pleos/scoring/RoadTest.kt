package com.kyusun.pleos.scoring

import com.kyusun.pleos.signal.GearPosition
import com.kyusun.pleos.signal.Sample

/**
 * Road test items (도로교통법 시행규칙 별표26, 2026. 2. 24.) judged from the car's own signals. [number] is the
 * item's number in the annex, [label] its name there. PLAN 5절 lists all 46 and how each is judged.
 */
enum class RoadItem(val number: Int, val label: String, val points: Int) {
    /** Automatic: stopped 10 s or more without the lever in neutral. */
    GEAR_NOT_NEUTRAL(4, "정지 중 기어 미중립", 5),

    /** A hard acceleration while pulling away. On an EV the annex means the pedal-misuse safety brake, not readable. */
    HARD_START(9, "급조작ㆍ급출발", 7),

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
        ) to emptyList()
        is Sample.Brake -> copy(
            braking = sample.pressed,
            stop = stop?.copy(brakeOffNs = if (sample.pressed) null else stop.brakeOffNs ?: sample.tNs),
        ) to emptyList()
        is Sample.Turn, is Sample.Steering, is Sample.SeatBelt, is Sample.SpeedLimit, is Sample.Location,
        is Sample.SpeedCameras ->
            this to emptyList()
    }

    private fun onSpeed(s: Sample.Speed): Pair<RoadTest, List<Penalty>> {
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

        fun inGear(position: GearPosition) = position == GearPosition.DRIVE || position == GearPosition.REVERSE
        fun outOfGear(position: GearPosition) = position == GearPosition.NEUTRAL || position == GearPosition.PARK
    }
}
