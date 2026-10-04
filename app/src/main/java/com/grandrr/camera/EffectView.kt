package com.grandrr.camera

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import java.util.Random
import kotlin.math.*

/**
 * Live effects drawn over the camera. Everything is driven by a motion grid (from camera frame
 * differencing, or touch) and a luma grid. Light effects use additive blending + pre-rendered glow
 * sprites, so they behave like real light instead of flat shapes.
 */
class EffectView(ctx: Context) : View(ctx) {
    companion object { const val GW = 24; const val GH = 40; const val LW = 48; const val LH = 84; const val TR = 24 }

    var effect: Effect = Effect.FIREFLIES
        set(v) { field = v; parts.clear(); resetBuf(); amp.fill(0f) }
    var style = 0
        set(v) { field = v; buildLeaves() }
    var onNote: ((Float, Float) -> Unit)? = null

    private class Part(var x: Float, var y: Float, var vx: Float, var vy: Float,
                       val max: Float, val hue: Float, val size: Float) {
        var life = 0f
        var ox = x; var oy = y
        val tx = FloatArray(TR); val ty = FloatArray(TR); var n = 0
        fun push() {
            for (i in TR - 1 downTo 1) { tx[i] = tx[i - 1]; ty[i] = ty[i - 1] }
            tx[0] = x; ty[0] = y; if (n < TR) n++
        }
    }
    private class Leaf(val x: Float, val y: Float, val rot: Float, val len: Float, val wid: Float, val ph: Float)

    private val dp = resources.displayMetrics.density
    private val cam = FloatArray(GW * GH)
    private val touch = FloatArray(GW * GH)
    private val mm = FloatArray(GW * GH)
    private val luma = FloatArray(LW * LH) { 0.5f }
    private val amp = FloatArray(256)
    private val rnd = Random()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val add = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD); isFilterBitmap = true }
    private val addStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD); style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fadePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val bufPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val rf = RectF()
    private val dst = RectF()
    private val hsvBuf = FloatArray(3)
    private val parts = ArrayList<Part>()
    private val leaves = ArrayList<Leaf>()
    private val stems = ArrayList<FloatArray>()
    private val strong = ArrayList<Int>()
    private val pts = FloatArray(2 * 9000)
    private val leafPath = Path().apply { moveTo(0f, 0f); quadTo(0.5f, -0.5f, 1f, 0f); quadTo(0.5f, 0.5f, 0f, 0f) }
    private val starX = FloatArray(90); private val starY = FloatArray(90); private val starP = FloatArray(90)
    private var buf: Bitmap? = null
    private var bufCanvas: Canvas? = null
    private var last = 0L
    private var t = 0f
    private var noteCd = 0f
    private var eCx = 0.5f
    private var eCy = 0.5f
    private var energy = 0f

    // pre-rendered light sprites: tinted soft glow and bokeh discs for 36 hues, plus white
    private val glow = Array(37) { makeGlow(if (it == 36) -1f else it * 10f) }
    private val bokeh = Array(36) { makeBokeh(it * 10f) }

    init {
        val r = Random(3)
        for (i in starX.indices) { starX[i] = r.nextFloat(); starY[i] = r.nextFloat(); starP[i] = r.nextFloat() * 6.28f }
        buildLeaves()
    }

    private fun tint(h: Float): Int = if (h < 0) Color.WHITE else Color.HSVToColor(floatArrayOf(h, 0.5f, 1f))

    private fun makeGlow(h: Float): Bitmap {
        val s = 96; val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val col = tint(h)
        val pp = Paint(Paint.ANTI_ALIAS_FLAG)
        pp.shader = RadialGradient(s / 2f, s / 2f, s / 2f,
            intArrayOf(Color.argb(255, Color.red(col), Color.green(col), Color.blue(col)),
                Color.argb(110, Color.red(col), Color.green(col), Color.blue(col)),
                Color.argb(0, Color.red(col), Color.green(col), Color.blue(col))),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        Canvas(b).drawCircle(s / 2f, s / 2f, s / 2f, pp)
        return b
    }

    private fun makeBokeh(h: Float): Bitmap {
        val s = 96; val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val col = tint(h)
        val r = Color.red(col); val g = Color.green(col); val bl = Color.blue(col)
        val pp = Paint(Paint.ANTI_ALIAS_FLAG)
        pp.shader = RadialGradient(s / 2f, s / 2f, s / 2f,
            intArrayOf(Color.argb(70, r, g, bl), Color.argb(80, r, g, bl), Color.argb(210, r, g, bl), Color.argb(0, r, g, bl)),
            floatArrayOf(0f, 0.7f, 0.9f, 1f), Shader.TileMode.CLAMP)
        Canvas(b).drawCircle(s / 2f, s / 2f, s / 2f, pp)
        return b
    }

    fun feed(m: FloatArray) { System.arraycopy(m, 0, cam, 0, min(m.size, cam.size)) }
    fun feedLuma(l: FloatArray) { System.arraycopy(l, 0, luma, 0, min(l.size, luma.size)) }

    private fun hsv(h: Float, s: Float, v: Float): Int {
        hsvBuf[0] = ((h % 360f) + 360f) % 360f; hsvBuf[1] = s; hsvBuf[2] = v
        return Color.HSVToColor(hsvBuf)
    }
    private fun hueIdx(h: Float) = ((((h % 360f) + 360f) % 360f) / 10f).toInt() % 36
    private fun gauss() = rnd.nextGaussian().toFloat()

    private fun mAt(x: Float, y: Float): Float {
        val gx = (x / width * GW).toInt().coerceIn(0, GW - 1)
        val gy = (y / height * GH).toInt().coerceIn(0, GH - 1)
        return mm[gy * GW + gx]
    }
    private fun lumaAt(x: Float, y: Float): Float {
        val gx = (x / width * LW).toInt().coerceIn(0, LW - 1)
        val gy = (y / height * LH).toInt().coerceIn(0, LH - 1)
        return luma[gy * LW + gx]
    }

    private fun sprite(c: Canvas, b: Bitmap, x: Float, y: Float, r: Float, a: Float) {
        if (a <= 0.004f) return
        add.alpha = (a * 255f).toInt().coerceIn(0, 255)
        dst.set(x - r, y - r, x + r, y + r)
        c.drawBitmap(b, null, dst, add)
    }

    private fun stroke(c: Canvas, pa: Path, wDp: Float, col: Int, a: Int) {
        if (wDp <= 0.05f) return
        addStroke.strokeWidth = wDp * dp; addStroke.color = col; addStroke.alpha = a.coerceIn(0, 255)
        c.drawPath(pa, addStroke)
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
            q.ox = q.x; q.oy = q.y
            q.x += q.vx * dt; q.y += q.vy * dt
            val d = max(0f, 1f - drag * dt)
            q.vx *= d; q.vy *= d
            q.life += dt
            if (trail) q.push()
            if (q.life >= q.max || q.x < -80 || q.x > width + 80 || q.y < -80 || q.y > height + 80) it.remove()
        }
    }

    private fun ensureBuf(): Bitmap {
        val bw = max(1, width / 2); val bh = max(1, height / 2)
        var b = buf
        if (b == null || b.width != bw || b.height != bh) {
            b?.recycle(); b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            buf = b; bufCanvas = Canvas(b)
        }
        return b
    }
    private fun resetBuf() { buf?.recycle(); buf = null; bufCanvas = null }

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
            Effect.TESLA -> tesla(c, w, h)
            Effect.EMBER -> ember(c, dt)
            Effect.TRACE -> trace(c, w, h, dt)
            Effect.SILK, Effect.VORTEX -> silkVortex(c, w, h, dt)
            Effect.NEBULA -> nebula(c, w, h, dt)
            Effect.OPAL -> opal(c, w, h)
            Effect.RESONANCE -> resonance(c, w, h)
            Effect.DAPPLE -> dapple(c, w, h)
            Effect.STRINGS -> strings(c, w, h, dt)
        }
        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------ FIREFLIES: blinking, drifting bokeh lights
    private fun fireflies(c: Canvas, dt: Float) {
        spawn(34f, dt, 300) { x, y ->
            val big = rnd.nextFloat() < 0.18f
            Part(x, y, gauss() * 10 * dp, gauss() * 10 * dp - 6 * dp, 5f + rnd.nextFloat() * 6f, rnd.nextFloat() * 360f,
                (if (big) 9f + rnd.nextFloat() * 9f else 2.5f + rnd.nextFloat() * 3.5f) * dp)
        }
        step(dt, false, 0.15f) { q ->
            q.vx += sin(t * 1.3f + q.hue) * 18 * dp * dt
            q.vy += cos(t * 1.1f + q.hue * 0.7f) * 14 * dp * dt - 3 * dp * dt
        }
        for (q in parts) {
            val age = q.life / q.max
            val fade = if (age < 0.1f) age / 0.1f else (1f - age) / 0.9f
            val blink = 0.45f + 0.55f * (0.5f + 0.5f * sin(t * (1.2f + q.hue % 3f) + q.hue))
            val hi = hueIdx(q.hue)
            if (q.size > 8 * dp) sprite(c, bokeh[hi], q.x, q.y, q.size * 2.2f, fade * blink * 0.5f)
            else {
                sprite(c, glow[hi], q.x, q.y, q.size * 4f, fade * blink * 0.55f)
                sprite(c, glow[36], q.x, q.y, q.size * 1.3f, fade * blink)
            }
        }
    }

    // ------------------------------------------------------------ TESLA: fractal lightning, branches, hand filaments
    private fun boltSeg(x1: Float, y1: Float, x2: Float, y2: Float, disp: Float, depth: Int) {
        if (depth == 0) { path.lineTo(x2, y2); return }
        val mx = (x1 + x2) / 2 + (rnd.nextFloat() - 0.5f) * disp
        val my = (y1 + y2) / 2 + (rnd.nextFloat() - 0.5f) * disp
        boltSeg(x1, y1, mx, my, disp / 2, depth - 1)
        boltSeg(mx, my, x2, y2, disp / 2, depth - 1)
    }

    private fun drawBolt(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, branch: Boolean) {
        val len = hypot(x2 - x1, y2 - y1)
        path.reset(); path.moveTo(x1, y1); boltSeg(x1, y1, x2, y2, len * 0.35f, 5)
        stroke(c, path, 7f, Color.rgb(60, 100, 255), 40)
        stroke(c, path, 2.6f, Color.rgb(140, 170, 255), 120)
        stroke(c, path, 1f, Color.rgb(240, 244, 255), 255)
        if (branch) for (b in 0..1) {
            val f = 0.25f + rnd.nextFloat() * 0.5f
            val bx = x1 + (x2 - x1) * f; val by = y1 + (y2 - y1) * f
            val a = atan2(y2 - y1, x2 - x1) + (rnd.nextFloat() - 0.5f) * 1.6f
            val bl = len * (0.3f + rnd.nextFloat() * 0.3f)
            drawBolt(c, bx, by, bx + cos(a) * bl, by + sin(a) * bl, false)
        }
    }

    private fun tesla(c: Canvas, w: Float, h: Float) {
        strong.clear()
        for (i in mm.indices) if (mm[i] > 0.3f) strong.add(i)
        if (strong.isEmpty()) return
        val cw = w / GW; val ch = h / GH
        var n = 0
        while (n < min(40, strong.size * 2)) {
            n++
            val i = strong[rnd.nextInt(strong.size)]
            val x = (i % GW + rnd.nextFloat()) * cw; val y = (i / GW + rnd.nextFloat()) * ch
            val a = rnd.nextFloat() * 6.283f; val l = (8f + rnd.nextFloat() * 18f) * dp
            path.reset(); path.moveTo(x, y); boltSeg(x, y, x + cos(a) * l, y + sin(a) * l, l * 0.5f, 2)
            stroke(c, path, 0.9f, Color.rgb(200, 215, 255), 200)
        }
        val bolts = min(5, strong.size / 3 + 1)
        for (k in 0 until bolts) {
            val i = strong[rnd.nextInt(strong.size)]; val j = strong[rnd.nextInt(strong.size)]
            val x1 = (i % GW + 0.5f) * cw; val y1 = (i / GW + 0.5f) * ch
            var x2 = (j % GW + 0.5f) * cw; var y2 = (j / GW + 0.5f) * ch
            if (hypot(x2 - x1, y2 - y1) < 20 * dp) { x2 += (rnd.nextFloat() - 0.5f) * 100 * dp; y2 += (rnd.nextFloat() - 0.5f) * 100 * dp }
            drawBolt(c, x1, y1, x2, y2, true)
        }
        sprite(c, glow[hueIdx(225f)], eCx * w, eCy * h, (70f + energy * 2f) * dp,
            min(0.5f, energy * 0.02f) * (0.7f + 0.3f * rnd.nextFloat()))
    }

    // ------------------------------------------------------------ EMBER: buoyant sparks that cool from white-yellow to red
    private fun ember(c: Canvas, dt: Float) {
        spawn(36f, dt, 400) { x, y ->
            Part(x, y, gauss() * 25 * dp, -(20f + rnd.nextFloat() * 70f) * dp, 1.0f + rnd.nextFloat() * 1.8f, 0f, (1.5f + rnd.nextFloat() * 2.5f) * dp)
        }
        step(dt, false, 0.35f) { q ->
            q.vy -= 70 * dp * dt
            q.vx += sin(q.y * 0.02f + t * 3f + q.size) * 55 * dp * dt
        }
        for (q in parts) {
            val f = 1f - q.life / q.max
            val hue = 10f + 40f * f
            val fl = 0.7f + 0.3f * sin(t * 22f + q.size * 9f)
            addStroke.strokeWidth = q.size * 0.5f; addStroke.color = hsv(hue, 0.9f, 1f); addStroke.alpha = (f * 230 * fl).toInt().coerceIn(0, 255)
            c.drawLine(q.x, q.y, q.x - q.vx * 0.06f, q.y - q.vy * 0.06f, addStroke)
            sprite(c, glow[hueIdx(hue)], q.x, q.y, q.size * 3.5f, f * 0.55f * fl)
            if (f > 0.6f) sprite(c, glow[36], q.x, q.y, q.size * 1.1f, (f - 0.6f) * 2f)
        }
    }

    // ------------------------------------------------------------ TRACE: luma halftone + persistent light-painting buffer
    private fun trace(c: Canvas, w: Float, h: Float, dt: Float) {
        p.style = Paint.Style.FILL; p.color = Color.argb(150, 0, 0, 0); c.drawRect(0f, 0f, w, h, p)
        val sp = 12f * dp
        var yy = sp / 2; var row = 0
        p.color = Color.WHITE
        while (yy < h) {
            var xx = sp / 2 + (if (row % 2 == 0) 0f else sp / 2)
            while (xx < w) {
                val l = lumaAt(xx, yy); val m = mAt(xx, yy)
                p.alpha = (90 + l * 140).toInt().coerceIn(0, 255)
                c.drawCircle(xx + sin(t * 6f + yy * 0.05f) * m * 7 * dp, yy, (0.4f + l * 3.2f + m * 1.5f) * dp, p)
                xx += sp
            }
            yy += sp * 0.866f; row++
        }
        val si = style.coerceIn(0, 2)
        val fade = intArrayOf(40, 16, 5)[si]
        val life = floatArrayOf(1.2f, 2.5f, 6f)[si]
        spawn(70f, dt, 500) { x, y -> Part(x, y, gauss() * 45 * dp, gauss() * 45 * dp, life * (0.6f + 0.4f * rnd.nextFloat()), 15f + rnd.nextFloat() * 30f, 1.2f * dp) }
        step(dt, false, 0.9f) { q -> if (si == 0) q.vy += 40 * dp * dt else q.vx += sin(q.y * 0.01f + t) * 30 * dp * dt }
        val b = ensureBuf(); val bc = bufCanvas ?: return
        fadePaint.color = Color.argb(fade, 0, 0, 0)
        bc.drawRect(0f, 0f, b.width.toFloat(), b.height.toFloat(), fadePaint)
        bc.save(); bc.scale(0.5f, 0.5f)
        for (q in parts) {
            val f = 1f - q.life / q.max
            bufPaint.color = hsv(q.hue, 0.85f, 1f); bufPaint.alpha = (f * 255).toInt().coerceIn(0, 255)
            bufPaint.strokeWidth = q.size * (0.6f + f)
            bc.drawLine(q.ox, q.oy, q.x, q.y, bufPaint)
        }
        bc.restore()
        add.alpha = 255
        dst.set(0f, 0f, w, h)
        c.drawBitmap(b, null, dst, add)
        for (q in parts) sprite(c, glow[hueIdx(q.hue)], q.x, q.y, 6 * dp, (1f - q.life / q.max) * 0.5f)
    }

    // ------------------------------------------------------------ SILK / VORTEX: layered glowing ribbons
    private fun addRibbon(x: Float, y: Float, hb: Float, vx: Float, vy: Float, life: Float) {
        val hue = hb + rnd.nextFloat() * 50f
        for (k in 0..2) parts.add(Part(x + gauss() * 3 * dp, y + gauss() * 3 * dp, vx, vy, life, hue + k * 4f, 0f))
    }

    private fun silkVortex(c: Canvas, w: Float, h: Float, dt: Float) {
        val vortex = effect == Effect.VORTEX
        val ccx = eCx * w; val ccy = eCy * h
        val hb = if (vortex) 190f else 325f
        if (parts.size < 60 && rnd.nextFloat() < 0.3f) addRibbon(rnd.nextFloat() * w, rnd.nextFloat() * h, hb, 0f, 0f, 3.5f)
        val cw = w / GW; val ch = h / GH
        for (i in mm.indices) {
            val m = mm[i]
            if (m < 0.2f || parts.size >= 300) continue
            if (rnd.nextFloat() < m * dt * 10f)
                addRibbon((i % GW + rnd.nextFloat()) * cw, (i / GW + rnd.nextFloat()) * ch, hb, gauss() * 40 * dp, gauss() * 40 * dp, 2.2f + rnd.nextFloat() * 1.8f)
        }
        step(dt, true, 0.55f) { q ->
            if (vortex) {
                val dx = q.x - ccx; val dy = q.y - ccy; val d = hypot(dx, dy) + 1f
                q.vx += (-dy / d * 260f - dx / d * 40f) * dp * dt
                q.vy += (dx / d * 260f - dy / d * 40f) * dp * dt
            } else {
                val a = sin(q.x * 0.004f + t * 0.6f) * 2f + cos(q.y * 0.005f - t * 0.4f) * 2f
                q.vx += cos(a) * 140f * dp * dt; q.vy += sin(a) * 140f * dp * dt
            }
        }
        for (q in parts) {
            if (q.n < 2) continue
            val f = (1f - q.life / q.max).coerceIn(0f, 1f)
            path.reset(); path.moveTo(q.tx[0], q.ty[0])
            for (i in 1 until q.n) path.lineTo(q.tx[i], q.ty[i])
            stroke(c, path, 7f * f, hsv(q.hue, 0.5f, 1f), (28 * f).toInt())
            stroke(c, path, 2.2f * f, hsv(q.hue, 0.45f, 1f), (120 * f).toInt())
            stroke(c, path, 0.8f, hsv(q.hue, 0.1f, 1f), (230 * f).toInt())
        }
        if (vortex) sprite(c, glow[hueIdx(200f)], ccx, ccy, (50f + energy) * dp, min(0.6f, energy * 0.03f))
    }

    // ------------------------------------------------------------ NEBULA: layered gas clouds + twinkling stars
    private fun nebula(c: Canvas, w: Float, h: Float, dt: Float) {
        if (parts.size < 14) parts.add(Part(rnd.nextFloat() * w, rnd.nextFloat() * h, gauss() * 6 * dp, gauss() * 6 * dp, 7f,
            250f + rnd.nextFloat() * 90f, (60f + rnd.nextFloat() * 100f) * dp))
        spawn(8f, dt, 120) { x, y -> Part(x, y, gauss() * 8 * dp, gauss() * 8 * dp, 4f + rnd.nextFloat() * 3f, 250f + rnd.nextFloat() * 90f, (40f + rnd.nextFloat() * 90f) * dp) }
        step(dt, false, 0.2f, null)
        for (q in parts) {
            val age = q.life / q.max
            val f = if (age < 0.2f) age / 0.2f else (1f - age) / 0.8f
            val hi = hueIdx(q.hue)
            sprite(c, glow[hi], q.x, q.y, q.size, f * 0.22f)
            sprite(c, glow[hi], q.x + q.size * 0.1f, q.y - q.size * 0.1f, q.size * 0.45f, f * 0.12f)
        }
        for (i in starX.indices) {
            val x = starX[i] * w; val y = starY[i] * h
            val tw = 0.5f + 0.5f * sin(t * (1f + starP[i] * 0.3f) + starP[i] * 10f)
            sprite(c, glow[36], x, y, (2.5f + mAt(x, y) * 5f) * dp, 0.3f + 0.5f * tw)
        }
    }

    // ------------------------------------------------------------ OPAL: thin-film shimmer + glitter
    private fun opal(c: Canvas, w: Float, h: Float) {
        val a = t * 0.3f; val dx = cos(a) * w; val dy = sin(a) * h
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(w / 2 - dx / 2, h / 2 - dy / 2, w / 2 + dx / 2, h / 2 + dy / 2,
            intArrayOf(Color.argb(40, 255, 180, 232), Color.argb(40, 180, 232, 255), Color.argb(40, 232, 255, 180), Color.argb(40, 255, 232, 180)),
            null, Shader.TileMode.MIRROR)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        for (i in 0..4) {
            val ang = t * 0.15f + i * 1.26f
            sprite(c, glow[hueIdx(t * 25f + i * 72f)], w * (0.5f + 0.35f * cos(ang * 1.3f + i)), h * (0.5f + 0.35f * sin(ang + i * 2f)), w * 0.55f, 0.22f)
        }
        for (i in starX.indices) {
            val x = starX[i] * w; val y = starY[i] * h
            val tw = 0.5f + 0.5f * sin(t * 2f + starP[i] * 7f)
            sprite(c, glow[hueIdx(t * 40f + i * 13f)], x, y, (2f + mAt(x, y) * 6f) * dp, tw * 0.9f)
        }
        val cw = w / GW; val ch = h / GH
        for (i in mm.indices) {
            if (mm[i] < 0.2f) continue
            sprite(c, glow[hueIdx(t * 60f + i * 7f)], (i % GW + 0.5f) * cw, (i / GW + 0.5f) * ch, cw * 1.6f, mm[i] * 0.5f)
        }
    }

    // ------------------------------------------------------------ RESONANCE: Chladni-style grain lattice
    private fun resonance(c: Canvas, w: Float, h: Float) {
        p.style = Paint.Style.FILL; p.color = Color.argb(120, 16, 10, 4); c.drawRect(0f, 0f, w, h, p)
        val step = w / 3.2f
        val x0 = 0.61f * step
        val y0 = (0.245f * h) % step - step
        val warm = Color.rgb(255, 226, 170)
        var cnt = 0
        var j = 0
        while (y0 + j * step < h + step) {
            for (i in -1..4) {
                val x = x0 + i * step; val y = y0 + j * step
                val m = mAt(x.coerceIn(0f, w - 1), y.coerceIn(0f, h - 1))
                val bright = 0.55f + 0.45f * min(1f, m * 2f + energy * 0.02f)
                val wob = 1f + (0.05f + 0.25f * m) * sin(t * 5f + i * 1.3f + j)
                if ((i + j) % 2 == 0) {
                    val r = 0.34f * step * wob
                    rf.set(x - r, y - r, x + r, y + r)
                    addStroke.strokeWidth = 12 * dp; addStroke.color = warm; addStroke.alpha = (22 * bright).toInt()
                    c.drawRoundRect(rf, r * 0.8f, r * 0.8f, addStroke)
                    repeat(90) {
                        if (cnt < 8900) {
                            val a = rnd.nextFloat() * 6.2832f; val ct = cos(a); val st = sin(a)
                            val rr = r * (1f + gauss() * 0.018f) * (1f + m * 0.12f * rnd.nextFloat())
                            pts[2 * cnt] = x + rr * sign(ct) * abs(ct).pow(0.5f)
                            pts[2 * cnt + 1] = y + rr * sign(st) * abs(st).pow(0.5f)
                            cnt++
                        }
                    }
                } else {
                    val l = 0.42f * step * (1f + 0.2f * m)
                    repeat(70) {
                        if (cnt < 8900) {
                            var u = rnd.nextFloat() * 2f - 1f
                            u = sign(u) * abs(u).pow(1.5f)
                            val dg = if (rnd.nextBoolean()) 1f else -1f
                            pts[2 * cnt] = x + u * l + gauss() * 0.012f * step
                            pts[2 * cnt + 1] = y + u * l * dg + gauss() * 0.012f * step
                            cnt++
                        }
                    }
                    sprite(c, glow[36], x, y, 0.2f * step, 0.5f * bright)
                }
            }
            j++
        }
        addStroke.color = warm
        addStroke.strokeWidth = 3.2f * dp; addStroke.alpha = 70; c.drawPoints(pts, 0, cnt * 2, addStroke)
        addStroke.strokeWidth = 1.4f * dp; addStroke.alpha = 240; c.drawPoints(pts, 0, cnt * 2, addStroke)
    }

    // ------------------------------------------------------------ DAPPLE: soft-edged leaf shadows in warm light
    private fun buildLeaves() {
        leaves.clear(); stems.clear()
        val r = Random(11L + style)
        when (style) {
            0 -> for (s in 0..1) for (k in 0..9)
                leaves.add(Leaf(0.78f + 0.1f * s, -0.02f, 60f + k * 14f + s * 10f, 0.42f, 0.05f, r.nextFloat() * 6f))
            1 -> vine(r, 3, 16, 0.12f, 0.3f, 0.05f, 0.12f, 0.045f, 55f)
            2 -> vine(r, 2, 30, 0.2f, 0.5f, 0.08f, 0.07f, 0.022f, 70f)
            else -> vine(r, 2, 11, 0.25f, 0.45f, 0.04f, 0.16f, 0.06f, 50f)
        }
    }

    private fun vine(r: Random, count: Int, n: Int, sx0: Float, dsx: Float, amp: Float, len: Float, wid: Float, spread: Float) {
        for (s in 0 until count) {
            val sx = sx0 + dsx * s
            val pp = FloatArray(2 * (n + 1))
            for (k in 0..n) {
                val x = sx + amp * sin(k * 0.6f + s); val y = k / n.toFloat()
                pp[2 * k] = x; pp[2 * k + 1] = y
                val side = if (k % 2 == 0) 1 else -1
                leaves.add(Leaf(x, y, 90f - side * spread + r.nextFloat() * 20f, len, wid, r.nextFloat() * 6f))
            }
            stems.add(pp)
        }
    }

    private fun dapple(c: Canvas, w: Float, h: Float) {
        p.style = Paint.Style.FILL; p.color = Color.argb(26, 255, 205, 130); c.drawRect(0f, 0f, w, h, p)
        for (i in 0..9)
            sprite(c, glow[4], w * (0.5f + 0.4f * sin(t * 0.1f + i * 1.7f)), h * (0.5f + 0.4f * cos(t * 0.13f + i * 2.3f)), w * 0.22f, 0.06f)
        val wind = sin(t * 0.9f) * 0.006f + sin(t * 2.1f) * 0.003f
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 2.2f * dp; p.color = Color.argb(120, 24, 28, 40)
        for (s in stems) {
            path.reset()
            val n = s.size / 2
            for (k in 0 until n) {
                val px = (s[2 * k] + wind * (k.toFloat() / n)) * w; val py = s[2 * k + 1] * h
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            c.drawPath(path, p)
        }
        p.style = Paint.Style.FILL
        for (l in leaves) {
            val lx = l.x * w; val ly = l.y * h
            val m = mAt(lx.coerceIn(0f, w - 1), ly.coerceIn(0f, h - 1))
            val ang = l.rot + sin(t * 1.1f + l.ph) * 4f + wind * 900f * l.y + m * 35f * sin(t * 7f + l.ph)
            c.save(); c.translate(lx, ly); c.rotate(ang); c.scale(l.len * w, l.wid * w * 2f)
            p.color = Color.argb(38, 28, 32, 46); c.save(); c.scale(1.18f, 1.5f); c.drawPath(leafPath, p); c.restore()
            p.color = Color.argb(55, 28, 32, 46); c.save(); c.scale(1.08f, 1.22f); c.drawPath(leafPath, p); c.restore()
            p.color = Color.argb(95, 24, 28, 40); c.drawPath(leafPath, p)
            c.restore()
        }
    }

    // ------------------------------------------------------------ STRINGS: plucked, damped vibrating strings with shading
    private fun strings(c: Canvas, w: Float, h: Float, dt: Float) {
        val horiz = style == 1 || style == 3
        val si = style.coerceIn(0, 3)
        p.style = Paint.Style.FILL; p.color = Color.argb(46, 176, 138, 90); c.drawRect(0f, 0f, w, h, p)
        val gap = floatArrayOf(26f, 8f, 22f, 7f)[si] * dp
        val sw = floatArrayOf(1.4f, 3f, 7f, 3.6f)[si] * dp
        val body = intArrayOf(Color.rgb(214, 178, 92), Color.rgb(232, 214, 184), Color.rgb(176, 170, 110), Color.rgb(210, 175, 130))[si]
        val dark = intArrayOf(Color.rgb(90, 64, 28), Color.rgb(120, 100, 80), Color.rgb(70, 64, 30), Color.rgb(100, 72, 46))[si]
        val light = intArrayOf(Color.rgb(255, 240, 170), Color.rgb(255, 250, 235), Color.rgb(235, 235, 170), Color.rgb(255, 235, 205))[si]
        val span = if (horiz) h else w; val len = if (horiz) w else h
        val decay = 0.965f.pow(dt * 60f)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        var pos = gap / 2; var idx = 0
        while (pos < span && idx < amp.size) {
            var exc = 0f
            for (s in 0..7) {
                val a = len * (s + 0.5f) / 8f
                val x = if (horiz) a else pos; val y = if (horiz) pos else a
                exc = max(exc, mAt(x.coerceIn(0f, w - 1f), y.coerceIn(0f, h - 1f)))
            }
            if (exc > 0.2f) amp[idx] = max(amp[idx], exc * 20f * dp)
            amp[idx] *= decay
            val a0 = amp[idx]; val fr = 14f + (idx % 5) * 1.7f
            path.reset()
            for (s in 0..40) {
                val a = len * s / 40f
                val env = sin(PI.toFloat() * s / 40f)
                val off = a0 * (env * sin(t * fr) + 0.4f * sin(2f * PI.toFloat() * s / 40f) * sin(t * fr * 2.1f)) +
                        sin(t * 1.4f + idx * 0.3f + s * 0.35f) * 0.8f * dp
                val px = if (horiz) a else pos + off; val py = if (horiz) pos + off else a
                if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            val tw = sw * (1f - 0.45f * min(1f, a0 / (10f * dp)))
            val odd = if (idx % 2 == 0) 1f else 0.88f
            val sh = 1.3f * dp
            c.save(); c.translate(if (horiz) 0f else sh, if (horiz) sh else 0f)
            p.strokeWidth = tw + 0.6f * dp; p.color = Color.argb(110, Color.red(dark), Color.green(dark), Color.blue(dark)); c.drawPath(path, p)
            c.restore()
            p.strokeWidth = tw
            p.color = Color.rgb((Color.red(body) * odd).toInt(), (Color.green(body) * odd).toInt(), (Color.blue(body) * odd).toInt())
            c.drawPath(path, p)
            c.save(); c.translate(if (horiz) 0f else -0.5f * dp, if (horiz) -0.5f * dp else 0f)
            p.strokeWidth = max(0.6f * dp, tw * 0.28f); p.color = Color.argb(150, Color.red(light), Color.green(light), Color.blue(light))
            c.drawPath(path, p)
            c.restore()
            pos += gap; idx++
        }
    }
}
