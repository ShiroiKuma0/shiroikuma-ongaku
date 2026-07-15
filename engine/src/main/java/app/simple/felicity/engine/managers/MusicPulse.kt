package app.simple.felicity.engine.managers

import android.os.SystemClock
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Live music pulse for the audio-reactive 音楽端灯 (fork-only): distills the ExoPlayer
 * pipeline's PCM into small signals the meteor view polls per frame.
 *
 * Fed by [app.simple.felicity.engine.processors.VisualizerProcessor] — the last processor
 * in the DSP chain, so this hears exactly the post-FX signal, including its output-latency
 * pre-delay (the analysis is aligned with what the listener actually hears). Unlike the
 * reference implementation (自由作業盤's `MusicPulseSource`), there is no
 * `android.media.audiofx.Visualizer` involved: no RECORD_AUDIO permission, no output-mix
 * dependency, and it keeps working during A2DP-offload / Android Auto playback because the
 * tap sees the decoder output directly.
 *
 * Signals (all safe to call from any thread; return zeros while nothing is playing):
 *  - [level]:     smoothed loudness 0..1 — fast attack, slow release
 *  - [beat]:      a decaying 0..1 impulse fired on bass onsets
 *  - [beatPhase]: position within the current beat on the tempo grid, 0 (on the beat) → 1
 *  - [tempoConf]: 0..1 agreement of the onset-interval cluster behind the tempo estimate
 *  - [bpm]:       the tempo estimate itself
 *
 * The tempo phase-lock (onset clustering + PLL anchor nudge) is ported verbatim from the
 * reference `MusicPulseSource.onOnset` per hand-off.md A.1.
 *
 * Ref-counted: consumers [acquire] while visible and [release] when hidden so the per-sample
 * analysis work is skipped entirely when no meteors are on screen.
 */
object MusicPulse {

    /** Analysis window length in seconds (~60ms — within the spec's 50–100ms band). */
    private const val WINDOW_SEC = 0.06f

    /** Bass low-pass cutoff in Hz (spec: ≲150Hz). */
    private const val BASS_CUTOFF_HZ = 150.0

    /**
     * Minimum bass RMS for onset detection — the noise floor. The reference used FFT
     * magnitude units (`bassAvg > 1.0`); this is the equivalent gate for linear PCM RMS,
     * low enough that any audible bass line clears it while decoder silence does not.
     */
    private const val NOISE_FLOOR = 0.005f

    @Volatile
    private var refs = 0

    // --- outputs (audio thread writes, UI thread reads) ---
    @Volatile private var levelV = 0f
    @Volatile private var beatAt = 0L        // elapsedRealtime of the last bass onset
    @Volatile private var beatPeak = 0f      // strength of that onset, 0..1

    // --- beat grid (tempo phase-lock; audio thread writes, getters read the volatiles) ---
    private val onsets = ArrayDeque<Long>()
    @Volatile private var periodMs = 0.0     // 0 = no tempo estimate yet
    @Volatile private var anchorMs = 0L      // a beat instant on the grid
    @Volatile private var tempoConfV = 0f    // 0..1 cluster agreement

    // --- per-window accumulators (audio thread only) ---
    private var sumSq = 0.0                  // full-band energy accumulator
    private var bassSumSq = 0.0              // low-band energy accumulator
    private var windowCount = 0              // samples accumulated so far
    private var windowSamples = 0            // samples per window at the current rate
    private var lastSampleRate = 0
    private var lpAlpha = 0f                 // first-order low-pass coefficient
    private var lp1 = 0f; private var lp2 = 0f; private var lp3 = 0f; private var lp4 = 0f

    // Bass running average for onset detection (audio thread only).
    private var bassAvg = 0.0
    private var bassInit = false

    // ---------------------------------------------------------------------------------------------------------- //

    @Synchronized
    fun acquire() {
        refs++
    }

    @Synchronized
    fun release() {
        refs = (refs - 1).coerceAtLeast(0)
        if (refs == 0) {
            reset()
        }
    }

    private fun reset() {
        levelV = 0f
        beatPeak = 0f
        beatAt = 0L
        sumSq = 0.0
        bassSumSq = 0.0
        windowCount = 0
        lp1 = 0f; lp2 = 0f; lp3 = 0f; lp4 = 0f
        bassAvg = 0.0
        bassInit = false
        synchronized(onsets) { onsets.clear() }
        periodMs = 0.0
        anchorMs = 0L
        tempoConfV = 0f
    }

    // ---------------------------------------------------------------------------------------------------------- //

    /**
     * Feeds one mono PCM window from the audio thread. Called by
     * [app.simple.felicity.engine.processors.VisualizerProcessor] with its FFT-sized
     * sample buffer; samples are accumulated here into ~[WINDOW_SEC] analysis windows.
     *
     * Does nothing (beyond a volatile read) while no consumer holds a reference.
     */
    fun feed(samples: FloatArray, count: Int, sampleRate: Int) {
        if (refs == 0) return
        if (sampleRate <= 0) return

        if (sampleRate != lastSampleRate) {
            lastSampleRate = sampleRate
            windowSamples = (sampleRate * WINDOW_SEC).toInt().coerceAtLeast(1)
            // First-order low-pass coefficient for the bass band; four cascaded sections
            // give a ~4th-order roll-off above the cutoff (spec A.1).
            lpAlpha = (1.0 - exp(-2.0 * Math.PI * BASS_CUTOFF_HZ / sampleRate)).toFloat()
        }

        val a = lpAlpha
        for (i in 0 until count) {
            val s = samples[i]
            sumSq += (s * s).toDouble()
            lp1 += a * (s - lp1)
            lp2 += a * (lp1 - lp2)
            lp3 += a * (lp2 - lp3)
            lp4 += a * (lp3 - lp4)
            bassSumSq += (lp4 * lp4).toDouble()
            windowCount++
            if (windowCount >= windowSamples) {
                processWindow()
            }
        }
    }

    /** Closes one analysis window: level smoothing, bass onset detection, tempo update. */
    private fun processWindow() {
        val n = windowCount
        windowCount = 0
        val rms = sqrt(sumSq / n)
        val bass = sqrt(bassSumSq / n)
        sumSq = 0.0
        bassSumSq = 0.0

        // Full-scale sine RMS is ~0.707; the ×2 mirrors the reference's /64-of-128
        // normalization so typical music reaches well into the 0..1 range.
        val raw = (rms * 2.0).coerceIn(0.0, 1.0).toFloat()
        val cur = levelV
        // Fast attack so hits register, slow release so the level breathes down.
        levelV = if (raw > cur) cur + (raw - cur) * 0.5f else cur + (raw - cur) * 0.12f

        if (!bassInit) {
            bassAvg = bass
            bassInit = true
        }
        // Onset = bass jumps well above its running average (and above the noise floor).
        if (bassAvg > NOISE_FLOOR && bass > bassAvg * 1.45) {
            val now = SystemClock.elapsedRealtime()
            // Refractory 180ms: one onset per drum hit, not per window of its decay.
            if (now - beatAt > 180) {
                beatPeak = (((bass / bassAvg) - 1.0) / 1.5).coerceIn(0.4, 1.0).toFloat()
                beatAt = now
                updateTempo(now)
            }
        }
        bassAvg += (bass - bassAvg) * 0.08
    }

    /**
     * Beat-grid update — ported verbatim from the reference `MusicPulseSource.updateTempo`:
     * onset-interval clustering estimates the beat period (folded into the 60–180 BPM band);
     * the anchor is a known beat time, nudged PLL-style toward on-beat onsets so [beatPhase]
     * stays aligned even when individual onsets are missed.
     */
    private fun updateTempo(now: Long) {
        // Guarded because [reset] (main thread, on release) clears the deque while the
        // audio thread appends here. Contention is a few onsets per second — negligible.
        val t = synchronized(onsets) {
            onsets.addLast(now)
            while (onsets.size > 32 || now - onsets.first() > 10_000) onsets.removeFirst()
            if (onsets.size < 6) { tempoConfV = 0f; return }
            onsets.toLongArray()
        }
        // Successive + skip-one inter-onset intervals, each folded into 333..1000ms (180..60 BPM) —
        // folding maps half/double-time hits onto the same beat period.
        val iois = ArrayList<Double>(t.size * 2)
        for (i in 1 until t.size) {
            for (j in 1..2) {
                if (i - j < 0) continue
                var d = (t[i] - t[i - j]).toDouble()
                if (d < 80) continue
                while (d < 333) d *= 2
                while (d > 1000) d /= 2
                iois.add(d)
            }
        }
        if (iois.isEmpty()) { tempoConfV = 0f; return }
        // Modal cluster: the candidate with the most intervals within ±8% wins; period = cluster mean.
        var bestN = 0
        var bestSum = 0.0
        for (c in iois) {
            var n = 0
            var s = 0.0
            for (x in iois) if (kotlin.math.abs(x - c) < c * 0.08) { n++; s += x }
            if (n > bestN) { bestN = n; bestSum = s }
        }
        val p = bestSum / bestN
        tempoConfV = (bestN.toFloat() / iois.size).coerceIn(0f, 1f)
        if (periodMs <= 0 || kotlin.math.abs(p - periodMs) > periodMs * 0.1) {
            periodMs = p                     // tempo changed — re-anchor the grid on this onset
            anchorMs = now
        } else {
            periodMs += (p - periodMs) * 0.2
            // Phase-lock: if this onset lands near the grid, pull the anchor toward it (30%).
            val ph = ((now - anchorMs).toDouble() % periodMs) / periodMs
            val err = if (ph > 0.5) ph - 1.0 else ph
            if (kotlin.math.abs(err) < 0.2) anchorMs += (err * periodMs * 0.3).toLong()
        }
    }

    // ---------------------------------------------------------------------------------------------------------- //

    /** Smoothed loudness 0..1 — fast attack (0.5), slow release (0.12) per analysis window. */
    fun level(): Float = levelV

    /** Last onset's strength decaying to 0 (τ=180ms, hard zero after 600ms). */
    fun beat(): Float {
        val dt = SystemClock.elapsedRealtime() - beatAt
        return if (dt > 600) 0f else beatPeak * exp(-dt / 180.0).toFloat()
    }

    /** Tempo estimate in beats per minute; 0 while no estimate exists. */
    fun bpm(): Float = if (periodMs > 0) (60_000.0 / periodMs).toFloat() else 0f

    /** Position within the current beat, 0 (on the beat) → 1 (next beat). 0 while no tempo. */
    fun beatPhase(): Float {
        val p = periodMs
        if (p <= 0) return 0f
        var ph = ((SystemClock.elapsedRealtime() - anchorMs).toDouble() % p) / p
        if (ph < 0) ph += 1.0
        return ph.toFloat()
    }

    /** Tempo confidence 0..1, fading out when onsets stop coming (track pause/quiet outro). */
    fun tempoConf(): Float {
        val silent = SystemClock.elapsedRealtime() - beatAt
        if (silent <= 2_000) return tempoConfV
        return tempoConfV * exp(-(silent - 2_000) / 2_000.0).toFloat()
    }
}
