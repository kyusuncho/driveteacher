package com.kyusun.driveteacher.ui

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.kyusun.driveteacher.scoring.RoadItem
import com.kyusun.driveteacher.scoring.RoadTest

/** What the road test judges, grouped as a sign lists destinations: a name, then its numbers. */
private val GROUPS = listOf(
    "출발할 때 신호" to listOf(RoadItem.START_NO_SIGNAL, RoadItem.START_SIGNAL_OFF_EARLY, RoadItem.START_SIGNAL_KEPT_ON),
    "회전할 때 신호" to listOf(
        RoadItem.TURN_NO_SIGNAL,
        RoadItem.TURN_SIGNAL_LATE,
        RoadItem.TURN_SIGNAL_NOT_KEPT,
        RoadItem.TURN_SIGNAL_NOT_OFF,
    ),
    "정지와 기어" to listOf(RoadItem.GEAR_NOT_NEUTRAL, RoadItem.NEUTRAL_BEFORE_STOP, RoadItem.NO_BRAKE_WHEN_STOPPED),
    "가속과 제동" to listOf(RoadItem.HARD_START, RoadItem.HARD_BRAKE),
)

/** Before the test: what it judges on a guide sign, and the start control as a 지시표지 (do this). */
internal fun startScreen(ui: Ui, state: UiState, actions: Actions): View {
    val p = ui.palette
    val list = ui.column(
        ui.text("도로주행 시험", Sp.TITLE, ui.faces.heavy, p.signInk),
        ui.full(
            ui.text(
                "100점에서 감점하고, 실격 없이 ${RoadTest.PASS_MARK}점 이상이면 합격이에요.",
                Sp.BODY,
                ui.faces.medium,
                p.signInkMuted,
            ),
            topDp = Dp.S,
        ),
        ui.rule(p.signInkMuted, topDp = Dp.L),
    )
    // The groups spread down the sign as its destination rows do, an equal space above each.
    GROUPS.forEach { (name, items) ->
        list.addView(ui.weight(View(ui.context), 1f, vertical = true))
        list.addView(ui.full(groupRow(ui, name, items.map { ui.numberPlate(it.number, Sp.HEADLINE * 0.8f) })))
    }
    val outs = listOf("제한속도 +10km/h 초과", "안전띠 미착용").map { ui.plate(it, p.plate, p.plateInk, Sp.BODY, p.danger) }
    list.addView(ui.weight(View(ui.context), 1f, vertical = true))
    list.addView(ui.full(groupRow(ui, "바로 실격", outs)))
    list.addView(ui.weight(View(ui.context), 0.5f, vertical = true))

    val blocked = when {
        !state.canScore -> "차속 권한이 없어 시험을 시작할 수 없어요"
        state.driving -> "주행 중에는 시험을 시작할 수 없어요"
        else -> null
    }
    val start = ui.column(
        ui.symbol(AheadSymbol(p.route, p.signInk), 260).apply { alpha = if (blocked == null) 1f else 0.35f },
        ui.wrap(ui.text("시험 시작", Sp.TITLE, ui.faces.heavy, p.groundInk, Gravity.CENTER), Gravity.CENTER_HORIZONTAL, Dp.M),
        gravity = Gravity.CENTER_HORIZONTAL,
    ).apply { setPadding(ui.px(Dp.L), ui.px(Dp.M), ui.px(Dp.L), ui.px(Dp.M)) }
    if (blocked == null) ui.tappable(start, "시험 시작", radiusDp = 40, onTap = actions::start)

    val notes = ui.column(gravity = Gravity.CENTER_HORIZONTAL)
    val note = blocked ?: "주차한 자리에서 출발하는 것부터 채점해요"
    notes.addView(ui.wrap(ui.plate(note, p.plate, p.plateInk, Sp.LABEL, p.groundInkMuted), Gravity.CENTER_HORIZONTAL))
    if (!state.canScore) {
        notes.addView(ui.wrap(ui.button("권한 요청", p.route, p.signInk, onTap = actions::askPermission), Gravity.CENTER_HORIZONTAL, Dp.S))
    }
    if (state.simulated) {
        notes.addView(ui.wrap(ui.plate(SIMULATED, p.plate, p.plateInk, Sp.LABEL, p.groundInkMuted), Gravity.CENTER_HORIZONTAL, Dp.S))
    }

    val side = ui.column(
        ui.wrap(ui.button("홈", p.ground, p.groundInk, p.groundInkMuted, actions::home), Gravity.START),
        ui.weight(View(ui.context), 1f, vertical = true),
        ui.wrap(start, Gravity.CENTER_HORIZONTAL),
        ui.weight(View(ui.context), 1f, vertical = true),
        ui.full(notes),
    )
    return ui.screen(
        ui.row(
            ui.weight(ui.sign(p.guide, p.signInk, list), 1.45f),
            ui.gap(Dp.L),
            ui.weight(side, 1f),
            gravity = Gravity.FILL_VERTICAL,
        ),
    )
}

private fun groupRow(ui: Ui, name: String, plates: List<View>): View {
    val row = ui.row(ui.weight(ui.text(name, Sp.HEADLINE * 0.85f, ui.faces.bold, ui.palette.signInk), 1f, fillAcross = false))
    plates.forEach {
        row.addView(it.apply { layoutParams = LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginStart = ui.px(Dp.XS + 4) } })
    }
    return row
}

internal const val SIMULATED = "개발용 빌드\n안전띠·제한속도·회전 안내는 시뮬레이터 값"
