package com.kyusun.pleos.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverridesTest {
    @Test
    fun `the car's value of a kind passes until the simulator sends that kind, then the simulator's wins`() {
        val overrides = Overrides()
        val carBelt = Sample.SeatBelt(1, false)
        assertEquals(carBelt, overrides.fromCar(carBelt))

        val simBelt = Sample.SeatBelt(2, true)
        assertEquals(simBelt, overrides.fromSim(simBelt))

        assertNull(overrides.fromCar(Sample.SeatBelt(3, false)))
        val carSpeed = Sample.Speed(4, 10f, 4)
        assertEquals("other kinds still come from the car", carSpeed, overrides.fromCar(carSpeed))
    }
}
