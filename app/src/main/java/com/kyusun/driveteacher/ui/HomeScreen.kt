package com.kyusun.driveteacher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.kyusun.driveteacher.Exam
import com.kyusun.driveteacher.scoring.RoadTest

/** Where the app is in one launch. One road test at a time; the last finished one stays on the home screen. */
internal enum class Stage { HOME, READY, TESTING, DONE }

internal data class UiState(
    val stage: Stage = Stage.HOME,
    val exam: Exam = Exam(),
    val driving: Boolean = true, // until the UX restrictions are read: the driving screen is the safe one
    val canScore: Boolean = true, // the speed permission
    val lastResult: RoadTest? = null,
    val simulated: Boolean = false, // debug build: the simulator channel supplies belt, limit and turns
)

internal interface Actions {
    fun openRoadTest()
    fun start()
    fun end()
    fun home()
    fun askPermission()
}

private const val ROAD_TEST_WEIGHT = 1.6f
private val LANES = floatArrayOf(ROAD_TEST_WEIGHT, 1f, 1f)
private const val LANE_GAP_DP = Dp.M

/** A gantry over a three-lane road: each sign is a destination, the first one is the road test. */
internal fun homeScreen(ui: Ui, state: UiState, actions: Actions): View {
    val p = ui.palette
    val header = ui.row(
        ui.text("운전선생", Sp.HEADLINE, ui.faces.heavy, p.groundInk),
        ui.weight(View(ui.context), 1f),
        state.lastResult?.let { ui.plate("지난 시험  ${verdictLine(it)}", p.plate, p.plateInk, Sp.LABEL, p.groundInkMuted) },
    )
    val signs = ui.row(
        ui.weight(roadTestSign(ui, state, actions), LANES[0]),
        ui.gap(LANE_GAP_DP),
        ui.weight(laterSign(ui, "장내기능 시험", "코스를 달리는 기능 시험 채점"), LANES[1]),
        ui.gap(LANE_GAP_DP),
        ui.weight(laterSign(ui, "운전 습관 점수", "평소 운전의 급가속·과속 기록"), LANES[2]),
        gravity = Gravity.TOP,
    )
    val beam = View(ui.context).apply { setBackgroundColor(p.beam) }
    return ui.screen(
        ui.column(
            ui.full(header),
            beam.apply { layoutParams = LinearLayout.LayoutParams(Ui.MATCH, ui.px(14)).apply { topMargin = ui.px(Dp.L) } },
            ui.weight(signs, 1.5f, vertical = true),
            ui.weight(RoadView(ui.context, p, ui.px(LANE_GAP_DP).toFloat()), 1f, vertical = true).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = ui.px(Dp.L)
            },
        ),
    )
}

private fun verdictLine(test: RoadTest): String = when {
    test.disqualified -> "실격"
    test.passed -> "${test.score}점 · 합격"
    else -> "${test.score}점 · 불합격"
}

private fun hangers(ui: Ui): View = ui.row(
    ui.weight(View(ui.context), 1f),
    View(ui.context).apply {
        setBackgroundColor(ui.palette.beam)
        layoutParams = LinearLayout.LayoutParams(ui.px(10), ui.px(Dp.M))
    },
    ui.weight(View(ui.context), 2f),
    View(ui.context).apply {
        setBackgroundColor(ui.palette.beam)
        layoutParams = LinearLayout.LayoutParams(ui.px(10), ui.px(Dp.M))
    },
    ui.weight(View(ui.context), 1f),
)

private fun roadTestSign(ui: Ui, state: UiState, actions: Actions): View {
    val p = ui.palette
    // The items it scores, as a sign shows route numbers beside a destination.
    val numbers = ui.column()
    ITEM_NUMBERS.chunked(ITEM_NUMBERS.size / 2).forEachIndexed { i, line ->
        val row = ui.row()
        line.forEach { n ->
            row.addView(ui.numberPlate(n, Sp.BODY).apply {
                layoutParams = LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { marginEnd = ui.px(Dp.XS) }
            })
        }
        numbers.addView(ui.full(row, topDp = if (i == 0) 0 else Dp.XS))
    }
    val face = ui.column(
        ui.text("도로주행 시험", Sp.TITLE * 1.3f, ui.faces.heavy, p.signInk),
        ui.full(ui.text("별표26 채점표로 12개 항목과 실격 2개를 봐요", Sp.BODY, ui.faces.medium, p.signInkMuted), topDp = Dp.S),
        ui.full(numbers, topDp = Dp.L),
        ui.weight(View(ui.context), 1f, vertical = true),
        ui.row(
            ui.symbol(ArrowSymbol(p.signInk), 72),
            ui.gap(Dp.M),
            ui.text(if (state.driving) "주행 중에는 시작할 수 없어요" else "시험 보러 가기", Sp.HEADLINE, ui.faces.heavy, p.signInk),
        ),
    )
    val sign = ui.sign(p.guide, p.signInk, face)
    if (!state.driving) ui.tappable(sign, "도로주행 시험 보러 가기", onTap = actions::openRoadTest)
    return ui.column(ui.full(hangers(ui)), ui.weight(sign, 1f, vertical = true))
}

/** A destination not built yet: a blue sign with no arrow, an orange work-zone plate where the arrow would be. */
private fun laterSign(ui: Ui, title: String, about: String): View {
    val p = ui.palette
    val face = ui.column(
        ui.text(title, Sp.HEADLINE, ui.faces.heavy, p.signInk),
        ui.full(ui.text(about, Sp.LABEL, ui.faces.medium, p.signInkMuted), topDp = Dp.S),
        ui.weight(View(ui.context), 1f, vertical = true),
        ui.wrap(ui.plate("준비 중", p.work, p.workInk, Sp.LINE)),
    )
    return ui.column(ui.full(hangers(ui)), ui.weight(ui.sign(p.route, p.signInk, face, padDp = Dp.M + 8), 1f, vertical = true))
}

private val ITEM_NUMBERS = listOf(4, 9, 11, 12, 13, 18, 20, 21, 30, 31, 32, 33)

/** The road under the gantry: asphalt, edge lines, dashed lane lines under the gaps, a lane arrow per sign. */
private class RoadView(context: Context, private val palette: Palette, private val gapPx: Float) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radius = 22f * resources.displayMetrics.density
    private val line = resources.displayMetrics.density * 6
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = line
        color = palette.roadPaint
        pathEffect = DashPathEffect(floatArrayOf(line * 6, line * 5), 0f)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = palette.road
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, paint)
        paint.color = palette.roadPaint
        canvas.drawRect(0f, 0f, w, line, paint)
        canvas.drawRect(0f, h - line, w, h, paint)
        // Lane edges match the signs above: the same weights and gaps.
        val usable = w - gapPx * (LANES.size - 1)
        var x = 0f
        LANES.forEachIndexed { i, weight ->
            val laneW = usable * weight / LANES.sum()
            val cx = x + laneW / 2
            paint.style = Paint.Style.FILL
            paint.color = palette.roadPaint
            paint.alpha = if (i == 0) 255 else 110
            canvas.drawPath(arrow(cx, h / 2, h * 0.52f), paint)
            paint.alpha = 255
            x += laneW
            if (i < LANES.size - 1) {
                val lx = x + gapPx / 2
                canvas.drawLine(lx, line * 3, lx, h - line * 3, dashed)
                x += gapPx
            }
        }
    }
}
