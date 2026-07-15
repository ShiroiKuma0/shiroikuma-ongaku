package app.simple.felicity.engine.views

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import app.simple.felicity.engine.managers.MusicPulse
import app.simple.felicity.manager.SharedPreferences.registerSharedPreferencesListener
import app.simple.felicity.manager.SharedPreferences.unregisterListener
import app.simple.felicity.preferences.MeteorPreferences
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * 音楽端灯 — native edge meteors (fork-only): neon comet ribbons orbiting the screen
 * perimeter inside a rounded-rect band, glowing, beat-locked to the music via [MusicPulse].
 *
 * Direct port of the 自由作業盤 reference renderer + simulation (`EdgeMeteors.kt`,
 * 音楽反応 v3, tempo-locked) — reproduce the look EXACTLY; see hand-off.md A.2–A.4 for
 * every constant. Differences from the reference are deliberate and small:
 *  - Plain [View] + [Choreographer] instead of Compose (this app is view-based).
 *  - Knobs come from [MeteorPreferences] (re-read on shared-pref change) instead of
 *    per-frame scene config, and are raw pixels (the reference used dp).
 *  - Audio comes from [MusicPulse] (a sample-accurate ExoPlayer pipeline tap) instead of
 *    the lossy `Visualizer` capture — the 3s silence fallback of the reference is
 *    obsolete and intentionally omitted (the tap always hears the decoder).
 *
 * Renderer rules (the hard-won part — do not regress, hand-off.md A.3):
 *  - NEVER draw wide anti-aliased paths (stroked or blurred) — HWUI software-rasterizes
 *    them into mask textures every frame (~190% CPU measured in the reference).
 *  - NEVER clip with an even-odd ring clipPath — full-window coverage mask raster per frame.
 *  - No BlurMaskFilter — the glow is layered capsules, not blur.
 *  - The band hole is punched with ONE PorterDuff.CLEAR round-rect after all ribbons.
 *  - The corner masks are a STATIC path, rebuilt only when size/radius changes.
 *  - The FPS cap skips the frame callback entirely — a skipped frame costs nothing.
 */
class EdgeMeteorsView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null
) : View(context, attrs), SharedPreferences.OnSharedPreferenceChangeListener, Choreographer.FrameCallback {

    private data class Knobs(
            val palette: IntArray,           // opaque 0xFFRRGGBB entries
            val pad: Float,                  // band half-thickness in px (capsule inflate)
            val radius: Float,               // inner-hole corner radius in px (band outer edge)
            val corner: Float,               // black corner-mask radius in px
            val periodS: Float,              // base seconds for one full loop of the perimeter
            val glowPx: Float,               // innermost halo half-width basis in px
            val glowLayers: Int,             // extra glow passes (total = 3 + this)
            val glowSpread: Float,           // outermost glow reach multiplier
            val glowStrength: Float,         // halo opacity multiplier
            val reverse: Boolean,
            val count: Int,                  // target ribbon count (spawn cap ×1.5)
            val spawnMs: Float,              // spawn interval
            val minLen: Float, val maxLen: Float,   // ribbon length as fraction of the perimeter
            val speedMin: Float, val speedMax: Float, val speedChangeS: Float,
            val maxFps: Float,               // 0 = every vsync
            val headGlow: Float,             // head star size multiplier, 0 = off
            val twinkle: Float,              // 0..1 shimmer depth
            val hueDrift: Float,             // deg/s colour evolution
            val reactive: Boolean,           // 音楽反応 master
            val reactGain: Float, val reactPulse: Float, val reactKick: Float, val reactSharp: Float,
    )

    private class Ribbon {
        var pos = 0f; var len = 0f
        var col = 0                          // spawn colour (for the glow's solid halo tint)
        var r = 0; var g = 0; var b = 0      // spawn rgb
        var h = 0f; var s = 0f; var l = 0f   // spawn hsl (hue-drift base)
        var tf = 0f; var tp = 0f             // twinkle frequency (rad/ms) + phase
        var speed = 0f                       // perimeter fraction per ms (before speedMul)
        var age = 0f; var life = 0f
        var intn = 0f
        // per-frame draw values, computed once in step() and reused by the draw pass:
        var fa = 0f; var fr = 0; var fg = 0; var fb = 0
    }

    @Volatile
    private var knobs: Knobs = loadKnobs()

    // --- simulation state (mutated only from the frame callback, read by onDraw) ---
    private val ribbons = ArrayList<Ribbon>()
    private var speedMul = 1f; private var speedTarget = 1f; private var speedNextMs = 0.0
    private var lvMin = 1f; private var lvMax = 0f; private var wob = 1f
    private var pulse = 1f
    private var lastSpawnMs = 0.0
    private var px = 0f; private var py = 0f             // pt() out-params (single-threaded)
    private var cr = 0; private var cg = 0; private var cb = 0   // hsl2rgb out-params

    // computeRuns output: up to 4 axis-aligned runs × (x1,y1,x2,y2,f0,f1) — f is the 0..1
    // fraction of the ribbon's length at the run's start/end (drives the tail→head taper).
    private var runN = 0
    private val runs = FloatArray(4 * 6)

    // --- frame loop ---
    private var running = false
    private var lastStepNs = 0L
    private var lastRenderNs = 0L

    // --- paints (FILL only: every ribbon run is a GPU-native round-rect capsule) ---
    private val glowPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val corePaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
    private val headPaint = Paint().apply { isAntiAlias = true }

    // The band is NOT a clipPath: an even-odd ring clip makes HWUI rasterize a full-window
    // coverage mask every frame. Instead we draw unclipped and then ERASE the inner hole
    // with one native PorterDuff.CLEAR round-rect — same crisp band, no mask raster.
    private val clearPaint = Paint().apply { isAntiAlias = true; xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    // Physically rounded screen corners: opaque-black masks covering the region between
    // each corner square and the outer round-rect, drawn OVER the ribbons. The path is
    // STATIC (rebuilt only on size/radius change), so HWUI's path cache rasterizes it once.
    private val cornerMask = Path()
    private val cornerTmp = Path()
    private val cornerKey = floatArrayOf(-1f, -1f, -1f)
    private val cornerPaint = Paint().apply { isAntiAlias = true; color = 0xFF000000.toInt() }

    init {
        // PorterDuff.CLEAR must erase to transparency within this view's own layer, not
        // punch a black hole through the window — render the view into a hardware layer.
        setLayerType(LAYER_TYPE_HARDWARE, null)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // ------------------------------------------------------------------ knobs

    private fun loadKnobs(): Knobs {
        return Knobs(
                palette = parsePalette(MeteorPreferences.getPalette()),
                pad = MeteorPreferences.getPadding(),
                radius = MeteorPreferences.getRadius(),
                corner = MeteorPreferences.getCornerMask(),
                periodS = MeteorPreferences.getPeriodS().coerceAtLeast(0.1f),
                glowPx = MeteorPreferences.getGlow(),
                glowLayers = MeteorPreferences.getGlowLayers().coerceIn(0, 6),
                glowSpread = MeteorPreferences.getGlowSpread(),
                glowStrength = MeteorPreferences.getGlowStrength(),
                reverse = MeteorPreferences.isReversed(),
                count = MeteorPreferences.getCount().coerceAtLeast(1),
                spawnMs = MeteorPreferences.getSpawnMs().coerceAtLeast(1f),
                minLen = MeteorPreferences.getMinLen(),
                maxLen = MeteorPreferences.getMaxLen(),
                speedMin = MeteorPreferences.getSpeedMin(),
                speedMax = MeteorPreferences.getSpeedMax(),
                speedChangeS = MeteorPreferences.getSpeedChangeS().coerceAtLeast(0.1f),
                maxFps = MeteorPreferences.getMaxFps(),
                headGlow = MeteorPreferences.getHeadGlow(),
                twinkle = MeteorPreferences.getTwinkle(),
                hueDrift = MeteorPreferences.getHueDrift(),
                reactive = MeteorPreferences.isReactive(),
                reactGain = MeteorPreferences.getReactGain(),
                reactPulse = MeteorPreferences.getReactPulse(),
                reactKick = MeteorPreferences.getReactKick(),
                reactSharp = MeteorPreferences.getReactSharp(),
        )
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key != null && key.startsWith(MeteorPreferences.KEY_PREFIX)) {
            knobs = loadKnobs()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerSharedPreferencesListener(this)
        knobs = loadKnobs()
        updateRunning()
    }

    override fun onDetachedFromWindow() {
        unregisterListener(this)
        stopLoop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateRunning()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateRunning()
    }

    private fun updateRunning() {
        if (isAttachedToWindow && isShown && windowVisibility == VISIBLE) startLoop() else stopLoop()
    }

    private fun startLoop() {
        if (running) return
        running = true
        lastStepNs = 0L
        lastRenderNs = 0L
        MusicPulse.acquire()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stopLoop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        MusicPulse.release()
    }

    /**
     * The frame loop: sim-step + invalidate. An fps-capped skip does NOTHING (no sim step,
     * no state write, no draw, no RenderThread work) — a skipped frame is genuinely free.
     */
    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        Choreographer.getInstance().postFrameCallback(this)
        val k = knobs
        val minFrameNs = if (k.maxFps > 0f) (1_000_000_000.0 / k.maxFps).toLong() else 0L
        if (minFrameNs > 0 && lastRenderNs != 0L && frameTimeNanos - lastRenderNs < minFrameNs - 1_000_000) return
        lastRenderNs = frameTimeNanos
        val dt = if (lastStepNs == 0L) 16f else ((frameTimeNanos - lastStepNs) / 1_000_000f).coerceAtMost(64f)
        lastStepNs = frameTimeNanos
        step(k, frameTimeNanos / 1_000_000.0, dt)
        invalidate()
    }

    // ------------------------------------------------------------------ simulation

    /** Map t∈[0,1] to a point on the w×h rect perimeter (top→right→bottom→left). */
    private fun pt(t: Float, w: Float, h: Float) {
        val perim = 2f * (w + h)
        var d = t * perim
        if (d < w) { px = d; py = 0f; return }; d -= w
        if (d < h) { px = w; py = d; return }; d -= h
        if (d < w) { px = w - d; py = h; return }; d -= w
        px = 0f; py = h - d
    }

    /**
     * Split the ribbon's perimeter span into axis-aligned runs, breaking ONLY at the screen
     * corners it crosses (so 1–3 runs for real lengths). Each run is then drawn as a
     * GPU-native capsule ([Canvas.drawRoundRect]).
     */
    private fun computeRuns(pos: Float, len: Float, w: Float, h: Float) {
        val perim = 2f * (w + h)
        val c0 = w / perim; val c1 = (w + h) / perim; val c2 = (2 * w + h) / perim
        runN = 0
        val end = pos + len
        var t0 = pos
        var k = 0
        while (runN < 3) {
            val base = (k / 4).toFloat()
            val c = base + when (k % 4) { 0 -> c0; 1 -> c1; 2 -> c2; else -> 1f }
            k++
            if (c <= t0) continue
            if (c >= end) break
            emitRun(t0, c, pos, len, w, h)
            t0 = c
        }
        emitRun(t0, end, pos, len, w, h)
    }

    private fun emitRun(ta: Float, tb: Float, pos: Float, len: Float, w: Float, h: Float) {
        val o = runN * 6
        pt(ta % 1f, w, h); runs[o] = px; runs[o + 1] = py
        pt(tb % 1f, w, h); runs[o + 2] = px; runs[o + 3] = py
        runs[o + 4] = (ta - pos) / len
        runs[o + 5] = (tb - pos) / len
        runN++
    }

    private fun hue1(p: Float, q: Float, t0: Float): Float {
        var t = t0
        if (t < 0) t += 1f; if (t > 1) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }

    private fun hsl2rgb(h0: Float, s: Float, l: Float) {
        val h = (((h0 % 360f) + 360f) % 360f) / 360f
        if (s == 0f) { cr = (l * 255).roundToInt(); cg = cr; cb = cr; return }
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        cr = (hue1(p, q, h + 1f / 3f) * 255).roundToInt()
        cg = (hue1(p, q, h) * 255).roundToInt()
        cb = (hue1(p, q, h - 1f / 3f) * 255).roundToInt()
    }

    private fun spawn(k: Knobs) {
        val base = (if (k.reverse) -1f else 1f) / (k.periodS * 1000f)
        val col = if (k.palette.isEmpty()) 0xFFFFFFFF.toInt() else k.palette[Random.nextInt(k.palette.size)]
        val r = (col shr 16) and 0xFF; val g = (col shr 8) and 0xFF; val b = col and 0xFF
        // rgb→hsl once at spawn (hue-drift base)
        val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
        val mx = max(rf, max(gf, bf)); val mn = min(rf, min(gf, bf))
        var hh = 0f; var ss = 0f; val ll = (mx + mn) / 2f
        if (mx != mn) {
            val d = mx - mn
            ss = if (ll > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
            hh = when (mx) {
                rf -> (gf - bf) / d + (if (gf < bf) 6f else 0f)
                gf -> (bf - rf) / d + 2f
                else -> (rf - gf) / d + 4f
            } * 60f
        }
        ribbons.add(Ribbon().apply {
            pos = Random.nextFloat()
            len = k.minLen + Random.nextFloat() * (k.maxLen - k.minLen)
            this.col = col; this.r = r; this.g = g; this.b = b
            h = hh; this.s = ss; l = ll
            tf = 0.004f + Random.nextFloat() * 0.008f
            tp = Random.nextFloat() * 6.283f
            speed = base * (0.7f + Random.nextFloat() * 0.6f)
            age = 0f; life = 1500f + Random.nextFloat() * 2500f
            intn = 0.6f + Random.nextFloat() * 0.4f
        })
    }

    /** One sim step: reactive speed, spawn/reap, advance, and per-ribbon draw values. */
    private fun step(k: Knobs, nowMs: Double, dtMs: Float) {
        if (lastSpawnMs == 0.0) lastSpawnMs = nowMs
        if (speedNextMs == 0.0) speedNextMs = nowMs + k.speedChangeS * 1000.0
        pulse = 1f
        if (k.reactive) {
            // 音楽反応 v3 — tempo-locked: with a confident beat grid the speed pumps ON each
            // grid beat (sharp attack, exp decay through the beat); dynamics (auto-gain-
            // normalised loudness) set the baseline; low confidence falls back to onset surges.
            // No silence fallback here: unlike the reference's Visualizer capture, the
            // pipeline tap always hears the decoder (hand-off.md A.2).
            val lvl = MusicPulse.level()
            val bt = MusicPulse.beat()
            val ph = MusicPulse.beatPhase()
            val cf = MusicPulse.tempoConf()
            val kf = 1f - exp(-dtMs / 300f); val ks = 1f - exp(-dtMs / 8000f)
            lvMin += (if (lvl < lvMin) kf else ks) * (lvl - lvMin)
            lvMax += (if (lvl > lvMax) kf else ks) * (lvl - lvMax)
            val norm = ((lvl - lvMin) / max(0.05f, lvMax - lvMin)).coerceIn(0f, 1f)
            var tgt = k.speedMin + norm.pow(1.2f) * min(2f, k.reactGain) * (k.speedMax - k.speedMin)
            if (cf > 0.45f) {
                val pump = exp(-ph * k.reactSharp)      // 1.0 on the beat → ~0 by the next
                tgt *= (1f + k.reactKick * pump)
                pulse = 1f + k.reactPulse * max(0.8f * pump, bt)
            } else {
                if (nowMs > speedNextMs) {
                    wob = 0.9f + Random.nextFloat() * 0.2f
                    speedNextMs = nowMs + k.speedChangeS * 1000.0 * (0.5 + Random.nextFloat())
                }
                tgt *= wob * (1f + k.reactKick * bt)
                pulse = 1f + k.reactPulse * bt
            }
            speedTarget = min(k.speedMax * 1.8f, tgt)
            speedMul += (speedTarget - speedMul) * (1f - exp(-dtMs / REACT_TAU))
        } else {
            // Non-reactive: wander between speedMin and speedMax, retargeting every ~speedChangeS.
            if (nowMs > speedNextMs) {
                speedTarget = k.speedMin + Random.nextFloat() * (k.speedMax - k.speedMin)
                speedNextMs = nowMs + k.speedChangeS * 1000.0 * (0.5 + Random.nextFloat())
            }
            speedMul += (speedTarget - speedMul) * (1f - exp(-dtMs / SPEED_TAU))
        }
        while (nowMs - lastSpawnMs > k.spawnMs && ribbons.size < k.count * 3 / 2) { spawn(k); lastSpawnMs += k.spawnMs }
        // advance + reap in place, and precompute the frame's alpha/colour per ribbon
        var kk = 0
        for (i in ribbons.indices) {
            val p = ribbons[i]
            p.age += dtMs
            if (p.age >= p.life) continue
            p.pos = (((p.pos + p.speed * speedMul * dtMs) % 1f) + 1f) % 1f
            val lr = p.age / p.life
            var a = if (lr < 0.15f) lr / 0.15f else if (lr > 0.85f) (1f - lr) / 0.15f else 1f
            a *= p.intn
            if (k.twinkle > 0f) a *= 1f - k.twinkle * 0.5f * (1f + sin(nowMs.toFloat() * p.tf + p.tp))
            if (pulse > 1f) a = min(1f, a * pulse)   // 拍で光る
            var r = p.r; var g = p.g; var b = p.b
            if (k.hueDrift > 0f) { hsl2rgb(p.h + k.hueDrift * p.age / 1000f, p.s, p.l); r = cr; g = cg; b = cb }
            p.fa = a; p.fr = r; p.fg = g; p.fb = b
            ribbons[kk++] = p
        }
        while (ribbons.size > kk) ribbons.removeAt(ribbons.size - 1)
    }

    // ------------------------------------------------------------------ renderer

    @SuppressLint("DrawAllocation") // the LinearGradient/RadialGradient shaders are inherent to the technique
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val k = knobs
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val pad = k.pad
        val rad = k.radius
        val glowPx = k.glowPx

        val save = canvas.save()
        for (p in ribbons) {
            val a = p.fa; val r = p.fr; val g = p.fg; val b = p.fb
            if (a <= 0.02f) continue                    // invisible (just spawned/dying/twinkle-null)
            computeRuns(p.pos, p.len, w, h)
            val runN = runN; val runs = runs
            val ex = runs[(runN - 1) * 6 + 2]; val ey = runs[(runN - 1) * 6 + 3]   // head point
            // glow: per run, concentric widening capsules with a Gaussian-ish alpha falloff.
            // Each capsule end extends half a width past the endpoint, so consecutive runs
            // overlap around the crossed corner and the halo bends continuously.
            if (a > 0.02f) {
                val passes = 3 + k.glowLayers
                val reach = 2f * glowPx * k.glowSpread
                for (i in 0 until passes) {
                    val t = i.toFloat() / (passes - 1)
                    val e = pad + reach * t * 0.5f                       // half-width of this pass
                    glowPaint.color = argb(a * k.glowStrength * 0.4f * (1f - t).pow(1.6f), r, g, b)
                    for (ri in 0 until runN) {
                        val o = ri * 6
                        val l = min(runs[o], runs[o + 2]) - e; val rr = max(runs[o], runs[o + 2]) + e
                        val tt = min(runs[o + 1], runs[o + 3]) - e; val bb = max(runs[o + 1], runs[o + 3]) + e
                        canvas.drawRoundRect(l, tt, rr, bb, e, e, glowPaint)
                    }
                }
            }
            // core: per run, one capsule with an axis-aligned gradient — the tail→head comet
            // taper (0 → 0.85 at 55% → 1) sampled at the run's fraction endpoints.
            for (ri in 0 until runN) {
                val o = ri * 6
                val x1 = runs[o]; val y1 = runs[o + 1]; val x2 = runs[o + 2]; val y2 = runs[o + 3]
                corePaint.shader = LinearGradient(
                        x1, y1, x2, y2,
                        argb(a * coreTaper(runs[o + 4]), r, g, b),
                        argb(a * coreTaper(runs[o + 5]), r, g, b),
                        Shader.TileMode.CLAMP,
                )
                val l = min(x1, x2) - pad; val rr = max(x1, x2) + pad
                val tt = min(y1, y2) - pad; val bb = max(y1, y2) + pad
                canvas.drawRoundRect(l, tt, rr, bb, pad, pad, corePaint)
                corePaint.shader = null
            }
            // head star: bright near-white core melting into the ribbon colour; swells on the beat
            if (k.headGlow > 0f) {
                val hr = pad * 2.2f * k.headGlow * (if (pulse > 1f) 0.7f + 0.3f * pulse else 1f)
                headPaint.shader = RadialGradient(
                        ex, ey, hr,
                        intArrayOf(argb(a * 0.95f, 255, 255, 255), argb(a * 0.9f, r, g, b), argb(0f, r, g, b)),
                        floatArrayOf(0f, 0.35f, 1f),
                        Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(ex, ey, hr, headPaint)
                headPaint.shader = null
            }
        }
        // punch the inner hole: everything drawn past the band (glow spill, head-star bellies)
        // is erased in one native draw — the old even-odd clip's look without its mask raster
        canvas.drawRoundRect(pad, pad, w - pad, h - pad, max(0f, rad - pad), max(0f, rad - pad), clearPaint)
        // black corner masks on top — the screen's corners read as physically rounded.
        // Radius = the cornerMask knob (own var, live-tunable), independent of the band radius.
        val crad = k.corner
        if (crad > 0f) {
            if (cornerKey[0] != w || cornerKey[1] != h || cornerKey[2] != crad) {
                cornerKey[0] = w; cornerKey[1] = h; cornerKey[2] = crad
                cornerMask.rewind()
                cornerMask.addRect(0f, 0f, crad, crad, Path.Direction.CW)
                cornerMask.addRect(w - crad, 0f, w, crad, Path.Direction.CW)
                cornerMask.addRect(0f, h - crad, crad, h, Path.Direction.CW)
                cornerMask.addRect(w - crad, h - crad, w, h, Path.Direction.CW)
                cornerTmp.rewind()
                cornerTmp.addRoundRect(0f, 0f, w, h, crad, crad, Path.Direction.CW)
                cornerMask.op(cornerTmp, Path.Op.DIFFERENCE)
            }
            canvas.drawPath(cornerMask, cornerPaint)
        }
        canvas.restoreToCount(save)
    }

    companion object {
        private const val REACT_TAU = 150f       // reactive speed easing (ms)
        private const val SPEED_TAU = 700f       // non-reactive speed easing (ms)

        private fun argb(a: Float, r: Int, g: Int, b: Int): Int =
            ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (r shl 16) or (g shl 8) or b

        /** The core's tail→head brightness profile: 0 at the tail, 0.85 at 55% of the length, 1 at the head. */
        private fun coreTaper(f: Float): Float =
            if (f < 0.55f) 0.85f * (f / 0.55f) else 0.85f + 0.15f * ((f - 0.55f) / 0.45f)

        /** Comma-separated #rrggbb list → opaque colour ints; falls back to the tuned default palette. */
        fun parsePalette(s: String): IntArray {
            val cols = s.split(',').mapNotNull { part ->
                val t = part.trim().removePrefix("#")
                if (Regex("^[0-9a-fA-F]{6}$").matches(t)) (0xFF000000.toInt() or t.toInt(16)) else null
            }
            if (cols.isNotEmpty()) return cols.toIntArray()
            return parsePalette(app.simple.felicity.preferences.MeteorPreferences.DEFAULT_PALETTE)
        }
    }
}
