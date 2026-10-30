package com.kyusun.pleos.scoring

import com.kyusun.pleos.signal.Sample
import kotlin.math.abs

/** Something the driver did, found in the [Sample] stream. Both modes score these (PLAN 2절). */
sealed interface DrivingEvent {
    val tNs: Long

    /**
     * Speed rose faster than the threshold over the last second. From a standstill this is a hard start.
     * [kphPerS] is the rate when the episode started, not its peak (the event is not held back for that).
     */
    data class HardAccel(override val tNs: Long, val kphPerS: Float) : DrivingEvent

    /** Speed fell faster than the threshold over the last second; [kphPerS] as in [HardAccel], positive. */
    data class HardBrake(override val tNs: Long, val kphPerS: Float) : DrivingEvent

    data class Speeding(override val tNs: Long, val kph: Float, val limitKph: Int) : DrivingEvent

    /** The driver's belt came off while moving. */
    data class BeltOff(override val tNs: Long) : DrivingEvent

    /**
     * The car moved off and every belt read so far said unbuckled. The emulator always reads false, so the belt item
     * is off until the belt first reads buckled; on a real car, driving off unbuckled looks the same and is not scored.
     */
    data class BeltUnreadable(override val tNs: Long) : DrivingEvent
}

/** Speed as its size: AAOS reports it below zero in reverse. */
internal val Sample.Speed.kph: Float get() = abs(mps) * KPH_PER_MPS

/** At or above this the car is under way (pulling away, or rolling with the belt off). */
internal const val MOVING_KPH = 3f

private const val KPH_PER_MPS = 3.6f

/** Defaults are public figures for real cars (PLAN 6절); recalibrate on real drives. */
data class Thresholds(
    val hardAccelKphPerS: Float = 9.7f,
    val hardBrakeKphPerS: Float = 10.8f,
    val speedingMarginKph: Float = 0f,
)

/**
 * Finds [DrivingEvent]s in the [Sample] stream, one sample at a time. Immutable: [next] returns the detector for the
 * following sample with the events this one started. Each episode (a hard start, a stretch over the limit) gives one
 * event; it ends only well below its threshold, so readings wobbling around it do not repeat the event.
 *
 * Each signal must come in its own time order, as SignalCollector delivers it. Skew across signals does not matter:
 * rates come from speed alone, and the limit and belt are checked at the speed samples that follow them (the belt
 * only once it has been read: the Vehicle SDK can start seconds after speed). Speed counts as its size, since AAOS
 * reports it below zero in reverse. A speed with no sample 1 to 1.5 s before it (after a gap in the samples) gives
 * no rate, and an unknown limit (0) gives no speeding; both leave an episode in progress as it is.
 */
data class EventDetector(
    val thresholds: Thresholds = Thresholds(),
    private val recent: List<Sample.Speed> = emptyList(),
    private val limitKph: Int = 0,
    private val buckled: Boolean? = null, // not read yet
    private val everBuckled: Boolean = false,
    private val beltChecked: Boolean = false,
    private val ongoing: Set<Episode> = emptySet(),
) {
    fun next(sample: Sample): Pair<EventDetector, List<DrivingEvent>> = when (sample) {
        is Sample.Speed -> onSpeed(sample)
        is Sample.SpeedLimit -> copy(limitKph = sample.kph) to emptyList()
        is Sample.SeatBelt -> copy(buckled = sample.buckled, everBuckled = everBuckled || sample.buckled) to emptyList()
        is Sample.Gear, is Sample.Brake, is Sample.Turn, is Sample.Steering, is Sample.Location,
        is Sample.SpeedCameras, is Sample.NextTurn -> this to emptyList()
    }

    private fun onSpeed(s: Sample.Speed): Pair<EventDetector, List<DrivingEvent>> {
        val kph = s.kph
        val window = (recent + s).filter { s.tNs - it.tNs <= MAX_SPAN_NS }
        val rate = window.lastOrNull { s.tNs - it.tNs >= SPAN_NS }
            ?.let { (kph - it.kph) * NS_PER_S / (s.tNs - it.tNs) }
        val moving = kph >= MOVING_KPH
        val episodes = listOfNotNull(
            Episode.HARD_ACCEL.takeIf { holdsRate(it, rate, thresholds.hardAccelKphPerS) },
            Episode.HARD_BRAKE.takeIf { holdsRate(it, rate?.let { r -> -r }, thresholds.hardBrakeKphPerS) },
            Episode.SPEEDING.takeIf {
                if (limitKph == 0) it in ongoing
                else holds(it, kph - limitKph - thresholds.speedingMarginKph, LIMIT_BAND_KPH, -LIMIT_BAND_KPH)
            },
            // Ends only when buckled again: stop-and-go unbuckled is one event.
            Episode.BELT_OFF.takeIf { everBuckled && buckled == false && (moving || it in ongoing) },
        ).toSet()
        val started = (episodes - ongoing).map {
            when (it) {
                Episode.HARD_ACCEL -> DrivingEvent.HardAccel(s.tNs, checkNotNull(rate))
                Episode.HARD_BRAKE -> DrivingEvent.HardBrake(s.tNs, -checkNotNull(rate))
                Episode.SPEEDING -> DrivingEvent.Speeding(s.tNs, kph, limitKph)
                Episode.BELT_OFF -> DrivingEvent.BeltOff(s.tNs)
            }
        }
        val checkBelt = moving && buckled != null && !beltChecked
        val unreadable = listOfNotNull(DrivingEvent.BeltUnreadable(s.tNs).takeIf { checkBelt && !everBuckled })
        return copy(recent = window, beltChecked = beltChecked || checkBelt, ongoing = episodes) to unreadable + started
    }

    /** An episode starts at [enter] and lasts while [value] stays at or above [exit]. */
    private fun holds(episode: Episode, value: Float, enter: Float, exit: Float = enter * EXIT_FRACTION) =
        value >= if (episode in ongoing) exit else enter

    /** As [holds]; with no rate an episode in progress goes on and none starts. */
    private fun holdsRate(episode: Episode, rate: Float?, enter: Float) =
        if (rate == null) episode in ongoing else holds(episode, rate, enter)

    enum class Episode { HARD_ACCEL, HARD_BRAKE, SPEEDING, BELT_OFF }

    private companion object {
        const val NS_PER_S = 1_000_000_000f
        const val SPAN_NS = 1_000_000_000L
        const val MAX_SPAN_NS = 1_500_000_000L
        const val EXIT_FRACTION = 0.5f

        /** Speeding starts half a km/h over the limit and ends half a km/h under it. The bridge's whole km/h come
         * back as m/s floats (50 reads 50.000004), and a speed hovering at the limit should count once. */
        const val LIMIT_BAND_KPH = 0.5f
    }
}
