package com.kyusun.driveteacher

import com.kyusun.driveteacher.scoring.Penalty
import com.kyusun.driveteacher.scoring.RoadItem
import com.kyusun.driveteacher.signal.Sample
import com.kyusun.driveteacher.signal.TurnSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamTest {
    private fun run(vararg samples: Sample): Exam = samples.fold(Exam()) { exam, s -> exam.next(s).first }

    @Test
    fun `the span runs from the earliest to the latest sample, whatever order they came in`() {
        val exam = run(Sample.Speed(5_000, 0f, 5_000), Sample.Brake(3_000, true), Sample.Speed(9_000, 0f, 9_000))
        assertEquals(3_000L, exam.firstNs)
        assertEquals(6_000L, exam.durationNs)
        assertEquals(4_000L, exam.sinceStartNs(Penalty.Deduction(7_000, RoadItem.HARD_BRAKE)))
        assertEquals(0L, Exam().durationNs)
    }

    @Test
    fun `coverage notes a belt read buckled, a known limit and any turn guidance`() {
        assertEquals(Coverage(), run(Sample.SeatBelt(1, false), Sample.SpeedLimit(2, 0)).coverage)
        val seen = run(Sample.SeatBelt(1, true), Sample.SpeedLimit(2, 50), Sample.NextTurn(3, TurnSignal.OFF, 0f))
        assertEquals(Coverage(beltRead = true, limitRead = true, turnsGuided = true), seen.coverage)
        // Once read, a later unbuckled belt or unknown limit does not take it back.
        val later = seen.next(Sample.SeatBelt(4, false)).first.next(Sample.SpeedLimit(5, 0)).first
        assertTrue(later.coverage.beltRead && later.coverage.limitRead)
    }

    @Test
    fun `penalties pass through from the road test`() {
        val (exam, new) = Exam().next(Sample.Speed(1, 0f, 1))
        assertEquals(emptyList<Penalty>(), new)
        assertFalse(exam.test.disqualified)
    }
}
