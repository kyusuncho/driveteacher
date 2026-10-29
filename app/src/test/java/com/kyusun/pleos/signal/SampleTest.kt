package com.kyusun.pleos.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The recording format is a contract with tools/carla/compare_signals.py and, line by line, with bridge.py (③). */
class SampleTest {
    private val camera = SpeedCamera("6110000-12", SpeedCameraType.SPEED_CAMERA_TRAFFIC_LIGHT, 50, 230, false)
    private val school = SpeedCamera("6110000-7", SpeedCameraType.SPEED_CAMERA_SCHOOL_ZONE, 30, 480, true)

    private val samples = listOf(
        Sample.Speed(1_234_000_000, 13.5f, 1_239_000_000),
        Sample.Gear(2_000_000, GearPosition.DRIVE),
        Sample.Brake(3_000_000, true),
        Sample.Turn(4_000_000, TurnSignal.LEFT),
        Sample.Steering(5_000_000, -90f),
        Sample.SeatBelt(6_000_000, false),
        Sample.SpeedLimit(7_000_000, 50),
        Sample.Location(8_000_000, 37.5665, 126.978),
        Sample.SpeedCameras(9_000_000, listOf(camera, school)),
        Sample.SpeedCameras(10_000_000, emptyList()),
    )

    @Test
    fun `csv line is time in ms, kind and values`() {
        assertEquals(
            listOf(
                "1234,speed,13.5,5", "2,gear,DRIVE", "3,brake,true", "4,turn,LEFT", "5,steering,-90.0", "6,belt,false",
                "7,limit,50", "8,loc,37.5665,126.978",
                "9,cameras,6110000-12/SPEED_CAMERA_TRAFFIC_LIGHT/50/230/false;6110000-7/SPEED_CAMERA_SCHOOL_ZONE/30/480/true",
                "10,cameras,",
            ),
            samples.map { it.toCsv() },
        )
    }

    @Test
    fun `a recording line reads back as the same sample`() {
        assertEquals(samples, samples.map { parseSample(it.toCsv()) })
    }

    @Test
    fun `a simulator line has no time and is stamped on receipt`() {
        assertEquals(Sample.SpeedLimit(42, 30), simSample("limit,30", 42))
        assertEquals(Sample.SeatBelt(42, true), simSample("belt,true", 42))
        assertEquals(Sample.SpeedCameras(42, listOf(camera)), simSample("cameras,${camera.toCsv()}", 42))
    }

    @Test
    fun `unknown or broken lines are dropped, not guessed`() {
        listOf(
            "# wall_ms=1 t_ms=2", "", "7,limit", "7,limit,fast", "x,limit,50", "7,horn,true", "7,gear,SPORT",
            "9,cameras,1/NO_SUCH_TYPE/50/230/false", "9,cameras,1/SPEED_CAMERA_FIX/50",
        ).forEach { assertNull(it, parseSample(it)) }
    }
}
