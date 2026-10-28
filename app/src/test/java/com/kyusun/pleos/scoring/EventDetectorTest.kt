package com.kyusun.pleos.scoring

import com.kyusun.pleos.scoring.DrivingEvent.BeltOff
import com.kyusun.pleos.scoring.DrivingEvent.BeltUnreadable
import com.kyusun.pleos.scoring.DrivingEvent.HardAccel
import com.kyusun.pleos.scoring.DrivingEvent.HardBrake
import com.kyusun.pleos.scoring.DrivingEvent.Speeding
import com.kyusun.pleos.signal.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDetectorTest {
    @Test
    fun `steady driving gives no events`() {
        assertEquals(listOf<DrivingEvent>(), detect(buckled() + speeds(List(50) { 40f })))
    }

    @Test
    fun `one hard start gives one event, a gentle one none`() {
        val hard = detect(buckled() + speeds(ramp(0f, 30f, perSecond = 20f)))
        assertEquals(listOf(HardAccel::class), hard.map { it::class })
        assertTrue((hard[0] as HardAccel).kphPerS >= 9.7f)

        assertEquals(listOf<DrivingEvent>(), detect(buckled() + speeds(ramp(0f, 30f, perSecond = 5f))))
    }

    @Test
    fun `hard braking gives one event, with the rate when it started`() {
        val events = detect(buckled() + speeds(List(20) { 50f } + ramp(50f, 0f, perSecond = 15f)))
        assertEquals(listOf(HardBrake::class), events.map { it::class })
        assertEquals(12f, (events[0] as HardBrake).kphPerS, 0.01f) // the first 1 s rate at or over 10.8
    }

    @Test
    fun `a rate wobbling around the threshold is still one episode`() {
        // 1 s rates alternate between 10.3 and 9.1 km/h/s every second, around the 9.7 threshold.
        val kph = (0 until 80).map { i -> 0.97f * i + if (i / 10 % 2 == 0) 0.3f else -0.3f }
        assertEquals(listOf(HardAccel::class), detect(buckled() + speeds(kph)).map { it::class })
    }

    @Test
    fun `missing samples still give the rate over the last second`() {
        val kph = ramp(0f, 30f, perSecond = 20f)
        val sparse = speeds(kph).filterIndexed { i, _ -> i % 3 != 1 } // 0.2 s holes
        assertEquals(listOf(HardAccel::class), detect(buckled() + sparse).map { it::class })
    }

    @Test
    fun `no rate across a long gap in the samples`() {
        // The emulator froze for 3 s while the car sped up from 0 to 40: the jump is not a 1 s rate.
        val before = speeds(List(20) { 0f })
        val after = speeds(List(20) { 40f }, startMs = before.last().tNs / NS_PER_MS + 3_000)
        assertEquals(listOf<DrivingEvent>(), detect(buckled() + before + after))
    }

    @Test
    fun `speeding is one event per stretch over the limit`() {
        val kph = List(10) { 45f } + List(10) { 51f } + List(10) { 50f } + List(10) { 51f } + // one stretch
            List(10) { 45f } + List(10) { 54f } // back under, then over again (a 9 km/h step: not a hard start)
        val events = detect(buckled() + limit(50) + speeds(kph))
        assertEquals(listOf(Speeding::class, Speeding::class), events.map { it::class })
        val first = events[0] as Speeding
        assertEquals(50, first.limitKph)
        assertEquals(51f, first.kph, 0.01f)
    }

    @Test
    fun `driving at the limit is not speeding, nor is an unknown limit`() {
        // The bridge's whole km/h come back as m/s floats: 50 km/h reads 50.000004.
        assertEquals(listOf<DrivingEvent>(), detect(buckled() + limit(50) + speeds(List(30) { 50f })))
        assertEquals(listOf<DrivingEvent>(), detect(buckled() + limit(0) + speeds(List(30) { 120f })))
    }

    @Test
    fun `unbuckling while moving is one event`() {
        val drive = buckled() + speeds(List(10) { 30f }) + belt(false, 2_000) +
            speeds(List(10) { 30f }, startMs = 2_000)
        assertEquals(listOf(BeltOff::class), detect(drive).map { it::class })
    }

    @Test
    fun `a belt never read as buckled turns the belt item off`() {
        val events = detect(belt(false, 0) + speeds(List(10) { 0f } + ramp(0f, 30f, perSecond = 5f)))
        assertEquals(listOf(BeltUnreadable::class), events.map { it::class })
    }

    @Test
    fun `speeding up then braking hard gives both events`() {
        val kph = ramp(0f, 30f, perSecond = 15f) + ramp(30f, 0f, perSecond = 15f)
        assertEquals(listOf(HardAccel::class, HardBrake::class), detect(buckled() + speeds(kph)).map { it::class })
    }

    @Test
    fun `hard braking cut by a freeze is still one event`() {
        // 1 s into braking at 15 km/h/s the emulator freezes for 2 s; afterwards there is no rate for a second.
        val braking = speeds(List(20) { 60f } + ramp(60f, 0f, perSecond = 15f))
        val frozen = braking.take(30) + braking.drop(30).map { it.copy(tNs = it.tNs + 2_000 * NS_PER_MS) }
        assertEquals(listOf(HardBrake::class), detect(buckled() + frozen).map { it::class })
    }

    @Test
    fun `braking hard while reversing is a hard brake`() {
        // AAOS reports the speed below zero in reverse.
        val events = detect(buckled() + speeds(List(20) { -15f } + ramp(-15f, 0f, perSecond = 15f)))
        assertEquals(listOf(HardBrake::class), events.map { it::class })
    }

    @Test
    fun `speeding stays one event while the limit is briefly unknown`() {
        val drive = buckled() + limit(50) + speeds(List(10) { 60f }) + limit(0, 1_000) +
            speeds(List(10) { 60f }, startMs = 1_000) + limit(50, 2_000) + speeds(List(10) { 60f }, startMs = 2_000)
        assertEquals(listOf(Speeding::class), detect(drive).map { it::class })
    }

    @Test
    fun `stop-and-go unbuckled is one event until the belt is buckled again`() {
        val creep = List(40) { if (it / 5 % 2 == 0) 4f else 2f } // around walking pace, stopping and going
        val drive = buckled() + belt(false, 0) + speeds(creep) + belt(true, 4_000) + speeds(creep, startMs = 4_000) +
            belt(false, 8_000) + speeds(creep, startMs = 8_000)
        assertEquals(listOf(BeltOff::class, BeltOff::class), detect(drive).map { it::class })
    }

    @Test
    fun `a belt read that comes after moving off is not unreadable`() {
        // The Vehicle SDK can take seconds to start, and the app may start mid-drive.
        val drive = speeds(List(30) { 30f }) + belt(true, 3_000) + speeds(List(10) { 30f }, startMs = 3_000)
        assertEquals(listOf<DrivingEvent>(), detect(drive))
    }

    @Test
    fun `recorded CARLA drive`() {
        val samples = recordedCarlaDrive()
        val events = detect(samples)
        val startMs = samples.first().tNs / NS_PER_MS
        // CARLA's autopilot is harsher than the real-car figures (PLAN 6절): it pulls away at up to 22 km/h/s
        // (6.9 s; 50.2 s, where it eases off for 0.6 s and pulls again at 52.5 s; 82.5 s from 15 km/h) and stops at
        // full brake, 19 km/h to 0 in 0.1 s (35.6 s, same in the bridge log). At 96 s the bridge ends the drive.
        assertEquals(
            listOf(
                "BeltUnreadable" to 6_200L, "HardAccel" to 6_900L, "HardBrake" to 35_600L, "HardAccel" to 50_200L,
                "HardAccel" to 52_500L, "HardAccel" to 82_500L, "HardBrake" to 96_000L,
            ),
            events.map { it::class.simpleName to (it.tNs / NS_PER_MS - startMs) / 100 * 100 },
        )
    }
}

private fun detect(samples: List<Sample>): List<DrivingEvent> =
    samples.fold(EventDetector() to listOf<DrivingEvent>()) { (detector, events), sample ->
        detector.next(sample).let { (next, new) -> next to events + new }
    }.second
