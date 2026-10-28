package com.kyusun.pleos.scoring

import com.kyusun.pleos.scoring.Disqualification.NO_SEAT_BELT
import com.kyusun.pleos.scoring.Disqualification.SPEEDING
import com.kyusun.pleos.scoring.RoadItem.GEAR_NOT_NEUTRAL
import com.kyusun.pleos.scoring.RoadItem.HARD_BRAKE
import com.kyusun.pleos.scoring.RoadItem.HARD_START
import com.kyusun.pleos.scoring.RoadItem.NEUTRAL_BEFORE_STOP
import com.kyusun.pleos.scoring.RoadItem.NO_BRAKE_WHEN_STOPPED
import com.kyusun.pleos.signal.GearPosition.DRIVE
import com.kyusun.pleos.signal.GearPosition.NEUTRAL
import com.kyusun.pleos.signal.GearPosition.PARK
import com.kyusun.pleos.signal.Sample
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
    fun `recorded CARLA drive`() {
        val samples = recording("carla-drive-1028.csv")
        val startMs = samples.first().tNs / NS_PER_MS
        val test = score(samples)
        // The autopilot pulls away and stops harder than the real-car figures (EventDetectorTest) and waits at a red
        // light for 14 s in D (35.7 s). Its second hard pull at 52.5 s belongs to the start at 49.7 s.
        assertEquals(
            listOf(
                HARD_START to 6_900L, HARD_BRAKE to 35_600L, GEAR_NOT_NEUTRAL to 46_800L, HARD_START to 50_200L,
                HARD_BRAKE to 96_000L,
            ),
            test.penalties.map { penalty(it) to (it.tNs / NS_PER_MS - startMs) / 100 * 100 },
        )
        assertEquals(67, test.score)
    }

    @Test
    fun `recorded careful drive keeps all 100 points`() {
        // tools/carla/driver.py: starts and stops gently, waits at three red lights (14, 25 and 20 s) in N with the
        // brake held, shifting back to D before moving off, and ends braking to a stop, then P.
        val test = score(recording("carla-careful-drive-1028.csv"))
        assertEquals(listOf<Enum<*>>(), penalties(test))
        assertEquals(100, test.score)
        assertTrue(test.passed)
    }
}

private fun score(samples: List<Sample>): RoadTest =
    samples.fold(RoadTest()) { test, sample -> test.next(sample).first }

private fun penalties(test: RoadTest): List<Enum<*>> = test.penalties.map(::penalty)

private fun penalty(p: Penalty): Enum<*> = when (p) {
    is Penalty.Deduction -> p.item
    is Penalty.Disqualified -> p.reason
}
