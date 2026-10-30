package com.kyusun.pleos.signal

import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.TBTInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class NextTurnOfTest {
    @Test
    fun `the nearest turn ahead and its signal, past maneuvers that need none`() {
        val keep = TBTInfo(TurnType.TURN_KEEP_R, 40.0, "")
        assertEquals(Sample.NextTurn(7, TurnSignal.RIGHT, 80f), nextTurnOf(7, listOf(keep, TBTInfo(TurnType.TURN_RIGHT, 80.0, ""))))
        assertEquals(Sample.NextTurn(7, TurnSignal.LEFT, 25.5f), nextTurnOf(7, listOf(TBTInfo(TurnType.TURN_UTURN, 25.5, ""))))
        assertEquals(Sample.NextTurn(7, TurnSignal.LEFT, 12f), nextTurnOf(7, listOf(TBTInfo(TurnType.TURN_SHARP_L, 12.0, ""))))
    }

    @Test
    fun `no turn ahead, or none with a readable distance, is none`() {
        val none = Sample.NextTurn(7, TurnSignal.OFF, 0f)
        assertEquals(none, nextTurnOf(7, emptyList()))
        assertEquals(none, nextTurnOf(7, listOf(TBTInfo(TurnType.TURN_ARRIVE, 30.0, ""))))
        assertEquals(none, nextTurnOf(7, listOf(TBTInfo(TurnType.TURN_LEFT, Double.NaN, ""), TBTInfo(TurnType.TURN_RIGHT, -3.0, ""))))
    }
}
