package com.kyusun.pleos.scoring

import com.kyusun.pleos.scoring.Disqualification.NO_SEAT_BELT
import com.kyusun.pleos.scoring.Disqualification.SPEEDING
import com.kyusun.pleos.scoring.RoadItem.GEAR_NOT_NEUTRAL
import com.kyusun.pleos.scoring.RoadItem.HARD_BRAKE
import com.kyusun.pleos.scoring.RoadItem.HARD_START
import com.kyusun.pleos.scoring.RoadItem.NEUTRAL_BEFORE_STOP
import com.kyusun.pleos.scoring.RoadItem.NO_BRAKE_WHEN_STOPPED
import com.kyusun.pleos.scoring.RoadItem.START_NO_SIGNAL
import com.kyusun.pleos.scoring.RoadItem.START_SIGNAL_KEPT_ON
import com.kyusun.pleos.scoring.RoadItem.START_SIGNAL_OFF_EARLY
import com.kyusun.pleos.scoring.RoadItem.TURN_NO_SIGNAL
import com.kyusun.pleos.scoring.RoadItem.TURN_SIGNAL_LATE
import com.kyusun.pleos.scoring.RoadItem.TURN_SIGNAL_NOT_KEPT
import com.kyusun.pleos.scoring.RoadItem.TURN_SIGNAL_NOT_OFF
import com.kyusun.pleos.signal.GearPosition.DRIVE
import com.kyusun.pleos.signal.GearPosition.NEUTRAL
import com.kyusun.pleos.signal.GearPosition.PARK
import com.kyusun.pleos.signal.GearPosition.REVERSE
import com.kyusun.pleos.signal.Sample
import com.kyusun.pleos.signal.TurnSignal
import com.kyusun.pleos.signal.TurnSignal.LEFT
import com.kyusun.pleos.signal.TurnSignal.OFF
import com.kyusun.pleos.signal.TurnSignal.RIGHT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadTestTest {
    @Test
    fun `a careful drive keeps all 100 points`() {
        val drive = merge(
            buckled(), gear(DRIVE, 0), brake(true, 0), speeds(List(30) { 0f }),
            brake(false, 3_000), speeds(ramp(0f, 40f, perSecond = 5f), startMs = 3_000), // gentle start, to 12 s
            brake(true, 12_000), speeds(ramp(40f, 0f, perSecond = 5f), startMs = 12_000), // stopped from 20 s
            gear(NEUTRAL, 21_500), speeds(List(120) { 0f }, startMs = 21_000), // in neutral within 10 s
            gear(DRIVE, 33_000), brake(false, 33_000), speeds(ramp(0f, 30f, perSecond = 5f), startMs = 33_000),
        )
        val test = score(drive)
        assertEquals(listOf<Enum<*>>(), penalties(test))
        assertEquals(100, test.score)
        assertTrue(test.passed)
    }

    @Test
    fun `a hard start costs 7 once per start, hard acceleration under way nothing`() {
        // Pulls away hard, eases off for a second and pulls hard again: still one start.
        val start = merge(
            buckled(), gear(DRIVE, 0), brake(true, 0), speeds(List(10) { 0f }),
            brake(false, 1_000), speeds(ramp(0f, 20f, perSecond = 20f) + ramp(20f, 40f, perSecond = 20f), 1_000),
        )
        assertEquals(listOf(HARD_START), penalties(score(start)))

        val underWay = merge(buckled(), gear(DRIVE, 0), speeds(List(30) { 30f } + ramp(30f, 50f, perSecond = 20f)))
        assertEquals(listOf<Enum<*>>(), penalties(score(underWay)))

        // A gentle start, then a hard pull 4 s after moving off: past the start.
        val late = speeds(ramp(0f, 15f, perSecond = 5f) + ramp(15f, 40f, perSecond = 20f))
        assertEquals(listOf<Enum<*>>(), penalties(score(merge(buckled(), gear(DRIVE, 0), brake(false, 0), late))))

        // Two hard starts with a stop between them.
        val twoStarts = ramp(0f, 30f, perSecond = 20f) + ramp(30f, 0f, perSecond = 5f) + ramp(0f, 30f, perSecond = 20f)
        val drive = merge(buckled(), gear(DRIVE, 0), brake(false, 0), speeds(twoStarts))
        assertEquals(listOf(HARD_START, HARD_START), penalties(score(drive)))
    }

    @Test
    fun `a speed flickering around standstill is still one stop`() {
        // 20 s in D without the brake, the speed reading 0 and 0.6 km/h by turns (creeping, rolling back).
        val standing = speeds(List(200) { if (it % 4 == 1) 0.6f else 0f })
        val drive = merge(buckled(), gear(DRIVE, 0), brake(false, 0), standing)
        assertEquals(listOf(NO_BRAKE_WHEN_STOPPED, GEAR_NOT_NEUTRAL), penalties(score(drive)))
    }

    @Test
    fun `a shift to neutral counts from when it happened, not when it arrived`() {
        // Stopping from 20 km/h, standing from 4.3 s. The shift to N at 4.5 s arrives before the speeds after 3 s.
        val stopping = speeds(List(10) { 20f } + ramp(20f, 0f, perSecond = 6f))
        val (early, late) = stopping.partition { it.tNs <= 3_000 * NS_PER_MS }
        val drive = buckled() + gear(DRIVE, 0) + brake(true, 0) + early + gear(NEUTRAL, 4_500) + late
        assertEquals(listOf<Enum<*>>(), penalties(score(drive)))
    }

    @Test
    fun `a stop is judged once the gear is read, but waiting after leaving park is not`() {
        // The app starts at a red light and reads D 1 s after the first speeds.
        val lateGear = merge(buckled(), brake(true, 0), speeds(List(120) { 0f }), gear(DRIVE, 1_000))
        assertEquals(listOf(GEAR_NOT_NEUTRAL), penalties(score(lateGear)))

        // At the start of the test: P, then D, then 14 s before moving off.
        val fromPark = merge(buckled(), gear(PARK, 0), brake(true, 0), speeds(List(150) { 0f }), gear(DRIVE, 1_000))
        assertEquals(listOf<Enum<*>>(), penalties(score(fromPark)))
    }

    @Test
    fun `each hard brake costs 7, and under 70 points the test is failed`() {
        val stopAndGo = ramp(0f, 30f, perSecond = 5f) + ramp(30f, 0f, perSecond = 15f)
        val test = score(merge(buckled(), gear(DRIVE, 0), brake(true, 0), speeds(List(5) { stopAndGo }.flatten())))
        assertEquals(List(5) { HARD_BRAKE }, penalties(test))
        assertEquals(65, test.score)
        assertFalse(test.passed)
    }

    @Test
    fun `stopping 10 s or more out of neutral costs 5 per stop`() {
        // Judged at 11 s: a shift into N just inside 10 s may arrive after the speeds past it.
        val twoStops = List(111) { 0f } + ramp(0f, 20f, perSecond = 5f) + ramp(20f, 0f, perSecond = 5f) +
            List(101) { 0f }
        val drive = merge(buckled(), gear(DRIVE, 0), brake(true, 0), speeds(twoStops))
        assertEquals(listOf(GEAR_NOT_NEUTRAL, GEAR_NOT_NEUTRAL), penalties(score(drive)))

        val shortStop = merge(buckled(), gear(DRIVE, 0), brake(true, 0), speeds(List(99) { 0f }))
        assertEquals(listOf<Enum<*>>(), penalties(score(shortStop)))
    }

    @Test
    fun `standing without the brake costs 5 per stop, but not in park`() {
        val noBrake = merge(buckled(), gear(DRIVE, 0), brake(false, 0), speeds(List(50) { 0f }))
        assertEquals(listOf(NO_BRAKE_WHEN_STOPPED), penalties(score(noBrake)))

        val parked = merge(buckled(), gear(PARK, 0), brake(false, 0), speeds(List(50) { 0f }))
        assertEquals(listOf<Enum<*>>(), penalties(score(parked)))
    }

    @Test
    fun `shifting to neutral before the car stops costs 5`() {
        val drive = merge(
            buckled(), gear(DRIVE, 0), brake(true, 0),
            speeds(List(10) { 20f } + ramp(20f, 0f, perSecond = 5f)), gear(NEUTRAL, 2_000),
        )
        assertEquals(listOf(NEUTRAL_BEFORE_STOP), penalties(score(drive)))
    }

    @Test
    fun `over 10 km per h above the limit disqualifies and ends the test`() {
        // 60 at a 50 limit is not over by more than 10; 61 is. The hard brake after it is not scored.
        val drive = merge(
            buckled(), gear(DRIVE, 0), limit(50),
            speeds(List(20) { 60f } + List(10) { 61f } + ramp(61f, 0f, perSecond = 15f)),
        )
        val test = score(drive)
        assertEquals(listOf(SPEEDING), penalties(test))
        assertFalse(test.passed)
    }

    @Test
    fun `unbuckling disqualifies, a belt that cannot be read does not`() {
        val unbuckled = merge(buckled(), gear(DRIVE, 0), speeds(List(30) { 30f }), belt(false, 2_000))
        assertEquals(listOf(NO_SEAT_BELT), penalties(score(unbuckled)))

        val unreadable = score(merge(belt(false, 0), gear(DRIVE, 0), speeds(List(30) { 30f })))
        assertEquals(listOf<Enum<*>>(), penalties(unreadable))
        assertTrue(unreadable.passed)
    }

    @Test
    fun `pulling out from park with the left signal until in the lane costs nothing`() {
        // On in P, or in D before the car moves, or late but before 10 m (6.3 m at 5 s); off between 10 and 30 m.
        for (on in listOf(500L, 1_500L, 5_000L)) {
            assertEquals("on at $on ms", listOf<Enum<*>>(), penalties(score(pullOut(turn(LEFT, on), turn(OFF, 7_500)))))
        }
    }

    @Test
    fun `pulling out from park without the left signal costs 5 at 10 m`() {
        assertEquals(START_NO_SIGNAL to 5_800.0, timed(score(pullOut())))
        // Turned off again before the car moved: as if never on.
        assertEquals(listOf(START_NO_SIGNAL), penalties(score(pullOut(turn(LEFT, 500), turn(OFF, 1_500)))))
        // The car leaves the right edge of the road to the left; a right signal is for something else (a turn).
        assertEquals(listOf(START_NO_SIGNAL), penalties(score(pullOut(turn(RIGHT, 1_500)))))
    }

    @Test
    fun `turning the left signal off before 10 m costs 5`() {
        // Judged at the first speed from the time it went off.
        assertEquals(START_SIGNAL_OFF_EARLY to 4_100.0, timed(score(pullOut(turn(LEFT, 1_500), turn(OFF, 4_000)))))
        assertEquals(listOf(START_SIGNAL_OFF_EARLY), penalties(score(pullOut(turn(LEFT, 1_500), turn(RIGHT, 4_000)))))
        // Either side of 10 m: 9.5 m at 5.7 s, 10.6 m at 5.9 s.
        assertEquals(listOf(START_SIGNAL_OFF_EARLY), penalties(score(pullOut(turn(LEFT, 1_500), turn(OFF, 5_650)))))
        assertEquals(listOf<Enum<*>>(), penalties(score(pullOut(turn(LEFT, 1_500), turn(OFF, 5_850)))))
    }

    @Test
    fun `turning the left signal off counts at its own time, though it arrives before the speeds up to it`() {
        // Off at 6.3 s (12.8 m), arriving before the speeds after 5.7 s (9.5 m): 0.6 s late, as seen on the emulator.
        val (early, late) = pullOut(turn(LEFT, 1_500)).partition { it.tNs <= 5_700 * NS_PER_MS }
        assertEquals(listOf<Enum<*>>(), penalties(score(early + turn(OFF, 6_300) + late)))
    }

    @Test
    fun `turning the left signal on counts at its own time too`() {
        // On at 6 s (11.1 m), arriving before the speeds after 5.4 s: it was not on at 10 m (5.8 s).
        val (early, late) = pullOut().partition { it.tNs <= 5_400 * NS_PER_MS }
        assertEquals(START_NO_SIGNAL to 5_800.0, timed(score(early + turn(LEFT, 6_000) + late + turn(OFF, 7_500))))
    }

    @Test
    fun `10 and 30 m are judged at the time the car passed them, between speeds`() {
        // No speeds from 8.9 to 9.9 s: the car passes 30 m at 9.24 s with the signal on, off at 9.6 s.
        val gap = pullOut(turn(LEFT, 1_500), turn(OFF, 9_600)).filterNot { it is Sample.Speed && it.tNs in GAP_NS }
        assertEquals(listOf(START_SIGNAL_KEPT_ON), penalties(score(gap)))
        // Off at 9.1 s, before 30 m: in time.
        val offBefore = pullOut(turn(LEFT, 1_500), turn(OFF, 9_100)).filterNot { it is Sample.Speed && it.tNs in GAP_NS }
        assertEquals(listOf<Enum<*>>(), penalties(score(offBefore)))
    }

    @Test
    fun `the signal and moving off count between speeds too`() {
        fun without(fromMs: Long, toMs: Long, drive: List<Sample>) =
            drive.filterNot { it is Sample.Speed && it.tNs in fromMs * NS_PER_MS..toMs * NS_PER_MS }
        // Off for 1.1 s between speeds 3.8 and 5.6 s (before 10 m): off before the car was in the lane.
        val offBefore = without(3_900, 5_500, pullOut(turn(LEFT, 1_500), turn(OFF, 4_000), turn(LEFT, 5_100), turn(OFF, 7_500)))
        assertEquals(listOf(START_SIGNAL_OFF_EARLY), penalties(score(offBefore)))
        // Off for 1.05 s between speeds 7.8 and 9.2 s (24 m): off in time.
        val offBetween = without(7_900, 9_100, pullOut(turn(LEFT, 1_500), turn(OFF, 8_000), turn(LEFT, 9_050)))
        assertEquals(listOf<Enum<*>>(), penalties(score(offBetween)))
        // 3 km/h passed at 2.6 s between speeds 2.3 and 3.1 s, the signal off at 2.9 s: after moving off.
        val offMoving = without(2_400, 3_000, pullOut(turn(LEFT, 1_500), turn(OFF, 2_900)))
        assertEquals(START_SIGNAL_OFF_EARLY to 3_100.0, timed(score(offMoving)))
    }

    @Test
    fun `rolling back in D is not pulling out`() {
        // 12.5 m backwards at 3 km/h in D (speed below zero), no signal: nothing judged yet.
        val back = merge(
            buckled(), turn(OFF, 0), gear(PARK, 0), brake(true, 0), gear(DRIVE, 1_000), brake(false, 2_000),
            speeds(List(150) { 3f }, startMs = 2_000).map { it.copy(mps = -it.mps) },
        )
        assertEquals(listOf<Enum<*>>(), penalties(score(back)))
    }

    @Test
    fun `moving off is in D at 3 km per h, and only off before that is as if never on`() {
        // Creeping at 2 km/h when the signal goes off at 4 s: not moved off yet.
        val creep = merge(
            buckled(), turn(OFF, 0), gear(PARK, 0), brake(true, 0), speeds(List(20) { 0f }), gear(DRIVE, 1_000),
            turn(LEFT, 1_500), brake(false, 2_000), turn(OFF, 4_050),
            speeds(List(30) { 2f } + ramp(2f, 21f, perSecond = 5f) + List(100) { 21f }, startMs = 2_000),
        )
        assertEquals(listOf(START_NO_SIGNAL), penalties(score(creep)))

        // Creeping at 2.5 km/h with the signal on past 10 m (16.4 s), off at 13.9 m: in time, though never 3 km/h.
        val slow = merge(
            buckled(), turn(OFF, 0), gear(PARK, 0), brake(true, 0), speeds(List(20) { 0f }), gear(DRIVE, 1_000),
            turn(LEFT, 1_500), brake(false, 2_000), speeds(List(300) { 2.5f }, startMs = 2_000), turn(OFF, 22_050),
        )
        assertEquals(listOf<Enum<*>>(), penalties(score(slow)))
    }

    @Test
    fun `a turn signal never read is not judged`() {
        assertEquals(listOf<Enum<*>>(), penalties(score(pullOut().filterNot { it is Sample.Turn })))
    }

    @Test
    fun `keeping the left signal on to 30 m costs 5`() {
        assertEquals(START_SIGNAL_KEPT_ON to 9_300.0, timed(score(pullOut(turn(LEFT, 1_500)))))
    }

    @Test
    fun `only pulling out from park is judged, and backing out of the space is not part of it`() {
        // Moving off in D from a stop (a light, say) needs no signal.
        val fromStop = merge(
            buckled(), gear(DRIVE, 0), brake(true, 0), speeds(List(20) { 0f }), brake(false, 2_000),
            speeds(ramp(0f, 20f, perSecond = 5f) + List(100) { 20f }, startMs = 2_000),
        )
        assertEquals(listOf<Enum<*>>(), penalties(score(fromStop)))

        // 16.7 m in R, the signal on in P and off at 10 km/h in R; then the pull-out in D with the left signal, off
        // at 19.4 m forward.
        val back = ramp(0f, 10f, perSecond = 5f) + List(30) { 10f } + ramp(10f, 0f, perSecond = 5f)
        val drive = merge(
            buckled(), turn(OFF, 0), gear(PARK, 0), brake(true, 0), turn(LEFT, 200), gear(REVERSE, 500),
            brake(false, 1_000), turn(OFF, 3_500),
            speeds(back, startMs = 1_000).map { it.copy(mps = -it.mps) }, brake(true, 9_500),
            gear(DRIVE, 10_000), turn(LEFT, 10_000), brake(false, 10_500),
            speeds(ramp(0f, 20f, perSecond = 5f) + List(100) { 20f }, startMs = 10_500), turn(OFF, 16_000),
        )
        assertEquals(listOf<Enum<*>>(), penalties(score(drive)))
    }

    @Test
    fun `a turn signalled from 30 m before it until past it costs nothing`() {
        // Right at 155.3 m: 30 m before it at 12.53 s, at it at 15.53 s, 30 m past it at 18.53 s.
        assertEquals(listOf<Enum<*>>(), penalties(score(turning(RIGHT, turn(RIGHT, 10_000), turn(OFF, 17_000)))))
        assertEquals(listOf<Enum<*>>(), penalties(score(turning(RIGHT, turn(RIGHT, 12_500), turn(OFF, 18_500)))))
        // Left turns and U-turns signal left.
        assertEquals(listOf<Enum<*>>(), penalties(score(turning(LEFT, turn(LEFT, 12_000), turn(OFF, 16_000)))))
    }

    @Test
    fun `a turn never signalled costs 7 at the turn`() {
        assertEquals(TURN_NO_SIGNAL to 15_500.0, timed(score(turning(RIGHT))))
        // The other way is no signal for this turn.
        assertEquals(listOf(TURN_NO_SIGNAL), penalties(score(turning(RIGHT, turn(LEFT, 10_000), turn(OFF, 17_000)))))
        // On and off again before 30 m: never on from there.
        assertEquals(listOf(TURN_NO_SIGNAL), penalties(score(turning(RIGHT, turn(RIGHT, 10_000), turn(OFF, 12_000)))))
    }

    @Test
    fun `signalling only after 30 m before the turn costs 7 when it goes on`() {
        assertEquals(TURN_SIGNAL_LATE to 12_600.0, timed(score(turning(RIGHT, turn(RIGHT, 12_600), turn(OFF, 17_000)))))
    }

    @Test
    fun `turning the signal off before the turn costs 7 when it goes off`() {
        assertEquals(TURN_SIGNAL_NOT_KEPT to 14_000.0, timed(score(turning(RIGHT, turn(RIGHT, 10_000), turn(OFF, 14_000)))))
        // On again before the turn: still not kept, once. Switched the other way: not kept either.
        val again = turning(
            RIGHT, turn(RIGHT, 10_000), turn(OFF, 14_000), turn(RIGHT, 14_500), turn(OFF, 15_000), turn(RIGHT, 15_200),
            turn(OFF, 17_000),
        )
        assertEquals(listOf(TURN_SIGNAL_NOT_KEPT), penalties(score(again)))
        val switched = turning(RIGHT, turn(RIGHT, 10_000), turn(LEFT, 14_000), turn(OFF, 16_000))
        assertEquals(listOf(TURN_SIGNAL_NOT_KEPT), penalties(score(switched)))
        // Late and not kept: both.
        val late = turning(RIGHT, turn(RIGHT, 13_000), turn(OFF, 14_000))
        assertEquals(listOf(TURN_SIGNAL_LATE, TURN_SIGNAL_NOT_KEPT), penalties(score(late)))
        // Off just past the turn: kept.
        assertEquals(listOf<Enum<*>>(), penalties(score(turning(RIGHT, turn(RIGHT, 10_000), turn(OFF, 15_600)))))
    }

    @Test
    fun `a signal back on within a second was not turned off`() {
        // Off at 14 s and back 2 ms later, both arriving before the speeds after 13.9 s (the speeds come ~0.6 s late):
        // on the emulator, the Virtual Console resetting the signal. Back after 1 s: off.
        val (early, late) = turning(RIGHT, turn(RIGHT, 10_000), turn(OFF, 17_000)).partition { it.tNs <= 13_900 * NS_PER_MS }
        assertEquals(listOf<Enum<*>>(), penalties(score(early + turn(OFF, 14_000) + turn(RIGHT, 14_002) + late)))
        assertEquals(listOf(TURN_SIGNAL_NOT_KEPT), penalties(score(early + turn(OFF, 14_000) + turn(RIGHT, 15_000) + late)))
        // The same for pulling out: off at 4 s (before 10 m) and back at once.
        val (start, rest) = pullOut(turn(LEFT, 1_500), turn(OFF, 7_500)).partition { it.tNs <= 3_900 * NS_PER_MS }
        assertEquals(listOf<Enum<*>>(), penalties(score(start + turn(OFF, 4_000) + turn(LEFT, 4_002) + rest)))
    }

    @Test
    fun `keeping the signal on 30 m past the turn costs 7`() {
        assertEquals(TURN_SIGNAL_NOT_OFF to 18_500.0, timed(score(turning(RIGHT, turn(RIGHT, 10_000)))))
    }

    @Test
    fun `the turn signal counts at its own time, though it arrives before the speeds up to it`() {
        // On at 12.5 s (in time) and at 12.6 s (late), each arriving before the speeds after 11.9 s.
        fun onEarly(atMs: Long): List<Sample> {
            val (early, late) = turning(RIGHT, turn(OFF, 17_000)).partition { it.tNs <= 11_900 * NS_PER_MS }
            return early + turn(RIGHT, atMs) + late
        }
        assertEquals(listOf<Enum<*>>(), penalties(score(onEarly(12_500))))
        assertEquals(TURN_SIGNAL_LATE to 12_600.0, timed(score(onEarly(12_600))))
    }

    @Test
    fun `30 m before the turn is where the car passed it, between speeds`() {
        // No speeds from 12.1 to 12.9 s: 30 m before the turn at 12.53 s, at 125.3 m.
        fun gap(onMs: Long) = turning(RIGHT, turn(RIGHT, onMs), turn(OFF, 17_000))
            .filterNot { it is Sample.Speed && it.tNs in 12_100 * NS_PER_MS..12_900 * NS_PER_MS }
        assertEquals(listOf<Enum<*>>(), penalties(score(gap(12_400))))
        assertEquals(listOf(TURN_SIGNAL_LATE), penalties(score(gap(12_700))))
    }

    @Test
    fun `each guidance update puts the turn where the navigation says`() {
        // 155.3 m ahead at 0 s, then 60.3 m ahead at 10 s (100 m): the turn is at 160.3 m, 30 m before it at 13.03 s.
        val drive = cruise(nextTurn(RIGHT, 155.3f, 0), nextTurn(RIGHT, 60.3f, 10_000), turn(RIGHT, 12_800), turn(OFF, 18_000))
        assertEquals(listOf<Enum<*>>(), penalties(score(drive)))
    }

    @Test
    fun `the turn is reached when the navigation moves on near it, and dropped when it moves on far from it`() {
        // Nothing ahead from 15 s, 5.3 m before the turn: reached then.
        assertEquals(TURN_NO_SIGNAL to 15_000.0, timed(score(cruise(nextTurn(RIGHT, 155.3f, 0), nextTurn(OFF, 0f, 15_000)))))
        // From 10 s, 55.3 m before it: the route changed.
        assertEquals(listOf<Enum<*>>(), penalties(score(cruise(nextTurn(RIGHT, 155.3f, 0), nextTurn(OFF, 0f, 10_000)))))
    }

    @Test
    fun `a turn first heard of within 30 m of it is not signalled late`() {
        // The navigation first gives the turn at 10 s, 20 m ahead (a new route, say): signalling then is in time,
        // but never signalling still costs 30.
        assertEquals(listOf<Enum<*>>(), penalties(score(cruise(nextTurn(RIGHT, 20f, 10_000), turn(RIGHT, 10_500), turn(OFF, 14_000)))))
        assertEquals(TURN_NO_SIGNAL to 12_000.0, timed(score(cruise(nextTurn(RIGHT, 20f, 10_000)))))
    }

    @Test
    fun `the signal kept on into a next turn the same way is not left on`() {
        // Right at 155.3 m, then right again at 195.3 m: its 30 m (from 165.3 m) begin before 30 m past the first.
        val guidance = (0L..25_000L step 1_000).flatMap { t ->
            val m = t / 100f
            when {
                m <= 155.3f -> nextTurn(RIGHT, 155.3f - m, t)
                m <= 195.3f -> nextTurn(RIGHT, 195.3f - m, t)
                else -> nextTurn(OFF, 0f, t)
            }
        }
        assertEquals(listOf<Enum<*>>(), penalties(score(cruise(guidance, turn(RIGHT, 10_000), turn(OFF, 20_000)))))
    }

    @Test
    fun `a left turn just after pulling out keeps the left signal on for the turn`() {
        // Left in 45 m, said while in park: the left signal from the start to 51.6 m (13 s), past the turn at 45 m.
        assertEquals(listOf<Enum<*>>(), penalties(score(pullOut(nextTurn(LEFT, 45f, 500), turn(LEFT, 1_500), turn(OFF, 13_000)))))
        assertEquals(listOf(START_SIGNAL_KEPT_ON), penalties(score(pullOut(turn(LEFT, 1_500), turn(OFF, 13_000)))))
    }

    @Test
    fun `turns are not judged with the signal never read`() {
        assertEquals(listOf<Enum<*>>(), penalties(score(turning(RIGHT).filterNot { it is Sample.Turn })))
    }

    @Test
    fun `recorded CARLA drive`() {
        val samples = recording("carla-drive-1028.csv")
        val startMs = samples.first().tNs / NS_PER_MS
        val test = score(samples)
        // The autopilot pulls away and stops harder than the real-car figures (EventDetectorTest), pulls out of park
        // without the left signal (10 m at 8.4 s) and waits at a red light for 14 s in D (35.7 s). Its second hard
        // pull at 52.5 s belongs to the start at 49.7 s.
        assertEquals(
            listOf(
                HARD_START to 6_900L, START_NO_SIGNAL to 8_400L, HARD_BRAKE to 35_600L, GEAR_NOT_NEUTRAL to 46_800L,
                HARD_START to 50_200L, HARD_BRAKE to 96_000L,
            ),
            test.penalties.map { penalty(it) to (it.tNs / NS_PER_MS - startMs) / 100 * 100 },
        )
        assertEquals(62, test.score)
    }

    @Test
    fun `recorded careful drives lose only the pull-out signal`() {
        // tools/carla/driver.py: starts and stops gently, waits at lights in N with the brake held, shifting back
        // to D before moving off, and ends braking to a stop, then P. 1028: three red lights (14, 25 and 20 s).
        // 1029 (filmed, assets/experiments/carla-careful-drive-1029): one light (35 s). Both were driven before the
        // driver signalled pulling out: 1028 without a signal, 1029 with the right one for a turn just ahead.
        for (name in listOf("carla-careful-drive-1028.csv", "carla-careful-drive-1029.csv")) {
            val test = score(recording(name))
            assertEquals(name, listOf(START_NO_SIGNAL), penalties(test))
            assertEquals(name, 95, test.score)
            assertTrue(name, test.passed)
        }
    }

    @Test
    fun `recorded careful drive pulling out with the left signal keeps all 100 points`() {
        // driver.py on the Gangnam map, 60 s down 테헤란로 at the 50 limit with the belt buckled (both over ③): the
        // left signal from the start until 20 m as the app counts them (it read 19.7 m).
        val test = score(recording("carla-careful-drive-gangnam-1029.csv"))
        assertEquals(listOf<Enum<*>>(), penalties(test))
        assertEquals(100, test.score)
        assertTrue(test.passed)
    }

    @Test
    fun `recorded careful drive turning left and right with the signal keeps all 100 points`() {
        // driver.py on the Gangnam map with --turns left,right: pulls out with the left signal, turns left at 282 m and
        // right at the next junction it can, then takes two left bends, each signalled from 40 m before the junction,
        // the navigation's next turn coming over ③. Under host load CARLA stalled up to 9 s, and the Virtual Console
        // reset the signal to off for 2 ms (133.4 s, before a turn) and 2.6 s (160.1 s, just past one).
        val test = score(recording("carla-turns-gangnam-1030.csv"))
        assertEquals(listOf<Enum<*>>(), penalties(test))
        assertEquals(100, test.score)
    }

    @Test
    fun `recorded drive turning without the signal costs 7 a turn`() {
        // The same route with --no-turn-signal: the left signal only for pulling out. Each of the four turns (left,
        // right, left, left) costs 30 at its turn point, the navigation's distance carried on by the car's speed.
        val samples = recording("carla-turns-no-signal-gangnam-1030.csv")
        val startMs = samples.first().tNs / NS_PER_MS
        val test = score(samples)
        assertEquals(
            listOf(TURN_NO_SIGNAL to 89_800L, TURN_NO_SIGNAL to 108_400L, TURN_NO_SIGNAL to 161_700L, TURN_NO_SIGNAL to 183_000L),
            test.penalties.map { penalty(it) to (it.tNs / NS_PER_MS - startMs) / 100 * 100 },
        )
        assertEquals(72, test.score)
        assertTrue(test.passed)
    }

    @Test
    fun `recorded pull-out with the left signal on through a junction costs 13`() {
        // driver.py on the Gangnam map before it let go of the signal inside a junction: the start lane enters the
        // 강남역 junction at 10 m, so the left signal it gave at the start stayed on to 44 m (the app counts 46 m).
        val test = score(recording("carla-pull-out-kept-on-1029.csv"))
        assertEquals(listOf(START_SIGNAL_KEPT_ON), penalties(test))
        assertEquals(95, test.score)
    }
}

private fun score(samples: List<Sample>): RoadTest =
    samples.fold(RoadTest()) { test, sample -> test.next(sample).first }

private fun penalties(test: RoadTest): List<Enum<*>> = test.penalties.map(::penalty)

private fun penalty(p: Penalty): Enum<*> = when (p) {
    is Penalty.Deduction -> p.item
    is Penalty.Disqualified -> p.reason
}

/**
 * Pulling out from park, the signal read off: P, D at 1 s, the brake off at 2 s, then 0 to 21 km/h at 5 km/h per s and 10 s at 21, plus
 * [signals]. Driven forward: 2.8 m at 4 s, 6.3 m at 5 s, 10 m at 5.8 s, 19.8 m at 7.5 s, 30 m at 9.3 s (no sample
 * falls on 10 or 30 m).
 */
private fun pullOut(vararg signals: List<Sample>): List<Sample> = merge(
    buckled(), turn(OFF, 0), gear(PARK, 0), brake(true, 0), speeds(List(20) { 0f }), gear(DRIVE, 1_000), brake(false, 2_000),
    speeds(ramp(0f, 21f, perSecond = 5f) + List(100) { 21f }, startMs = 2_000), *signals,
)

/** At 36 km/h in D, 1 m per 100 ms from 0 to 25 s, the signal read off at the start, plus [extra]. */
private fun cruise(vararg extra: List<Sample>): List<Sample> =
    merge(buckled(), gear(DRIVE, 0), brake(false, 0), turn(OFF, 0), speeds(List(250) { 36f }), *extra)

/**
 * [cruise] with the navigation saying every second how far a [signal] turn at 155.3 m is, and nothing ahead once
 * past it: 30 m before it at 12.53 s, at it at 15.53 s, 30 m past it at 18.53 s (no sample falls on these).
 */
private fun turning(signal: TurnSignal, vararg extra: List<Sample>): List<Sample> {
    val guidance = (0L..25_000L step 1_000).flatMap { t ->
        val remain = 155.3f - t / 100f
        if (remain >= 0f) nextTurn(signal, remain, t) else nextTurn(OFF, 0f, t)
    }
    return cruise(guidance, *extra)
}

/** The test's only penalty and when, in ms, to the 100 ms (the next speed sample after the distance is reached). */
private fun timed(test: RoadTest): Pair<Enum<*>, Double> {
    val p = test.penalties.single()
    return penalty(p) to Math.round(p.tNs / NS_PER_MS / 100.0) * 100.0
}

private val GAP_NS = 8_900 * NS_PER_MS..9_900 * NS_PER_MS
