package com.grandrr.camera

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import java.util.Random
import kotlin.math.*

/** Draws every live effect on top of the camera, driven by a motion grid from the camera frames (or touch). */
class EffectView(ctx: Context) : View(ctx) {
    companion object { const val GW = 24; const val GH = 40 }

    var effect: Effect = Effect.FIREFLIES
        set(v) { field = v; parts.clear() }
    var style = 0
        set(v) { field = v; buildLeaves() }
    var onNote: ((Float, Float) -> Unit)? = null

    private class Part(var x: Float, var y: Float, var vx: Float, var vy: Float,
                       val max: Float, val hue: Float, val size: Float) {
        var life = 0f
        val tx = FloatArray(10); val ty = FloatArray(10); var n = 0
        fun push() {
            for (i in 9 downTo 1) { tx[i] = tx[i - 1]; ty[i] = ty[i - 1] }
            tx[0] = x; ty[0] = y; if (n < 10) n++
        }
    }
    private class Leaf(val x: Float, val y: Float, val rot: Float, val len: Float, val wid: Float, val ph: Float)

    private val dp = resources.displayMetrics.density
    private val cam = FloatArray(GW * GH)
    private val touch = FloatArray(GW * GH)
    private val mm = FloatArray(GW * GH)
    private val rnd = Random()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rf = RectF()
    private val parts = ArrayList<Part>()
    private val leaves = ArrayList<Leaf>()
    private var last = 0L
    private var t = 0f
    private var noteCd = 0f
    private var eCx = 0.5f
    private var eCy = 0.5f
    private var energy = 0f

    init { buildLeaves() }

    fun feed(m: FloatArray) { System.arraycopy(m, 0, cam, 0, min(m.size, cam.size)) }

    private fun hsv(h: Float, s: Float, v: Float) = Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s, v))
    private fun gauss() = rnd.nextGaussian().toFloat()

    private fun mAt(x: Float, y: Float): Float {
        val gx = (x / width * GW).toInt().coerceIn(0, GW - 1)
        val gy = (y / height * GH).toInt().coerceIn(0, GH - 1)
        return mm[gy * GW + gx]
    }

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, col: Int, a: Float) {
        p.style = Paint.Style.FILL
        for (k in 0..3) {
            p.color = col
            p.alpha = (a * 255f * (0.12f + k * 0.25f)).toInt().coerceIn(0, 255)
            c.drawCircle(x, y, r * (1f - k * 0.25f), p)
        }
    }

    private fun spawn(rate: Float, dt: Float, cap: Int, make: (Float, Float) -> Part) {
        val cw = width / GW.toFloat(); val ch = height / GH.toFloat()
        for (i in mm.indices) {
            val m = mm[i]
            if (m < 0.15f) continue
            if (parts.size >= cap) return
            if (rnd.nextFloat() < m * rate * dt)
                parts.add(make((i % GW + rnd.nextFloat()) * cw, (i / GW + rnd.nextFloat()) * ch))
        }
    }

    private fun step(dt: Float, trail: Boolean, drag: Float, field: ((Part) -> Unit)?) {
        val it = parts.iterator()
        while (it.hasNext()) {
            val q = it.next()
            field?.invoke(q)
            q.x += q.vx * dt; q.y += q.vy * dt
            val d = 1f - drag * dt
            q.vx *= d; q.vy *= d
            q.life += dt
            if (trail) q.push()
            if (q.life >= q.max || q.x < -60 || q.x > width + 60 || q.y < -60 || q.y > height + 60) it.remove()
        }
    }

    private fun drawTrails(c: Canvas, w: Float, sat: Float, bri: Float) {
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        for (q in parts) {
            if (q.n < 2) continue
            val f = (1f - q.life / q.max).coerceIn(0f, 1f)
            p.color = hsv(q.hue, sat, bri); p.alpha = (f * 230).toInt()
            p.strokeWidth = w * dp * (0.4f + 0.6f * f)
            path.reset(); path.moveTo(q.tx[0], q.ty[0])
            for (i in 1 until q.n) path.lineTo(q.tx[i], q.ty[i])
            c.drawPath(path, p)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            val gx = (e.x / width * GW).toInt(); val gy = (e.y / height * GH).toInt()
            for (dy in -2..2) for (dx in -2..2) {
                val x = gx + dx; val y = gy + dy
                if (x in 0 until GW && y in 0 until GH) touch[y * GW + x] = 1f
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f) return
        val now = System.nanoTime()
        var dt = if (last == 0L) 0.016f else (now - last) / 1e9f
        last = now; dt = dt.coerceIn(0.002f, 0.05f); t += dt

        energy = 0f; var sx = 0f; var sy = 0f
        for (i in mm.indices) {
            touch[i] *= 0.92f
            val m = max(cam[i], touch[i]); mm[i] = m
            if (m > 0.12f) { energy += m; sx += (i % GW + 0.5f) * m; sy += (i / GW + 0.5f) * m }
        }
        if (energy > 0.01f) { eCx = sx / energy / GW; eCy = sy / energy / GH }
        noteCd -= dt
        if (effect.sound && energy > 2f && noteCd <= 0f) { onNote?.invoke(eCx, min(1f, energy / 25f)); noteCd = 0.25f }

        when (effect) {
            Effect.FIREFLIES -> fireflies(c, dt)
            Effect.TESLA -> tesla(c)
            Effect.EMBER -> ember(c, dt)
            Effect.TRACE -> trace(c, w, h, dt)
            Effect.SILK, Effect.VORTEX -> silkVortex(c, w, h, dt)
            Effect.NEBULA -> nebula(c, w, h, dt)
            Effect.OPAL -> opal(c, w, h)
            Effect.RESONANCE -> resonance(c, w, h)
            Effect.DAPPLE -> dapple(c, w, h)
            Effect.STRINGS -> strings(c, w, h)
        }
        postInvalidateOnAnimation()
    }

    private fun fireflies(c: Canvas, dt: Float) {
        spawn(40f, dt, 400) { x, y ->
            Part(x, y, gauss() * 12 * dp, gauss() * 12 * dp, 4f + rnd.nextFloat() * 5f,
                rnd.nextFloat() * 360f, (3f + rnd.nextFloat() * 5f) * dp)
        }
        step(dt, false, 0.25f, null)
        for (q in parts) {
            val f = 1f - q.life / q.max
            val tw = 0.6f + 0.4f * sin(t * 4f + q.hue)
            glow(c, q.x, q.y, q.size * 3f, hsv(q.hue, 0.5f, 1f), f * tw)
        }
    }

    private fun tesla(c: Canvas) {
        var bolts = 0
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        for (i in mm.indices) {
            if (mm[i] < 0.3f || bolts > 14 || rnd.nextFloat() > 0.3f) continue
            bolts++
            var x = (i % GW + 0.5f) * width / GW; var y = (i / GW + 0.5f) * height / GH
            val ang = rnd.nextFloat() * 6.28f
            path.reset(); path.moveTo(x, y)
            for (k in 0 until 5) {
                x += cos(ang + (rnd.nextFloat() - 0.5f) * 2f) * 22f * dp
                y += sin(ang + (rnd.nextFloat() - 0.5f) * 2f) * 22f * dp
                path.lineTo(x, y)
            }
            p.strokeWidth = 5f * dp; p.color = Color.rgb(92, 124, 255); p.alpha = 60; c.drawPath(path, p)
            p.strokeWidth = 1.3f * dp; p.color = Color.rgb(221, 230, 255); p.alpha = 240; c.drawPath(path, p)
        }
    }

    private fun ember(c: Canvas, dt: Float) {
        spawn(30f, dt, 500) { x, y ->
            Part(x, y, gauss() * 30 * dp, -(30f + rnd.nextFloat() * 60f) * dp, 1.2f + rnd.nextFloat() * 1.5f,
                15f + rnd.nextFloat() * 25f, (2f + rnd.nextFloat() * 3f) * dp)
        }
        step(dt, false, 0.5f, null)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        for (q in parts) {
            val f = 1f - q.life / q.max
            p.color = hsv(q.hue, 0.85f, 1f); p.alpha = (f * 255).toInt(); p.strokeWidth = 1.6f * dp
            c.drawLine(q.x, q.y, q.x - q.vx * 0.05f, q.y - q.vy * 0.05f, p)
            glow(c, q.x, q.y, q.size, hsv(q.hue, 0.8f, 1f), f * 0.5f)
        }
    }

    private fun trace(c: Canvas, w: Float, h: Float, dt: Float) {
        p.style = Paint.Style.FILL; p.color = Color.argb(110, 0, 0, 0); c.drawRect(0f, 0f, w, h, p)
        val stepPx = 16f * dp
        var yy = stepPx / 2
        while (yy < h) {
            var xx = stepPx / 2
            while (xx < w) {
                p.color = Color.argb(120, 255, 255, 255)
                c.drawCircle(xx, yy, (1.2f + mAt(xx, yy) * 2.4f) * dp, p)
                xx += stepPx
            }
            yy += stepPx
        }
        val life = floatArrayOf(1.5f, 3f, 7f)[style.coerceIn(0, 2)]
        spawn(60f, dt, 500) { x, y -> Part(x, y, gauss() * 40 * dp, gauss() * 40 * dp, life, 15f + rnd.nextFloat() * 20f, 0f) }
        step(dt, true, 0.8f) { q -> if (style == 0) q.vy += 60f * dp * dt }
        drawTrails(c, 1.4f, 0.8f, 1f)
    }

    private fun silkVortex(c: Canvas, w: Float, h: Float, dt: Float) {
        val vortex = effect == Effect.VORTEX
        val ccx = eCx * w; val ccy = eCy * h
        val hueBase = if (vortex) 190f else 320f
        if (energy < 0.5f && parts.size < 80 && rnd.nextFloat() < 0.6f)
            parts.add(Part(rnd.nextFloat() * w, rnd.nextFloat() * h, 0f, 0f, 3f, hueBase + rnd.nextFloat() * 60f, 0f))
        spawn(50f, dt, 600) { x, y -> Part(x, y, gauss() * 30 * dp, gauss() * 30 * dp, 2f + rnd.nextFloat() * 2f, hueBase + rnd.nextFloat() * 60f, 0f) }
        step(dt, true, 0.6f) { q ->
            if (vortex) {
                val dx = q.x - ccx; val dy = q.y - ccy
                q.vx += (-dy * 1.6f - dx * 0.4f) * dt; q.vy += (dx * 1.6f - dy * 0.4f) * dt
            } else {
                q.vx += sin(q.y * 0.012f + t) * 160f * dp * dt
                q.vy += cos(q.x * 0.012f + t * 1.3f) * 160f * dp * dt
            }
        }
        drawTrails(c, 2.2f, 0.35f, 1f)
    }

    private fun nebula(c: Canvas, w: Float, h: Float, dt: Float) {
        if (parts.size < 10) parts.add(Part(rnd.nextFloat() * w, rnd.nextFloat() * h, gauss() * 6 * dp, gauss() * 6 * dp, 6f, 250f + rnd.nextFloat() * 80f, (50f + rnd.nextFloat() * 70f) * dp))
        spawn(8f, dt, 120) { x, y -> Part(x, y, gauss() * 8 * dp, gauss() * 8 * dp, 4f + rnd.nextFloat() * 3f, 250f + rnd.nextFloat() * 80f, (40f + rnd.nextFloat() * 70f) * dp) }
        step(dt, false, 0.2f, null)
        for (q in parts) glow(c, q.x, q.y, q.size, hsv(q.hue, 0.6f, 1f), (1f - q.life / q.max) * 0.35f)
    }

    private fun opal(c: Canvas, w: Float, h: Float) {
        val a = t * 0.3f; val dx = cos(a) * w; val dy = sin(a) * h
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(w / 2 - dx / 2, h / 2 - dy / 2, w / 2 + dx / 2, h / 2 + dy / 2,
            intArrayOf(Color.argb(70, 255, 180, 232), Color.argb(70, 180, 232, 255), Color.argb(70, 232, 255, 180), Color.argb(70, 255, 232, 180)),
            null, Shader.TileMode.MIRROR)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        val cw = w / GW; val ch = h / GH
        for (i in mm.indices) {
            if (mm[i] < 0.2f) continue
            p.color = hsv(t * 60f + i * 7f, 0.4f, 1f); p.alpha = (mm[i] * 140).toInt()
            val x = (i % GW) * cw; val y = (i / GW) * ch
            c.drawRect(x, y, x + cw, y + ch, p)
        }
    }

    private fun resonance(c: Canvas, w: Float, h: Float) {
        p.style = Paint.Style.FILL; p.color = Color.argb(120, 16, 10, 4); c.drawRect(0f, 0f, w, h, p)
        val step = w / 3.2f
        val x0 = 0.61f * step
        val y0 = (0.245f * h) % step - step
        val warm = Color.rgb(255, 226, 170)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.color = warm
        var j = 0
        while (y0 + j * step < h + step) {
            for (i in -1..4) {
                val x = x0 + i * step; val y = y0 + j * step
                val m = mAt(x.coerceIn(0f, w - 1), y.coerceIn(0f, h - 1))
                val bright = 0.55f + 0.45f * min(1f, m * 2f + energy * 0.02f)
                val wob = 1f + (0.05f + 0.25f * m) * sin(t * 5f + i * 1.3f + j)
                val layers = floatArrayOf(10f, 5f, 2f); val alphas = floatArrayOf(25f, 60f, 235f)
                for (k in 0..2) {
                    p.strokeWidth = layers[k] * dp; p.color = warm; p.alpha = (alphas[k] * bright).toInt()
                    if ((i + j) % 2 == 0) {
                        val r = 0.34f * step * wob
                        rf.set(x - r, y - r, x + r, y + r); c.drawRoundRect(rf, r * 0.8f, r * 0.8f, p)
                    } else {
                        val l = 0.42f * step * (1f + 0.2f * m)
                        c.drawLine(x - l, y - l, x + l, y + l, p); c.drawLine(x - l, y + l, x + l, y - l, p)
                    }
                }
            }
            j++
        }
    }

    private fun buildLeaves() {
        leaves.clear()
        val r = Random(11L + style)
        when (style) {
            0 -> for (s in 0..1) for (k in 0..9)
                leaves.add(Leaf(0.78f + 0.1f * s, -0.02f, 60f + k * 14f + s * 10f, 0.42f, 0.05f, r.nextFloat() * 6f))
            1 -> for (s in 0..2) { val sx = 0.12f + 0.3f * s
                for (k in 0..16) { val side = if (k % 2 == 0) 1 else -1
                    leaves.add(Leaf(sx + 0.05f * sin(k * 0.6f + s), k / 16f, 90f - side * 55f + r.nextFloat() * 20f, 0.12f, 0.045f, r.nextFloat() * 6f)) } }
            2 -> for (s in 0..1) { val sx = 0.2f + 0.5f * s
                for (k in 0..30) { val side = if (k % 2 == 0) 1 else -1
                    leaves.add(Leaf(sx + 0.08f * sin(k * 0.3f), k / 30f, 90f - side * 70f, 0.07f, 0.022f, r.nextFloat() * 6f)) } }
            else -> for (s in 0..1) { val sx = 0.25f + 0.45f * s
                for (k in 0..11) { val side = if (k % 2 == 0) 1 else -1
                    leaves.add(Leaf(sx + 0.04f * sin(k * 0.8f), k / 11f, 90f - side * 50f, 0.16f, 0.06f, r.nextFloat() * 6f)) } }
        }
    }

    private fun dapple(c: Canvas, w: Float, h: Float) {
        p.style = Paint.Style.FILL; p.color = Color.argb(135, 24, 28, 40)
        for (l in leaves) {
            val m = mAt((l.x * w).coerceIn(0f, w - 1), (l.y * h).coerceIn(0f, h - 1))
            val ang = l.rot + sin(t * 1.1f + l.ph) * 4f + m * 35f * sin(t * 7f + l.ph)
            c.save(); c.translate(l.x * w, l.y * h); c.rotate(ang)
            rf.set(0f, -l.wid * w / 2, l.len * w, l.wid * w / 2); c.drawOval(rf, p)
            c.restore()
        }
    }

    private fun strings(c: Canvas, w: Float, h: Float) {
        val horiz = style == 1 || style == 3
        p.style = Paint.Style.FILL; p.color = Color.argb(46, 176, 138, 90); c.drawRect(0f, 0f, w, h, p)
        val gap = floatArrayOf(26f, 8f, 22f, 7f)[style.coerceIn(0, 3)] * dp
        val sw = floatArrayOf(1.2f, 3f, 7f, 3.4f)[style.coerceIn(0, 3)] * dp
        p.style = Paint.Style.STROKE; p.strokeWidth = sw; p.strokeCap = Paint.Cap.ROUND
        val span = if (horiz) h else w; val len = if (horiz) w else h
        var pos = gap / 2; var idx = 0
        while (pos < span) {
            path.reset()
            for (s in 0..40) {
                val a = len * s / 40f
                val x = if (horiz) a else pos; val y = if (horiz) pos else a
                val m = mAt(x.coerceIn(0f, w - 1), y.coerceIn(0f, h - 1))
                val off = (sin(t * 1.5f + idx * 0.3f + s * 0.4f) * 1.5f + m * sin(t * 10f + s * 0.9f + idx * 0.5f) * 16f) * dp
                val px = if (horiz) x else x + off; val py = if (horiz) y + off else y
                if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            p.color = if (idx % 2 == 0) Color.argb(210, 210, 175, 130) else Color.argb(190, 120, 90, 60)
            c.drawPath(path, p)
            pos += gap; idx++
        }
    }
}
