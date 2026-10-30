package com.kyusun.pleos

import com.kyusun.pleos.scoring.Disqualification
import com.kyusun.pleos.scoring.Penalty
import com.kyusun.pleos.scoring.RoadItem
import com.kyusun.pleos.scoring.RoadTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NoticeTest {
    private val noSignal = Penalty.Deduction(1, RoadItem.TURN_NO_SIGNAL)
    private val hardBrake = Penalty.Deduction(2, RoadItem.HARD_BRAKE)
    private val speeding = Penalty.Disqualified(3, Disqualification.SPEEDING)

    @Test
    fun `headline is the score, failing below 70, or the disqualification`() {
        assertEquals("100점", headlineOf(RoadTest()))
        assertEquals("86점", headlineOf(RoadTest(listOf(noSignal, hardBrake))))
        val fiveBrakes = List(5) { hardBrake }
        assertEquals("65점 · 불합격", headlineOf(RoadTest(fiveBrakes)))
        assertEquals("실격", headlineOf(RoadTest(listOf(noSignal, speeding))))
    }

    @Test
    fun `a line says what happened and what it cost`() {
        assertEquals("진로변경 신호 불이행 −7점", lineOf(noSignal))
        assertEquals("제한속도 시속 10km 초과 · 실격", lineOf(speeding))
        assertEquals("감점 없음", historyOf(emptyList()))
        assertEquals("진로변경 신호 불이행 −7점\n급브레이크 사용 −7점", historyOf(listOf(noSignal, hardBrake)))
    }

    @Test
    fun `speech reads every penalty of a moment, with km spelled out`() {
        assertEquals(
            "진로변경 신호 불이행, 7점 감점되었습니다. 제한속도 시속 10킬로미터 초과, 실격되었습니다.",
            speechOf(listOf(noSignal, speeding)),
        )
    }
}
