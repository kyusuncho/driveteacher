package com.kyusun.pleos.signal

import org.junit.Assert.assertEquals
import org.junit.Test

/** The recording format is a contract with tools/carla/compare_signals.py. */
class SampleTest {
    @Test
    fun `csv line is time in ms, kind and values`() {
        val lines = listOf(
            Sample.Speed(1_234_567_890, 13.5f, 1_240_000_000),
            Sample.Gear(2_000_000, GearPosition.DRIVE),
            Sample.Brake(3_000_000, true),
            Sample.Turn(4_000_000, TurnSignal.LEFT),
            Sample.Steering(5_000_000, -90f),
            Sample.SeatBelt(6_000_000, false),
            Sample.SpeedLimit(7_000_000, 50),
            Sample.Location(8_999_999, 37.5665, 126.978),
        ).map { it.toCsv() }

        assertEquals(
            listOf(
                "1234,speed,13.5,5","2,gear,DRIVE", "3,brake,true", "4,turn,LEFT",
                "5,steering,-90.0", "6,belt,false", "7,limit,50", "8,loc,37.5665,126.978",
            ),
            lines,
        )
    }
}
