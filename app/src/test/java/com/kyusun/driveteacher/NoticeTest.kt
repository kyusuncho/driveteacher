package com.kyusun.driveteacher

import com.kyusun.driveteacher.scoring.Disqualification
import com.kyusun.driveteacher.scoring.Penalty
import com.kyusun.driveteacher.scoring.RoadItem
import com.kyusun.driveteacher.scoring.RoadTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeTest {
    private val noSignal = Penalty.Deduction(1, RoadItem.TURN_NO_SIGNAL)
    private val hardBrake = Penalty.Deduction(2, RoadItem.HARD_BRAKE)
    private val speeding = Penalty.Disqualified(3, Disqualification.SPEEDING)

    @Test
    fun `pull-out items carry their section, the rest the annex label as is`() {
        assertEquals("출발 신호 안함", nameOf(RoadItem.START_NO_SIGNAL))
        assertEquals("출발 신호 계속", nameOf(RoadItem.START_SIGNAL_KEPT_ON))
        assertEquals("진로변경 신호 불이행", nameOf(RoadItem.TURN_NO_SIGNAL))
        assertEquals("출발 신호 안함, 5점 감점되었습니다.", speechOf(listOf(Penalty.Deduction(0, RoadItem.START_NO_SIGNAL))))
    }

    @Test
    fun `every item and disqualification has a reason`() {
        val all = RoadItem.entries.map { Penalty.Deduction(0, it) } + Disqualification.entries.map { Penalty.Disqualified(0, it) }
        all.forEach { assertTrue("no reason for $it", reasonOf(it).endsWith("요.")) }
        assertEquals(all.size, all.map(::reasonOf).toSet().size)
    }

    @Test
    fun `the result lists what the car could not give`() {
        assertEquals(listOf("좌석안전띠", "제한속도", "회전 신호 30~33"), unjudgedOf(Coverage()).map { it.first })
        assertEquals(emptyList<Pair<String, String>>(), unjudgedOf(Coverage(true, true, true)))
    }

    @Test
    fun `the clock reads minutes and seconds, hours past an hour`() {
        assertEquals("0:00", clockOf(0))
        assertEquals("4:07", clockOf(247_900_000_000))
        assertEquals("1:02:07", clockOf(3_727_000_000_000))
    }

    @Test
    fun `ending the test says the verdict`() {
        assertEquals("시험을 마쳤습니다. 86점, 합격입니다.", endSpeechOf(RoadTest(listOf(noSignal, hardBrake))))
        assertEquals("시험을 마쳤습니다. 실격입니다.", endSpeechOf(RoadTest(listOf(speeding))))
        assertEquals("시험을 마쳤습니다. 65점, 불합격입니다.", endSpeechOf(RoadTest(List(5) { hardBrake })))
    }

    @Test
    fun `speech reads every penalty of a moment, with km spelled out`() {
        assertEquals(
            "진로변경 신호 불이행, 7점 감점되었습니다. 제한속도 시속 10킬로미터 초과, 실격되었습니다.",
            speechOf(listOf(noSignal, speeding)),
        )
    }
}
