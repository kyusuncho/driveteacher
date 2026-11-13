package com.kyusun.driveteacher.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import android.os.Build

/**
 * The road-sign world (.impeccable surface brief): guide signs carry the score, a caution sign means below the pass
 * mark, a prohibition sign means disqualified, an orange work-zone plate means not built yet. The ground is concrete
 * by day and asphalt by night; the signs keep their colours, slightly dimmed at night so they do not glare.
 */
internal data class Palette(
    val ground: Int,
    val groundInk: Int,
    val groundInkMuted: Int,
    val road: Int,
    val roadPaint: Int,
    val beam: Int,
    val guide: Int, // 안내표지 green: the score while it passes
    val route: Int, // 안내표지 blue: lists and other destinations
    val signInk: Int,
    val signInkMuted: Int, // white over the sign's own colour, never grey
    val plate: Int, // 보조표지
    val plateInk: Int,
    val caution: Int, // 주의표지: below the pass mark
    val cautionInk: Int,
    val danger: Int, // 규제표지 ring: disqualified
    val work: Int, // 작업구역: not built yet
    val workInk: Int,
    // The score's sign below the pass mark and when disqualified. By day the sign itself (yellow; white with a red
    // ring); by night a dark panel carries the state in its line and symbol, so the screen never floods bright.
    val cautionPanel: Int,
    val cautionLine: Int,
    val cautionPanelInk: Int,
    val outPanel: Int,
    val outLine: Int,
    val outPanelInk: Int,
    val focus: Int,
    val ripple: Int,
) {
    companion object {
        val DAY = Palette(
            ground = 0xFFE4E6E2.toInt(),
            groundInk = 0xFF15191A.toInt(),
            groundInkMuted = 0xFF4A524F.toInt(),
            road = 0xFF2B2F2F.toInt(),
            roadPaint = 0xFFF2F2EE.toInt(),
            beam = 0xFF6E7673.toInt(),
            guide = 0xFF0B6B47.toInt(),
            route = 0xFF0A4FA0.toInt(),
            signInk = 0xFFFFFFFF.toInt(),
            signInkMuted = 0xC8FFFFFF.toInt(),
            plate = 0xFFFFFFFF.toInt(),
            plateInk = 0xFF121414.toInt(),
            caution = 0xFFF6C200.toInt(),
            cautionInk = 0xFF121414.toInt(),
            danger = 0xFFD2232A.toInt(),
            work = 0xFFF08C00.toInt(),
            workInk = 0xFF121414.toInt(),
            cautionPanel = 0xFFF6C200.toInt(),
            cautionLine = 0xFF121414.toInt(),
            cautionPanelInk = 0xFF121414.toInt(),
            outPanel = 0xFFFFFFFF.toInt(),
            outLine = 0xFFD2232A.toInt(),
            outPanelInk = 0xFF121414.toInt(),
            focus = 0xFFF6C200.toInt(),
            ripple = 0x40FFFFFF,
        )
        val NIGHT = Palette(
            ground = 0xFF101312.toInt(),
            groundInk = 0xFFE3E7E4.toInt(),
            groundInkMuted = 0xFF9BA49F.toInt(),
            road = 0xFF1B1E1E.toInt(),
            roadPaint = 0xFFB9BDB8.toInt(),
            beam = 0xFF3A403E.toInt(),
            guide = 0xFF075438.toInt(),
            route = 0xFF093E7E.toInt(),
            signInk = 0xFFE8ECE9.toInt(),
            signInkMuted = 0xB8E8ECE9.toInt(),
            plate = 0xFFD9DDD9.toInt(),
            plateInk = 0xFF101212.toInt(),
            caution = 0xFFD8AA00.toInt(),
            cautionInk = 0xFF101212.toInt(),
            danger = 0xFFC2262C.toInt(),
            work = 0xFFCF7A00.toInt(),
            workInk = 0xFF101212.toInt(),
            cautionPanel = 0xFF231D06.toInt(),
            cautionLine = 0xFFD8AA00.toInt(),
            cautionPanelInk = 0xFFEACB5A.toInt(),
            outPanel = 0xFF240D0E.toInt(),
            outLine = 0xFFC2262C.toInt(),
            outPanelInk = 0xFFECE4E3.toInt(),
            focus = 0xFFD8AA00.toInt(),
            ripple = 0x33FFFFFF,
        )

        fun of(context: Context): Palette {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return if (night == Configuration.UI_MODE_NIGHT_YES) NIGHT else DAY
        }
    }
}

/**
 * Overpass (Highway Gothic's descendant, OFL) for numerals; Gothic A1 (OFL) for Hangul. Overpass has no Hangul, so
 * the units in a figure ("−7점", "3건") fall back to Gothic A1, not to the system font.
 */
internal class Faces(context: Context) {
    private val assets = context.assets
    val medium: Typeface = Typeface.createFromAsset(assets, "fonts/GothicA1-Medium.ttf")
    val bold: Typeface = Typeface.createFromAsset(assets, "fonts/GothicA1-Bold.ttf")
    val heavy: Typeface = Typeface.createFromAsset(assets, "fonts/GothicA1-ExtraBold.ttf")
    val numerals: Typeface = numerals(800, "fonts/GothicA1-ExtraBold.ttf")
    val numeralsLight: Typeface = numerals(600, "fonts/GothicA1-Bold.ttf")

    private fun numerals(weight: Int, hangul: String): Typeface {
        val wght = "'wght' $weight"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Typeface.Builder(assets, "fonts/Overpass.ttf").setFontVariationSettings(wght).build()
        }
        val figures = Font.Builder(assets, "fonts/Overpass.ttf").setFontVariationSettings(wght).setWeight(weight).build()
        val units = Font.Builder(assets, hangul).setWeight(weight).build()
        return Typeface.CustomFallbackBuilder(FontFamily.Builder(figures).build())
            .addCustomFallback(FontFamily.Builder(units).build())
            .setStyle(FontStyle(weight, FontStyle.FONT_SLANT_UPRIGHT))
            .build()
    }
}

/** Type roles in sp (AAOS: body no smaller than 24). The score numeral fills its box instead (autosize). */
internal object Sp {
    const val TITLE = 56f
    const val HEADLINE = 44f
    const val LINE = 32f
    const val BODY = 26f
    const val LABEL = 24f
}

/** Spacing in dp: tight inside a group, wide between groups. */
internal object Dp {
    const val XS = 8
    const val S = 16
    const val M = 24
    const val L = 40
    const val XL = 64
    const val SIGN_RADIUS = 22
    const val PLATE_RADIUS = 10
    const val BORDER_INSET = 9
    const val BORDER = 4
    const val TOUCH = 96 // AAOS minimum is 76
}
