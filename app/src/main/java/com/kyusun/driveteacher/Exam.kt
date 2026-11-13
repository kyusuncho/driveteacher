package com.kyusun.driveteacher

import com.kyusun.driveteacher.scoring.Penalty
import com.kyusun.driveteacher.scoring.RoadTest
import com.kyusun.driveteacher.signal.Sample

/**
 * Which signals the car gave during a test, so the result can say what it could not judge: a belt never read as
 * buckled (the emulator, PLAN 10절), a speed limit never known (0), no turn guidance from the navigation.
 */
data class Coverage(
    val beltRead: Boolean = false,
    val limitRead: Boolean = false,
    val turnsGuided: Boolean = false,
) {
    fun next(s: Sample): Coverage = when {
        s is Sample.SeatBelt && s.buckled && !beltRead -> copy(beltRead = true)
        s is Sample.SpeedLimit && s.kph > 0 && !limitRead -> copy(limitRead = true)
        s is Sample.NextTurn && !turnsGuided -> copy(turnsGuided = true)
        else -> this
    }
}

/** One road test as the screens show it: the score, its time span, and the [Coverage]. Immutable like [RoadTest]. */
data class Exam(
    val test: RoadTest = RoadTest(),
    val firstNs: Long? = null,
    val lastNs: Long? = null,
    val coverage: Coverage = Coverage(),
) {
    fun next(s: Sample): Pair<Exam, List<Penalty>> {
        val (scored, new) = test.next(s)
        // Signals arrive out of time order by up to ~0.6 s (Sample.tNs), so the span is the min and max seen.
        val exam = copy(
            test = scored,
            firstNs = minOf(firstNs ?: s.tNs, s.tNs),
            lastNs = maxOf(lastNs ?: s.tNs, s.tNs),
            coverage = coverage.next(s),
        )
        return exam to new
    }

    val durationNs: Long get() = if (firstNs == null || lastNs == null) 0 else lastNs - firstNs

    /** How far into the test [p] happened; 0 before any sample. */
    fun sinceStartNs(p: Penalty): Long = (p.tNs - (firstNs ?: p.tNs)).coerceAtLeast(0)
}
