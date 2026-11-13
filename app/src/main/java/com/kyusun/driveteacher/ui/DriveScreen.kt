package com.kyusun.driveteacher.ui

import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.kyusun.driveteacher.NO_PENALTY
import com.kyusun.driveteacher.nameOf
import com.kyusun.driveteacher.scoring.Penalty
import com.kyusun.driveteacher.scoring.RoadTest

/** Which sign the score is on: guide while it passes, caution below the pass mark, prohibition when disqualified. */
internal enum class SignKind { GUIDE, CAUTION, PROHIBITION }

internal fun kindOf(test: RoadTest): SignKind = when {
    test.disqualified -> SignKind.PROHIBITION
    test.passed -> SignKind.GUIDE
    else -> SignKind.CAUTION
}

internal fun Ui.faceColors(kind: SignKind): Triple<Int, Int, Int> = when (kind) { // fill, line, ink
    SignKind.GUIDE -> Triple(palette.guide, palette.signInk, palette.signInk)
    SignKind.CAUTION -> Triple(palette.cautionPanel, palette.cautionLine, palette.cautionPanelInk)
    SignKind.PROHIBITION -> Triple(palette.outPanel, palette.outLine, palette.outPanelInk)
}

/**
 * While driving: one sign filling the screen, the score on the left and the last penalty on the right. Static, no
 * motion (UX restrictions NO_VIDEO), and nothing that tells the driver what to do next.
 */
internal fun driveScreen(ui: Ui, test: RoadTest): View {
    val kind = kindOf(test)
    val (fill, line, ink) = ui.faceColors(kind)
    val muted = ink and 0x00FFFFFF or 0xC8000000.toInt()
    val score: View = if (test.disqualified) {
        stopSign(ui, fill, line)
    } else {
        ui.row(
            ui.weight(ui.fill(test.score.toString(), ink, 700), 1f),
            ui.wrap(ui.text("점", Sp.TITLE * 1.4f, ui.faces.heavy, ink), Gravity.BOTTOM),
            gravity = Gravity.BOTTOM,
        ).apply { isBaselineAligned = true } // the unit sits on the numeral's baseline
    }
    return ui.screen(
        ui.sign(
            fill,
            line,
            ui.row(
                ui.weight(score, if (test.penalties.isEmpty()) 2.4f else 1.9f), // the right holds little before a penalty
                ui.rule(muted, thicknessDp = 4, vertical = true).apply {
                    (layoutParams as LinearLayout.LayoutParams).setMargins(ui.px(Dp.L), ui.px(Dp.M), ui.px(Dp.XL), ui.px(Dp.M))
                },
                ui.weight(lastPenalty(ui, test, kind, ink), 1f),
            ),
            padDp = Dp.XL,
        ),
    )
}

private fun lastPenalty(ui: Ui, test: RoadTest, kind: SignKind, ink: Int): View {
    val p = ui.palette
    val head: View? = when (kind) {
        SignKind.GUIDE -> null
        SignKind.CAUTION -> ui.row(
            ui.symbol(CautionSymbol(p.caution, p.danger, p.cautionInk), 88),
            ui.gap(Dp.M),
            ui.text("합격선 ${RoadTest.PASS_MARK}점 미만", Sp.HEADLINE, ui.faces.heavy, ink),
        )
        SignKind.PROHIBITION -> ui.text("실격", Sp.TITLE * 1.4f, ui.faces.heavy, ink)
    }
    val last = test.penalties.lastOrNull()
    val body: View = when (last) {
        null -> ui.text(NO_PENALTY, Sp.TITLE * 1.3f, ui.faces.heavy, ink)
        is Penalty.Disqualified -> ui.text(last.reason.label, Sp.TITLE, ui.faces.heavy, ink)
        is Penalty.Deduction -> ui.column(
            ui.wrap(ui.numberPlate(last.item.number, Sp.HEADLINE)),
            ui.full(ui.text(nameOf(last.item), Sp.TITLE, ui.faces.heavy, ink, lines = 2), topDp = Dp.M),
            ui.full(ui.text("−${last.item.points}점", Sp.TITLE * 1.6f, ui.faces.numerals, ink), topDp = Dp.S),
        )
    }
    return ui.column(
        head?.let { ui.full(it) },
        ui.weight(View(ui.context), 1f, vertical = true),
        ui.full(body),
        ui.weight(View(ui.context), 1f, vertical = true),
    )
}

/** Disqualified: a blank prohibition ring (통행금지) where the score was. The test is over; no number stands. */
internal fun stopSign(ui: Ui, fill: Int, ring: Int, insetDp: Int = Dp.M): View = View(ui.context).apply {
    background = InsetDrawable(RingSymbol(fill, ring), ui.px(insetDp))
    contentDescription = "실격"
}
