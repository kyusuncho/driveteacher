package com.kyusun.driveteacher

import com.kyusun.driveteacher.scoring.Penalty
import com.kyusun.driveteacher.scoring.RoadTest

/**
 * What the driver sees and hears about the road test (PLAN 3절). Short enough to read at a glance, and only what
 * already happened: never what to do next (Pleos policy: nothing that prompts the driver's hands or feet).
 */
internal fun headlineOf(test: RoadTest): String = when {
    test.disqualified -> "실격"
    test.passed -> "${test.score}점"
    else -> "${test.score}점 · 불합격"
}

/** One line for one penalty: "진로변경 신호 불이행 −7점". */
internal fun lineOf(p: Penalty): String = when (p) {
    is Penalty.Deduction -> "${p.item.label} −${p.item.points}점"
    is Penalty.Disqualified -> "${p.reason.label} · 실격"
}

/** Every penalty so far, one per line, for the parked screen. */
internal fun historyOf(penalties: List<Penalty>): String =
    if (penalties.isEmpty()) NO_PENALTY else penalties.joinToString("\n", transform = ::lineOf)

/** "진로변경 신호 불이행, 7점 감점되었습니다." for each penalty, in order. */
internal fun speechOf(penalties: List<Penalty>): String = penalties.joinToString(" ") {
    when (it) {
        is Penalty.Deduction -> "${it.item.label}, ${it.item.points}점 감점되었습니다."
        is Penalty.Disqualified -> "${it.reason.label}, 실격되었습니다."
    }
}.replace("km", "킬로미터")

internal const val NO_PENALTY = "감점 없음"
