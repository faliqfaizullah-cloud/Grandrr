package com.grandrr.camera

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Round "liquid glass" button with a hand-drawn icon. */
class IconButton(ctx: Context, var kind: Kind, private val fill: Int = Color.argb(64, 255, 255, 255)) : View(ctx) {
    enum class Kind { FLOWER, FLIP, PERSON, HELP, GALLERY, CLOSE, SPEAKER, MUTE, DOT, SHUFFLE }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rf = RectF()
    private val dp = resources.displayMetrics.density

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - dp
        p.style = Paint.Style.FILL; p.color = fill; c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * dp; p.color = Color.argb(120, 255, 255, 255)
        c.drawCircle(cx, cy, r, p)
        p.color = Color.WHITE; p.strokeWidth = 2f * dp; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        val u = r / 10f
        when (kind) {
            Kind.FLOWER -> {
                p.style = Paint.Style.FILL
                for (i in 0 until 8) {
                    val a = i * 0.7853982f
                    c.drawCircle(cx + cos(a) * u * 3.6f, cy + sin(a) * u * 3.6f, u * 2.9f, p)
                }
                c.drawCircle(cx, cy, u * 3.8f, p)
            }
            Kind.FLIP -> {
                rf.set(cx - u * 5.5f, cy - u * 3.5f, cx + u * 5.5f, cy + u * 4.5f)
                c.drawRoundRect(rf, u * 1.6f, u * 1.6f, p)
                rf.set(cx - u * 2.6f, cy - u * 2f, cx + u * 2.6f, cy + u * 3f)
                c.drawArc(rf, 200f, 250f, false, p)
            }
            Kind.PERSON -> {
                c.drawCircle(cx, cy - u * 2.4f, u * 2.4f, p)
                rf.set(cx - u * 4.6f, cy + u * 0.8f, cx + u * 4.6f, cy + u * 9f)
                c.drawArc(rf, 200f, 140f, false, p)
            }
            Kind.HELP -> {
                p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER
                p.typeface = Typeface.DEFAULT_BOLD; p.textSize = u * 11f
                c.drawText("?", cx, cy + u * 3.9f, p)
            }
            Kind.GALLERY -> {
                rf.set(cx - u * 5.5f, cy - u * 4.5f, cx + u * 5.5f, cy + u * 4.5f)
                c.drawRoundRect(rf, u * 1.6f, u * 1.6f, p)
                p.style = Paint.Style.FILL; c.drawCircle(cx - u * 2.2f, cy - u * 1.5f, u * 1f, p)
                p.style = Paint.Style.STROKE
                path.reset(); path.moveTo(cx - u * 5.5f, cy + u * 3f); path.lineTo(cx - u * 1.5f, cy)
                path.lineTo(cx + u * 1f, cy + u * 2.5f); path.lineTo(cx + u * 3f, cy + u * 0.5f)
                path.lineTo(cx + u * 5.5f, cy + u * 3f); c.drawPath(path, p)
            }
            Kind.CLOSE -> {
                c.drawLine(cx - u * 4f, cy - u * 4f, cx + u * 4f, cy + u * 4f, p)
                c.drawLine(cx - u * 4f, cy + u * 4f, cx + u * 4f, cy - u * 4f, p)
            }
            Kind.SPEAKER, Kind.MUTE -> {
                p.style = Paint.Style.FILL
                path.reset(); path.moveTo(cx - u * 6f, cy - u * 2f); path.lineTo(cx - u * 3.5f, cy - u * 2f)
                path.lineTo(cx, cy - u * 5f); path.lineTo(cx, cy + u * 5f); path.lineTo(cx - u * 3.5f, cy + u * 2f)
                path.lineTo(cx - u * 6f, cy + u * 2f); path.close(); c.drawPath(path, p)
                p.style = Paint.Style.STROKE
                if (kind == Kind.SPEAKER) {
                    rf.set(cx - u * 1.5f, cy - u * 3f, cx + u * 3.5f, cy + u * 3f); c.drawArc(rf, -50f, 100f, false, p)
                    rf.set(cx - u * 1.5f, cy - u * 5.5f, cx + u * 6f, cy + u * 5.5f); c.drawArc(rf, -50f, 100f, false, p)
                } else {
                    c.drawLine(cx + u * 2f, cy - u * 2.5f, cx + u * 6.5f, cy + u * 2.5f, p)
                    c.drawLine(cx + u * 2f, cy + u * 2.5f, cx + u * 6.5f, cy - u * 2.5f, p)
                }
            }
            Kind.DOT -> { p.style = Paint.Style.FILL; c.drawCircle(cx, cy, u * 3.3f, p) }
            Kind.SHUFFLE -> {
                path.reset(); path.moveTo(cx - u * 5f, cy - u * 3f); path.lineTo(cx - u * 1f, cy - u * 3f)
                path.lineTo(cx + u * 2f, cy + u * 3f); path.lineTo(cx + u * 5f, cy + u * 3f); c.drawPath(path, p)
                path.reset(); path.moveTo(cx - u * 5f, cy + u * 3f); path.lineTo(cx - u * 1f, cy + u * 3f)
                path.lineTo(cx + u * 2f, cy - u * 3f); path.lineTo(cx + u * 5f, cy - u * 3f); c.drawPath(path, p)
                c.drawLine(cx + u * 3.5f, cy - u * 4.5f, cx + u * 5f, cy - u * 3f, p)
                c.drawLine(cx + u * 3.5f, cy - u * 1.5f, cx + u * 5f, cy - u * 3f, p)
                c.drawLine(cx + u * 3.5f, cy + u * 1.5f, cx + u * 5f, cy + u * 3f, p)
                c.drawLine(cx + u * 3.5f, cy + u * 4.5f, cx + u * 5f, cy + u * 3f, p)
            }
        }
    }
}

/** Big red record button with a progress ring while recording. */
class RecordButton(ctx: Context) : View(ctx) {
    var recording = false
        set(v) { field = v; if (v) begin() else end() }
    private var progress = 0f
    private var anim: ValueAnimator? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val dp = resources.displayMetrics.density

    private fun begin() {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 60000; interpolator = LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
        invalidate()
    }

    private fun end() { anim?.cancel(); progress = 0f; invalidate() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 3 * dp
        p.style = Paint.Style.FILL; p.color = Color.argb(150, 120, 45, 45); c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f * dp; p.color = Color.argb(70, 255, 255, 255)
        c.drawCircle(cx, cy, r, p)
        if (recording) {
            p.color = Color.WHITE; p.strokeCap = Paint.Cap.ROUND
            rf.set(cx - r, cy - r, cx + r, cy + r); c.drawArc(rf, -90f, 360f * progress, false, p)
        }
        p.style = Paint.Style.FILL; p.color = Color.rgb(250, 60, 70)
        if (recording) {
            val s = r * 0.36f; rf.set(cx - s, cy - s, cx + s, cy + s); c.drawRoundRect(rf, s * 0.35f, s * 0.35f, p)
        } else c.drawCircle(cx, cy, r * 0.56f, p)
    }
}
