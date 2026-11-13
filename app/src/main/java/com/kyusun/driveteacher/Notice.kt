package com.kyusun.driveteacher

import com.kyusun.driveteacher.scoring.Disqualification
import com.kyusun.driveteacher.scoring.Penalty
import com.kyusun.driveteacher.scoring.RoadItem
import com.kyusun.driveteacher.scoring.RoadTest

/**
 * What the driver sees and hears about the road test (PLAN 3절). While driving: short enough to read at a glance,
 * and only what already happened, never what to do next (Pleos policy: nothing that prompts the driver's hands or
 * feet). Parked, the result explains each penalty the way a teacher would ([reasonOf]).
 */
internal fun nameOf(item: RoadItem): String = when (item) {
    // The annex lists 11~13 under 출발; alone, "신호 안함" could be any signal.
    RoadItem.START_NO_SIGNAL, RoadItem.START_SIGNAL_OFF_EARLY, RoadItem.START_SIGNAL_KEPT_ON -> "출발 ${item.label}"
    else -> item.label
}

/** "진로변경 신호 불이행, 7점 감점되었습니다." for each penalty, in order. */
internal fun speechOf(penalties: List<Penalty>): String = penalties.joinToString(" ") {
    when (it) {
        is Penalty.Deduction -> "${nameOf(it.item)}, ${it.item.points}점 감점되었습니다."
        is Penalty.Disqualified -> "${it.reason.label}, 실격되었습니다."
    }
}.spoken()

/** Said when the driver ends the test: "시험을 마쳤습니다. 82점, 합격입니다." */
internal fun endSpeechOf(test: RoadTest): String = "시험을 마쳤습니다. " + when {
    test.disqualified -> "실격입니다."
    test.passed -> "${test.score}점, 합격입니다."
    else -> "${test.score}점, 불합격입니다."
}

/** Why a penalty was given, for the parked result: what happened, and the rule it broke. */
internal fun reasonOf(p: Penalty): String = when (p) {
    is Penalty.Deduction -> reasonOf(p.item)
    is Penalty.Disqualified -> reasonOf(p.reason)
}

private fun reasonOf(item: RoadItem): String = when (item) {
    RoadItem.GEAR_NOT_NEUTRAL -> "10초 넘게 서 있는 동안 기어가 N이 아니었어요. 오래 설 때는 중립에 둬야 해요."
    RoadItem.HARD_START -> "출발하면서 1초에 시속 9.7km 넘게 빨라졌어요. 급출발로 봐요."
    RoadItem.START_NO_SIGNAL -> "주차한 자리에서 출발하면서 왼쪽 방향지시등을 켜지 않았어요."
    RoadItem.START_SIGNAL_OFF_EARLY -> "출발 신호를 차로에 들어서기 전에 껐어요. 차로에 들어설 때까지 켜 둬야 해요."
    RoadItem.START_SIGNAL_KEPT_ON -> "차로에 들어선 뒤에도 출발 신호가 켜져 있었어요."
    RoadItem.TURN_NO_SIGNAL -> "회전하는 동안 방향지시등을 켜지 않았어요."
    RoadItem.TURN_SIGNAL_LATE -> "회전 30m 전을 지나서야 방향지시등을 켰어요. 30m 전부터 켜야 해요."
    RoadItem.TURN_SIGNAL_NOT_KEPT -> "방향지시등을 켰다가 회전을 마치기 전에 껐어요."
    RoadItem.TURN_SIGNAL_NOT_OFF -> "회전을 마치고 30m를 지나서도 방향지시등이 켜져 있었어요."
    RoadItem.NEUTRAL_BEFORE_STOP -> "차가 완전히 서기 전에 기어를 N으로 바꿨어요."
    RoadItem.NO_BRAKE_WHEN_STOPPED -> "서 있는 동안 3초 넘게 브레이크를 밟지 않았어요."
    RoadItem.HARD_BRAKE -> "1초에 시속 10.8km 넘게 속도를 줄였어요. 급브레이크로 봐요."
}

private fun reasonOf(reason: Disqualification): String = when (reason) {
    Disqualification.SPEEDING -> "제한속도보다 시속 10km 넘게 빨리 달렸어요. 그 자리에서 시험이 끝나요."
    Disqualification.NO_SEAT_BELT -> "좌석안전띠를 매지 않고 달렸어요. 그 자리에서 시험이 끝나요."
}

/** What the test could not judge, as (item, why) for the result. Empty when the car gave every signal. */
internal fun unjudgedOf(coverage: Coverage): List<Pair<String, String>> = listOfNotNull(
    ("좌석안전띠" to "이 차에서 안전띠 상태를 읽지 못해 채점하지 않았어요.").takeUnless { coverage.beltRead },
    ("제한속도" to "제한속도를 받지 못해 과속을 채점하지 않았어요.").takeUnless { coverage.limitRead },
    ("회전 신호 30~33" to "내비 회전 안내가 없어 채점하지 않았어요.").takeUnless { coverage.turnsGuided },
)

/** Time into the test: "4:07", or "1:02:07" past an hour. */
internal fun clockOf(ns: Long): String {
    val s = (ns / 1_000_000_000).coerceAtLeast(0)
    val (h, m, sec) = Triple(s / 3600, s / 60 % 60, s % 60)
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun String.spoken() = replace("km", "킬로미터")

internal const val NO_PENALTY = "감점 없음"
