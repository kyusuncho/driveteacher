package com.kyusun.driveteacher.ui

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable

/** A sign face: a rounded panel with the white line inset from its edge that Korean guide signs carry. */
internal fun signFace(fill: Int, line: Int, radiusPx: Float, insetPx: Int, linePx: Int): Drawable {
    val panel = GradientDrawable().apply {
        cornerRadius = radiusPx
        setColor(fill)
    }
    val border = GradientDrawable().apply {
        cornerRadius = (radiusPx - insetPx).coerceAtLeast(0f)
        setStroke(linePx, line)
    }
    return LayerDrawable(arrayOf(panel, border)).apply { setLayerInset(1, insetPx, insetPx, insetPx, insetPx) }
}

/** A flat plate (보조표지, number plates): fill with an optional thin edge. */
internal fun plateFace(fill: Int, radiusPx: Float, edge: Int? = null, edgePx: Int = 0): Drawable =
    GradientDrawable().apply {
        cornerRadius = radiusPx
        setColor(fill)
        if (edge != null) setStroke(edgePx, edge)
    }

/** Press ripple clipped to the face, and a focus ring for rotary controllers (AAOS) drawn on top. */
internal fun touchFeedback(ripple: Int, focus: Int, radiusPx: Float, ringPx: Int): Drawable {
    val mask = GradientDrawable().apply {
        cornerRadius = radiusPx
        setColor(0xFFFFFFFF.toInt())
    }
    val ring = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_focused),
            GradientDrawable().apply {
                cornerRadius = radiusPx
                setStroke(ringPx, focus)
            },
        )
    }
    return LayerDrawable(arrayOf(RippleDrawable(ColorStateList.valueOf(ripple), null, mask), ring))
}

/** Base for the drawn sign symbols: one path family, bounds-relative, no bitmaps. */
internal abstract class Symbol : Drawable() {
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** 주의표지: a yellow triangle with a red border and a black exclamation mark. */
internal class CautionSymbol(private val fill: Int, private val edge: Int, private val ink: Int) : Symbol() {
    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = w * 0.88f
        val top = b.top + (b.height() - h) / 2
        fun triangle(inset: Float) = Path().apply {
            moveTo(b.left + w / 2, top + inset * 1.15f)
            lineTo(b.right - inset, top + h - inset * 0.6f)
            lineTo(b.left + inset, top + h - inset * 0.6f)
            close()
        }
        paint.style = Paint.Style.FILL
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = edge
        canvas.drawPath(triangle(0f), paint)
        paint.color = fill
        canvas.drawPath(triangle(w * 0.11f), paint)
        paint.color = ink
        val cx = b.left + w / 2
        val bar = w * 0.075f
        canvas.drawRoundRect(cx - bar / 2, top + h * 0.36f, cx + bar / 2, top + h * 0.68f, bar / 2, bar / 2, paint)
        canvas.drawCircle(cx, top + h * 0.78f, bar * 0.62f, paint)
    }
}

/** 규제표지: a white disc in a red ring. Content (text) sits on top in its own view. */
internal class RingSymbol(private val fill: Int, private val ring: Int) : Symbol() {
    override fun draw(canvas: Canvas) {
        val r = minOf(bounds.width(), bounds.height()) / 2f
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.2f
        paint.color = ring
        canvas.drawCircle(cx, cy, r * 0.9f, paint)
    }
}

/** 지시표지: a blue disc with a white straight-ahead arrow. */
internal class AheadSymbol(private val fill: Int, private val ink: Int) : Symbol() {
    override fun draw(canvas: Canvas) {
        val r = minOf(bounds.width(), bounds.height()) / 2f
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawCircle(cx, cy, r, paint)
        paint.color = ink
        canvas.drawPath(arrow(cx, cy, r * 1.15f), paint)
    }
}

/** A road-sign arrow pointing up, [size] tall, centred on (cx, cy): wide head, straight shaft. */
internal fun arrow(cx: Float, cy: Float, size: Float): Path {
    val top = cy - size / 2
    val head = size * 0.42f
    val halfHead = size * 0.3f
    val halfShaft = size * 0.105f
    return Path().apply {
        moveTo(cx, top)
        lineTo(cx + halfHead, top + head)
        lineTo(cx + halfShaft, top + head)
        lineTo(cx + halfShaft, top + size)
        lineTo(cx - halfShaft, top + size)
        lineTo(cx - halfShaft, top + head)
        lineTo(cx - halfHead, top + head)
        close()
    }
}

/** A plain arrow on a sign face (lane arrows on the gantry). */
internal class ArrowSymbol(private val ink: Int) : Symbol() {
    override fun draw(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = ink
        val size = minOf(bounds.width(), bounds.height()).toFloat()
        canvas.drawPath(arrow(bounds.exactCenterX(), bounds.exactCenterY(), size), paint)
    }
}
