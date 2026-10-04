package com.grandrr.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.*
import java.io.File
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private val effects = Effect.values()
    private var idx = Effect.FIREFLIES.ordinal
    private var styleIdx = 0
    private var front = false
    private var haptics = true
    @Volatile private var imported = false

    private lateinit var root: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var importView: ImageView
    private lateinit var fx: EffectView
    private lateinit var flash: View
    private lateinit var topBar: LinearLayout
    private lateinit var bottomCol: LinearLayout
    private lateinit var flipBtn: IconButton
    private lateinit var closeBtn: IconButton
    private lateinit var speakerBtn: IconButton
    private lateinit var recBtn: RecordButton
    private lateinit var hint: TextView
    private lateinit var styleRow: LinearLayout
    private lateinit var pillBox: LinearLayout
    private lateinit var carL: TextView
    private lateinit var carC: TextView
    private lateinit var carR: TextView

    private var fxRec: FxRecorder? = null
    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val r = fxRec ?: return
            r.frame(System.nanoTime())
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private val analysisExec = Executors.newSingleThreadExecutor()
    private val sound = SoundEngine()
    private val prevL = FloatArray(EffectView.GW * EffectView.GH)
    private val outM = FloatArray(EffectView.GW * EffectView.GH)
    private val lumaOut = FloatArray(EffectView.LW * EffectView.LH) { 0.5f }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasCamera()) startCamera() else toast("Camera permission is needed")
    }
    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { loadImport(it) } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun hasCamera() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private fun tick(v: View) { if (haptics) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildUi()
        styleIdx = effects[idx].defStyle
        updateEffect()
        val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= 28) perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (hasCamera()) startCamera() else permLauncher.launch(perms.toTypedArray())
    }

    // ---------------------------------------------------------------- UI
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        importView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        fx = EffectView(this).apply { onNote = { x, v -> effects[idx].timbre?.let { sound.play(x, v, it) } } }
        flash = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f }
        root.addView(previewView, -1, -1)
        root.addView(importView, -1, -1)
        root.addView(fx, -1, -1)

        // top bar
        topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun btn(k: IconButton.Kind, click: (View) -> Unit): IconButton =
            IconButton(this, k).also { b ->
                b.setOnClickListener { tick(it); click(it) }
                topBar.addView(b, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(10) })
            }
        btn(IconButton.Kind.FLOWER) { showSettings() }
        flipBtn = btn(IconButton.Kind.FLIP) { front = !front; startCamera() }
        closeBtn = btn(IconButton.Kind.CLOSE) { clearImport() }.also { it.visibility = View.GONE }
        btn(IconButton.Kind.PERSON) { toast("Person-only mode needs a segmentation model — coming soon") }
        btn(IconButton.Kind.HELP) { showHelp() }
        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        IconButton(this, IconButton.Kind.GALLERY).also { g ->
            g.setOnClickListener { tick(it); picker.launch("image/*") }
            topBar.addView(g, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // bottom column
        bottomCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        hint = TextView(this).apply {
            setTextColor(Color.argb(230, 255, 255, 255)); textSize = 16f; gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 2f, Color.argb(160, 0, 0, 0)); setPadding(dp(28), 0, dp(28), 0)
        }
        bottomCol.addView(hint, LinearLayout.LayoutParams(-1, -2))

        styleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pillBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(30).toFloat(); setColor(Color.argb(70, 40, 40, 40)); setStroke(dp(1), Color.argb(60, 255, 255, 255)) }
        }
        styleRow.addView(pillBox)
        styleRow.addView(IconButton(this, IconButton.Kind.SHUFFLE).apply {
            setOnClickListener { tick(it); val n = effects[idx].styles.size; if (n > 0) { styleIdx = (0 until n).random(); applyStyle() } }
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(8) })
        bottomCol.addView(styleRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })

        // effect carousel
        fun label(white: Boolean) = TextView(this).apply {
            gravity = Gravity.CENTER; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.06f
            setTextColor(if (white) Color.argb(235, 255, 255, 255) else Color.rgb(245, 240, 42)); maxLines = 1
        }
        carL = label(true); carC = label(false); carR = label(true)
        carC.background = GradientDrawable().apply { cornerRadius = dp(40).toFloat(); setColor(Color.argb(60, 255, 255, 255)); setStroke(dp(2), Color.rgb(229, 217, 60)) }
        val carousel = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        carousel.addView(carL, LinearLayout.LayoutParams(0, -1, 1f))
        carousel.addView(carC, LinearLayout.LayoutParams(0, -1, 1f))
        carousel.addView(carR, LinearLayout.LayoutParams(0, -1, 1f))
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(a: MotionEvent?, b: MotionEvent, vx: Float, vy: Float): Boolean { go(if (vx < 0) 1 else -1); return true }
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (e.x < carousel.width / 3f) go(-1) else if (e.x > carousel.width * 2f / 3f) go(1); return true
            }
        })
        carousel.setOnTouchListener { _, e -> gd.onTouchEvent(e) }
        bottomCol.addView(carousel, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(14); marginStart = dp(8); marginEnd = dp(8) })

        // controls
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun cell(v: View, size: Int) = FrameLayout(this).also {
            it.addView(v, FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER))
            controls.addView(it, LinearLayout.LayoutParams(0, dp(92), 1f))
        }
        cell(IconButton(this, IconButton.Kind.DOT, Color.argb(110, 40, 40, 40)).apply { setOnClickListener { tick(it); takePhoto() } }, 56)
        recBtn = RecordButton(this).apply { setOnClickListener { tick(it); toggleRecord() } }
        cell(recBtn, 84)
        speakerBtn = IconButton(this, IconButton.Kind.SPEAKER, Color.argb(210, 40, 70, 45)).apply {
            setOnClickListener { tick(it); setSound(!sound.enabled) }
        }
        cell(speakerBtn, 56)
        bottomCol.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        root.addView(bottomCol, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.addView(flash, -1, -1)
        flash.isClickable = false

        root.setOnApplyWindowInsetsListener { _, ins ->
            @Suppress("DEPRECATION")
            topBar.setPadding(dp(16), ins.systemWindowInsetTop + dp(10), dp(16), 0)
            @Suppress("DEPRECATION")
            bottomCol.setPadding(0, 0, 0, ins.systemWindowInsetBottom + dp(12))
            ins
        }
        setContentView(root)
    }

    private fun setSound(on: Boolean) {
        sound.enabled = on
        speakerBtn.kind = if (on) IconButton.Kind.SPEAKER else IconButton.Kind.MUTE
        speakerBtn.invalidate()
    }

    private fun go(d: Int) {
        val n = effects.size
        idx = (idx + d + n) % n
        styleIdx = effects[idx].defStyle
        if (haptics) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        updateEffect()
    }

    private fun updateEffect() {
        val e = effects[idx]
        val n = effects.size
        carL.text = effects[(idx + n - 1) % n].label; carC.text = e.label; carR.text = effects[(idx + 1) % n].label
        fx.effect = e
        styleRow.visibility = if (e.styles.isEmpty()) View.GONE else View.VISIBLE
        buildPills()
        applyStyle()
    }

    private fun applyStyle() {
        val e = effects[idx]
        fx.style = styleIdx
        val sb = StringBuilder()
        if (e.line1.isNotEmpty()) sb.append("◉  ").append(e.line1).append('\n')
        if (e.sound && e.music) sb.append("♪  ")
        sb.append(e.line2.replace("%s", e.styles.getOrNull(styleIdx)?.lowercase() ?: ""))
        hint.text = sb.toString()
        buildPills()
    }

    private fun buildPills() {
        pillBox.removeAllViews()
        for ((i, s) in effects[idx].styles.withIndex()) {
            val tv = TextView(this).apply {
                text = s; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                if (i == styleIdx) {
                    setTextColor(Color.BLACK)
                    background = GradientDrawable().apply { cornerRadius = dp(30).toFloat(); setColor(Color.rgb(245, 240, 42)) }
                } else setTextColor(Color.WHITE)
                setOnClickListener { tick(it); styleIdx = i; applyStyle() }
            }
            pillBox.addView(tv)
        }
    }

    private fun showSettings() {
        val items = arrayOf("Sound", "Haptic feedback")
        AlertDialog.Builder(this).setTitle("Settings")
            .setMultiChoiceItems(items, booleanArrayOf(sound.enabled, haptics)) { _, which, on ->
                if (which == 0) setSound(on) else haptics = on
            }.setPositiveButton("Done", null).show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this).setTitle("Grandrr")
            .setMessage("• Move in front of the camera (or touch the screen) to play the effect.\n" +
                    "• Swipe the effect names, or tap the left/right label, to switch.\n" +
                    "• Pills under the hint change an effect's style; the shuffle button picks one at random.\n" +
                    "• White dot = photo, red button = record video, green speaker = sound on/off.\n" +
                    "• Top right imports a photo to play effects on (touch to stir).")
            .setPositiveButton("OK", null).show()
    }

    // ---------------------------------------------------------------- import
    private fun loadImport(uri: Uri) {
        try {
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
            } ?: return
            importView.setImageBitmap(bmp); importView.visibility = View.VISIBLE
            imported = true; closeBtn.visibility = View.VISIBLE; flipBtn.visibility = View.GONE
            fx.feed(FloatArray(EffectView.GW * EffectView.GH))
        } catch (e: Exception) { toast("Couldn't open image") }
    }

    private fun clearImport() {
        imported = false; importView.visibility = View.GONE; importView.setImageDrawable(null)
        closeBtn.visibility = View.GONE; flipBtn.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- camera
    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            try { bind(f.get()) } catch (e: Exception) { toast("Camera error: ${e.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bind(p: ProcessCameraProvider) {
        val pv = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val an = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            .also { it.setAnalyzer(analysisExec) { img -> analyze(img) } }
        val sel = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        p.unbindAll()
        p.bindToLifecycle(this, sel, pv, an)
    }

    /** Frame-difference motion map (24x40) and luma map (48x84) in display orientation. */
    private fun analyze(img: ImageProxy) {
        try {
            val pl = img.planes[0]; val buf = pl.buffer; val rs = pl.rowStride
            val w = img.width; val h = img.height; val rot = img.imageInfo.rotationDegrees
            fun sample(u0: Float, v: Float): Float {
                val u = if (front) 1f - u0 else u0
                val sx: Float; val sy: Float
                when (rot) {
                    0 -> { sx = u; sy = v }
                    90 -> { sx = v; sy = 1f - u }
                    180 -> { sx = 1f - u; sy = 1f - v }
                    else -> { sx = 1f - v; sy = u }
                }
                return (buf.get((sy * (h - 1)).toInt() * rs + (sx * (w - 1)).toInt()).toInt() and 0xFF).toFloat()
            }
            val gw = EffectView.GW; val gh = EffectView.GH
            for (gy in 0 until gh) for (gx in 0 until gw) {
                val l = sample((gx + 0.5f) / gw, (gy + 0.5f) / gh)
                val i = gy * gw + gx
                val d = abs(l - prevL[i]); prevL[i] = l
                outM[i] = max(((d - 7f) / 35f).coerceIn(0f, 1f), outM[i] * 0.75f)
            }
            val lw = EffectView.LW; val lh = EffectView.LH
            for (ly in 0 until lh) for (lx in 0 until lw) {
                val i = ly * lw + lx
                lumaOut[i] = lumaOut[i] * 0.6f + 0.4f * sample((lx + 0.5f) / lw, (ly + 0.5f) / lh) / 255f
            }
            if (!imported) { fx.feed(outM); fx.feedLuma(lumaOut) }
        } finally { img.close() }
    }

    // ---------------------------------------------------------------- capture
    private fun takePhoto() {
        val w = root.width; val h = root.height
        if (w == 0) return
        val src: Bitmap? = if (imported) (importView.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap else previewView.bitmap
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); c.drawColor(Color.BLACK)
        if (src != null) {
            if (imported) {
                val s = max(w / src.width.toFloat(), h / src.height.toFloat())
                val dw = src.width * s; val dh = src.height * s
                c.drawBitmap(src, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), null)
            } else c.drawBitmap(src, null, Rect(0, 0, w, h), null)
        }
        fx.draw(c)
        savePhoto(out)
        flash.animate().cancel(); flash.alpha = 0.7f; flash.animate().alpha(0f).setDuration(250).start()
    }

    private fun savePhoto(bmp: Bitmap) {
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Grandrr_${stamp()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Grandrr")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        if (uri == null) { toast("Save failed"); return }
        contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        toast("Saved to Pictures/Grandrr")
    }

    /** Records camera + effect together, so the filter is part of the saved video. */
    private fun toggleRecord() {
        val running = fxRec
        if (running != null) {
            running.stop(); fxRec = null; recBtn.recording = false
            toast("Saving video…")
            return
        }
        val w = root.width; val h = root.height
        if (w == 0 || h == 0) return
        val encW = 720
        val encH = ((encW.toFloat() * h / w) / 16f).toInt() * 16
        val tmp = File(cacheDir, "rec_${stamp()}.mp4")
        val rec = FxRecorder(encW, encH, w, h, hasMic(), tmp,
            drawFrame = { c ->
                if (imported) importView.draw(c) else previewView.draw(c)
                fx.draw(c)
            },
            onFinished = { ok -> runOnUiThread { if (ok) saveVideo(tmp) else { tmp.delete(); toast("Recording failed") } } })
        if (!rec.start()) { toast("This device can't record video with effects"); return }
        fxRec = rec
        recBtn.recording = true
        Choreographer.getInstance().postFrameCallback(frameCb)
        root.postDelayed({ if (fxRec === rec) toggleRecord() }, 60_000)
    }

    private fun saveVideo(file: File) {
        Thread {
            try {
                val cv = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "Grandrr_${stamp()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Grandrr")
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
                if (uri == null) { runOnUiThread { toast("Save failed") }; return@Thread }
                contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
                runOnUiThread { toast("Saved to Movies/Grandrr (with effect)") }
            } catch (e: Exception) {
                runOnUiThread { toast("Save failed") }
            } finally { file.delete() }
        }.start()
    }

    override fun onDestroy() { super.onDestroy(); fxRec?.stop(); fxRec = null; analysisExec.shutdown() }
}
