package com.kyusun.driveteacher.ui

import android.animation.ValueAnimator
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ScrollView
import com.kyusun.driveteacher.Exam
import com.kyusun.driveteacher.NO_PENALTY
import com.kyusun.driveteacher.nameOf
import com.kyusun.driveteacher.clockOf
import com.kyusun.driveteacher.reasonOf
import com.kyusun.driveteacher.scoring.Penalty
import com.kyusun.driveteacher.scoring.RoadTest
import com.kyusun.driveteacher.unjudgedOf

/**
 * Parked: the verdict sign on the left, every penalty on a blue guide sign on the right, read like a sign's
 * destination rows ("30 진로변경 신호 불이행 −7"), each with the teacher's reason under it. While the test still runs
 * (parked mid-test) the same screen offers to end it.
 */
internal fun resultScreen(ui: Ui, state: UiState, actions: Actions, reveal: Boolean): View {
    val p = ui.palette
    val exam = state.exam
    val running = state.stage == Stage.TESTING
    val verdict = verdictSign(ui, exam, running)
    val buttons = if (running) {
        ui.row(ui.weight(ui.button("시험 끝내기", p.route, p.signInk, onTap = actions::end), 1f))
    } else {
        ui.row(
            ui.weight(ui.button("다시 시험", p.route, p.signInk, onTap = actions::start), 1f),
            ui.gap(Dp.M),
            ui.weight(ui.button("홈", p.ground, p.groundInk, p.groundInkMuted, actions::home), 0.6f),
        )
    }
    val left = ui.column(
        ui.weight(verdict, 1f, vertical = true),
        ui.full(buttons.apply { minimumHeight = ui.px(Dp.TOUCH) }, topDp = Dp.M),
    )
    if (reveal && ValueAnimator.areAnimatorsEnabled()) hang(ui, verdict)
    val list = ScrollView(ui.context).apply {
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        // The bar runs in its own gutter, in the sign's ink, clear of the figures on the right.
        scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
        setPadding(0, 0, ui.px(Dp.M), 0)
        clipToPadding = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            verticalScrollbarThumbDrawable = plateFace(p.signInkMuted, ui.px(4).toFloat())
            verticalScrollbarTrackDrawable = null
        }
        addView(penaltyList(ui, state))
    }
    return ui.screen(
        ui.row(
            ui.weight(left, 0.95f),
            ui.gap(Dp.L),
            ui.weight(ui.sign(p.route, p.signInk, list), 1.35f),
            gravity = Gravity.FILL_VERTICAL,
        ),
    )
}

/** The one authored motion: the verdict sign is hung, dropping a little into place. */
private fun hang(ui: Ui, sign: View) {
    sign.alpha = 0f
    sign.translationY = -ui.px(Dp.L).toFloat()
    sign.animate().alpha(1f).translationY(0f).setDuration(420)
        .setInterpolator(PathInterpolator(0.16f, 1f, 0.3f, 1f)).start()
}

private fun verdictSign(ui: Ui, exam: Exam, running: Boolean): View {
    val test = exam.test
    val kind = kindOf(test)
    val (fill, line, ink) = ui.faceColors(kind)
    val muted = ink and 0x00FFFFFF or 0xC8000000.toInt()
    val word = when {
        running -> "채점 중"
        kind == SignKind.PROHIBITION -> "실격"
        kind == SignKind.GUIDE -> "합격"
        else -> "불합격"
    }
    val symbol = when (kind) {
        SignKind.GUIDE -> null
        SignKind.CAUTION -> ui.symbol(CautionSymbol(ui.palette.caution, ui.palette.danger, ui.palette.cautionInk), 64)
        SignKind.PROHIBITION -> null // the ring below says it
    }
    val lost = test.penalties.sumOf { if (it is Penalty.Deduction) it.item.points else 0 }
    val count = test.penalties.count { it is Penalty.Deduction }
    val facts = ui.column(
        fact(ui, "합격선", "${RoadTest.PASS_MARK}점", ink, muted),
        fact(ui, if (count == 0) "감점" else "감점 ${count}건", if (count == 0) "0점" else "−${lost}점", ink, muted),
        fact(ui, "주행 시간", clockOf(exam.durationNs), ink, muted),
    )
    val score: View = if (test.disqualified) stopSign(ui, fill, line, Dp.L) else ui.row(
        ui.weight(ui.fill(test.score.toString(), ink, 300), 1f),
        ui.wrap(ui.text("점", Sp.HEADLINE, ui.faces.heavy, ink), Gravity.BOTTOM),
        gravity = Gravity.BOTTOM,
    ).apply { isBaselineAligned = true } // the unit sits on the numeral's baseline
    return ui.sign(
        fill,
        line,
        ui.column(
            ui.row(symbol, symbol?.let { ui.gap(Dp.S) }, ui.text(word, Sp.TITLE, ui.faces.heavy, ink)),
            ui.weight(score, 1f, vertical = true),
            ui.rule(muted, topDp = Dp.M),
            ui.full(facts, topDp = Dp.S),
        ),
    )
}

/** One row of a sign: name on the left, the figure on the right. */
private fun fact(ui: Ui, name: String, value: String, ink: Int, muted: Int): View = ui.full(
    ui.row(
        ui.weight(ui.text(name, Sp.BODY, ui.faces.bold, muted), 1f, fillAcross = false),
        ui.text(value, Sp.LINE, ui.faces.numerals, ink),
    ).apply { setPadding(0, ui.px(Dp.XS), 0, ui.px(Dp.XS)) },
)

private fun penaltyList(ui: Ui, state: UiState): View {
    val p = ui.palette
    val exam = state.exam
    val penalties = exam.test.penalties
    val list = ui.column(
        ui.row(
            ui.weight(ui.text("감점 내역", Sp.HEADLINE, ui.faces.heavy, p.signInk), 1f, fillAcross = false),
            ui.text("${penalties.size}건", Sp.LINE, ui.faces.numerals, p.signInkMuted),
        ),
        ui.rule(p.signInkMuted, topDp = Dp.M),
    )
    if (penalties.isEmpty()) {
        list.addView(ui.full(ui.text(NO_PENALTY, Sp.TITLE, ui.faces.heavy, p.signInk), topDp = Dp.L))
        list.addView(ui.full(ui.text("채점한 항목에서 깎인 점수가 없어요.", Sp.BODY, ui.faces.medium, p.signInkMuted), topDp = Dp.S))
    }
    penalties.forEachIndexed { i, penalty ->
        if (i > 0) list.addView(ui.rule(0x40FFFFFF, topDp = Dp.M))
        list.addView(ui.full(penaltyRow(ui, exam, penalty), topDp = Dp.M))
    }
    val unjudged = unjudgedOf(exam.coverage)
    if (unjudged.isNotEmpty()) {
        list.addView(ui.rule(p.signInkMuted, topDp = Dp.L))
        list.addView(ui.full(ui.text("채점하지 못한 항목", Sp.LINE, ui.faces.heavy, p.signInk), topDp = Dp.M))
        unjudged.forEach { (item, why) ->
            list.addView(
                ui.full(
                    ui.row(
                        ui.plate("채점 안 함", p.work, p.workInk, Sp.LABEL),
                        ui.gap(Dp.S),
                        ui.weight(ui.text("$item  ·  $why", Sp.BODY, ui.faces.medium, p.signInkMuted), 1f, fillAcross = false),
                        gravity = Gravity.TOP,
                    ),
                    topDp = Dp.S,
                ),
            )
        }
    }
    if (state.simulated) list.addView(ui.wrap(ui.plate(SIMULATED, p.plate, p.plateInk, Sp.LABEL), topDp = Dp.L))
    return list
}

private fun penaltyRow(ui: Ui, exam: Exam, penalty: Penalty): View {
    val p = ui.palette
    val badge = when (penalty) {
        is Penalty.Deduction -> ui.numberPlate(penalty.item.number)
        is Penalty.Disqualified -> ui.symbol(RingSymbol(p.plate, p.danger), 52)
    }
    val (label, cost) = when (penalty) {
        is Penalty.Deduction -> nameOf(penalty.item) to "−${penalty.item.points}"
        is Penalty.Disqualified -> penalty.reason.label to "실격"
    }
    val words = ui.column(
        ui.text(label, Sp.LINE, ui.faces.heavy, p.signInk),
        ui.full(ui.text(reasonOf(penalty), Sp.BODY, ui.faces.medium, p.signInkMuted), topDp = Dp.XS),
    )
    return ui.row(
        ui.text(clockOf(exam.sinceStartNs(penalty)), Sp.LABEL, ui.faces.numeralsLight, p.signInkMuted).apply {
            minWidth = ui.px(88)
        },
        badge,
        ui.gap(Dp.M),
        ui.weight(words, 1f, fillAcross = false),
        ui.gap(Dp.M),
        ui.text(cost, Sp.HEADLINE, if (penalty is Penalty.Deduction) ui.faces.numerals else ui.faces.heavy, p.signInk),
        gravity = Gravity.TOP,
    )
}
