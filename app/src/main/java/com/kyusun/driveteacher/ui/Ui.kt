package com.kyusun.driveteacher.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.text.LineBreakConfig
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** View builders for the road-sign world. Views are built in code (no AndroidX); one [Ui] per render. */
internal class Ui(val context: Context, val palette: Palette, val faces: Faces) {
    private val density = context.resources.displayMetrics.density

    fun px(dp: Int): Int = (dp * density + 0.5f).toInt()

    fun text(
        value: CharSequence,
        sp: Float,
        face: Typeface,
        color: Int,
        gravity: Int = Gravity.START,
        lines: Int = Int.MAX_VALUE,
    ): TextView = TextView(context).apply {
        text = value
        textSize = sp
        typeface = face
        setTextColor(color)
        this.gravity = gravity or Gravity.CENTER_VERTICAL
        // Gothic A1's tallest jamo (ㅂ's posts) rise past its ascent: without the font padding "불" is clipped to "물".
        includeFontPadding = !(face === faces.numerals || face === faces.numeralsLight)
        setLineSpacing(0f, 1.18f)
        maxLines = lines
        if (lines != Int.MAX_VALUE) ellipsize = TextUtils.TruncateAt.END
        // Korean wraps between phrases, not mid-word ("급\n출발").
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lineBreakWordStyle = LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
        }
        if (face === faces.numerals || face === faces.numeralsLight) fontFeatureSettings = "tnum"
    }

    /** A numeral that grows to fill its box: the score. Set against the box's end so a unit beside it stays close. */
    fun fill(value: String, color: Int, maxSp: Int, face: Typeface = faces.numerals): TextView =
        text(value, maxSp.toFloat(), face, color, Gravity.END).apply {
            maxLines = 1
            letterSpacing = -0.04f // the tracking floor: three digits get as large as two nearly do
            setAutoSizeTextTypeUniformWithConfiguration(40, maxSp, 4, TypedValue.COMPLEX_UNIT_SP)
        }

    fun column(vararg children: View?, gravity: Int = Gravity.START): LinearLayout = linear(LinearLayout.VERTICAL, gravity, children)

    fun row(vararg children: View?, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout = linear(LinearLayout.HORIZONTAL, gravity, children)

    private fun linear(orientation: Int, gravity: Int, children: Array<out View?>) = LinearLayout(context).apply {
        this.orientation = orientation
        this.gravity = gravity
        isBaselineAligned = false // baseline alignment clips a wrapped second line beside a one-line plate
        children.filterNotNull().forEach(::addView)
    }

    /** A sign panel of [fill] with its inset white line; content padded clear of the line. */
    fun sign(fill: Int, line: Int, content: View, padDp: Int = Dp.L): FrameLayout = FrameLayout(context).apply {
        background = signFace(fill, line, px(Dp.SIGN_RADIUS).toFloat(), px(Dp.BORDER_INSET), px(Dp.BORDER))
        val pad = px(padDp)
        setPadding(pad, pad, pad, pad)
        addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** 보조표지 or a work-zone plate: short text on a flat plate. */
    fun plate(value: String, fill: Int, ink: Int, sp: Float = Sp.LABEL, edge: Int? = null): TextView =
        text(value, sp, faces.bold, ink, Gravity.CENTER).apply {
            background = plateFace(fill, px(Dp.PLATE_RADIUS).toFloat(), edge, px(2))
            setPadding(px(Dp.S), px(Dp.XS + 2), px(Dp.S), px(Dp.XS + 2))
        }

    /** An item number as signs show route numbers: a white plate, black numerals. */
    fun numberPlate(number: Int, sp: Float = Sp.LINE): TextView =
        text(number.toString(), sp, faces.numerals, palette.plateInk, Gravity.CENTER).apply {
            background = plateFace(palette.plate, px(Dp.PLATE_RADIUS).toFloat())
            minWidth = px((sp * 2.1f).toInt())
            setPadding(px(Dp.XS + 2), px(6), px(Dp.XS + 2), px(4))
        }

    fun symbol(drawable: Drawable, sizeDp: Int): View = View(context).apply {
        background = drawable
        layoutParams = LinearLayout.LayoutParams(px(sizeDp), px(sizeDp))
    }

    /** A thin rule across a sign, in the sign's muted ink. */
    fun rule(color: Int, thicknessDp: Int = 2, vertical: Boolean = false, topDp: Int = 0): View = View(context).apply {
        setBackgroundColor(color)
        layoutParams = if (vertical) {
            LinearLayout.LayoutParams(px(thicknessDp), MATCH)
        } else {
            LinearLayout.LayoutParams(MATCH, px(thicknessDp)).apply { topMargin = px(topDp) }
        }
    }

    /** Makes [view] a control: press ripple, rotary focus ring, an accessible name, and [onTap]. */
    fun <V : View> tappable(view: V, label: String, radiusDp: Int = Dp.SIGN_RADIUS, onTap: () -> Unit): V = view.apply {
        isClickable = true
        isFocusable = true
        contentDescription = label
        foreground = touchFeedback(palette.ripple, palette.focus, px(radiusDp).toFloat(), px(6))
        minimumHeight = px(Dp.TOUCH)
        setOnClickListener { onTap() }
    }

    /** A plate-shaped button: [fill] face, centred label, at least [Dp.TOUCH] tall. */
    fun button(label: String, fill: Int, ink: Int, edge: Int? = null, onTap: () -> Unit): TextView =
        tappable(text(label, Sp.LINE, faces.heavy, ink, Gravity.CENTER), label, Dp.PLATE_RADIUS + 4, onTap).apply {
            background = plateFace(fill, px(Dp.PLATE_RADIUS + 4).toFloat(), edge, px(3))
            setPadding(px(Dp.L), px(Dp.S), px(Dp.L), px(Dp.S))
        }

    fun gap(dp: Int): View = View(context).apply { layoutParams = LinearLayout.LayoutParams(px(dp), px(dp)) }

    /** Takes [weight] of the parent's length; across, it fills the parent unless [fillAcross] is false (text). */
    fun weight(view: View, weight: Float, vertical: Boolean = false, fillAcross: Boolean = true): View = view.apply {
        val across = if (fillAcross) MATCH else WRAP
        layoutParams = if (vertical) LinearLayout.LayoutParams(across, 0, weight) else LinearLayout.LayoutParams(0, across, weight)
    }

    fun wrap(view: View, gravity: Int = Gravity.NO_GRAVITY, topDp: Int = 0): View = view.apply {
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply {
            this.gravity = gravity
            topMargin = px(topDp)
        }
    }

    fun full(view: View, topDp: Int = 0): View = view.apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = px(topDp) }
    }

    /** The screen: ground colour, padded clear of the edges. */
    fun screen(content: View): View = FrameLayout(context).apply {
        setBackgroundColor(palette.ground)
        val pad = px(Dp.L)
        setPadding(pad, pad, pad, pad)
        addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
