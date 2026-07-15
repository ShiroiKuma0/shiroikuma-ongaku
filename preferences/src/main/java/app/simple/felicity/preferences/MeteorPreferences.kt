package app.simple.felicity.preferences

import androidx.core.content.edit
import app.simple.felicity.manager.SharedPreferences.getSharedPreferences
import kotlin.math.roundToInt

/**
 * Preference store for the 音楽端灯 (edge meteors) music visualization (fork-only).
 *
 * All defaults are 白い熊's tuned values from the 自由作業盤 reference implementation
 * (hand-off.md A.4). Sizes are raw pixels (not dp) — the reference was tuned on the
 * target device in device pixels.
 */
object MeteorPreferences {

    const val ENABLED = "sk_meteor_enabled"
    const val OVERLAY = "sk_meteor_overlay"
    const val COUNT = "sk_meteor_count"
    const val SPAWN_MS = "sk_meteor_spawn_ms"
    const val PERIOD_S = "sk_meteor_period_s"
    const val REVERSE = "sk_meteor_reverse"
    const val MIN_LEN = "sk_meteor_min_len"
    const val MAX_LEN = "sk_meteor_max_len"
    const val PADDING = "sk_meteor_padding"
    const val RADIUS = "sk_meteor_radius"
    const val CORNER_MASK = "sk_meteor_corner_mask"
    const val GLOW = "sk_meteor_glow"
    const val GLOW_LAYERS = "sk_meteor_glow_layers"
    const val GLOW_SPREAD = "sk_meteor_glow_spread"
    const val GLOW_STRENGTH = "sk_meteor_glow_strength"
    const val HEAD_GLOW = "sk_meteor_head_glow"
    const val TWINKLE = "sk_meteor_twinkle"
    const val HUE_DRIFT = "sk_meteor_hue_drift"
    const val MAX_FPS = "sk_meteor_max_fps"
    const val REACTIVE = "sk_meteor_reactive"
    const val REACT_GAIN = "sk_meteor_react_gain"
    const val REACT_PULSE = "sk_meteor_react_pulse"
    const val REACT_KICK = "sk_meteor_react_kick"
    const val REACT_SHARP = "sk_meteor_react_sharp"
    const val SPEED_MIN = "sk_meteor_speed_min"
    const val SPEED_MAX = "sk_meteor_speed_max"
    const val SPEED_CHANGE_S = "sk_meteor_speed_change_s"
    const val PALETTE = "sk_meteor_palette"

    const val DEFAULT_PALETTE =
        "#80ff00, #ff0000, #00ff80, #ffeb3b, #c00000, #00e676, #1e88e5, #ff2a00, #00bcd4, #ffea00, #4caf50"

    /** Prefix shared by every meteor knob key — the view reloads on any key with this prefix. */
    const val KEY_PREFIX = "sk_meteor_"

    // ---------------------------------------------------------------------------------------------------------- //

    fun isEnabled(): Boolean = getSharedPreferences().getBoolean(ENABLED, true)
    fun setEnabled(value: Boolean) = getSharedPreferences().edit { putBoolean(ENABLED, value) }

    fun isOverlayEnabled(): Boolean = getSharedPreferences().getBoolean(OVERLAY, false)
    fun setOverlayEnabled(value: Boolean) = getSharedPreferences().edit { putBoolean(OVERLAY, value) }

    fun isReversed(): Boolean = getSharedPreferences().getBoolean(REVERSE, false)
    fun setReversed(value: Boolean) = getSharedPreferences().edit { putBoolean(REVERSE, value) }

    fun isReactive(): Boolean = getSharedPreferences().getBoolean(REACTIVE, true)
    fun setReactive(value: Boolean) = getSharedPreferences().edit { putBoolean(REACTIVE, value) }

    // ---------------------------------------------------------------------------------------------------------- //

    fun getCount(): Int = getSharedPreferences().getFloat(COUNT, 18F).roundToInt()
    fun setCount(value: Int) = getSharedPreferences().edit { putFloat(COUNT, value.toFloat()) }

    fun getSpawnMs(): Float = getSharedPreferences().getFloat(SPAWN_MS, 60F)
    fun setSpawnMs(value: Float) = getSharedPreferences().edit { putFloat(SPAWN_MS, value) }

    fun getPeriodS(): Float = getSharedPreferences().getFloat(PERIOD_S, 5F)
    fun setPeriodS(value: Float) = getSharedPreferences().edit { putFloat(PERIOD_S, value) }

    fun getMinLen(): Float = getSharedPreferences().getFloat(MIN_LEN, 0.05F)
    fun setMinLen(value: Float) = getSharedPreferences().edit { putFloat(MIN_LEN, value) }

    fun getMaxLen(): Float = getSharedPreferences().getFloat(MAX_LEN, 0.14F)
    fun setMaxLen(value: Float) = getSharedPreferences().edit { putFloat(MAX_LEN, value) }

    fun getPadding(): Float = getSharedPreferences().getFloat(PADDING, 5F)
    fun setPadding(value: Float) = getSharedPreferences().edit { putFloat(PADDING, value) }

    fun getRadius(): Float = getSharedPreferences().getFloat(RADIUS, 32F)
    fun setRadius(value: Float) = getSharedPreferences().edit { putFloat(RADIUS, value) }

    fun getCornerMask(): Float = getSharedPreferences().getFloat(CORNER_MASK, 18F)
    fun setCornerMask(value: Float) = getSharedPreferences().edit { putFloat(CORNER_MASK, value) }

    fun getGlow(): Float = getSharedPreferences().getFloat(GLOW, 12F)
    fun setGlow(value: Float) = getSharedPreferences().edit { putFloat(GLOW, value) }

    fun getGlowLayers(): Int = getSharedPreferences().getFloat(GLOW_LAYERS, 2F).roundToInt()
    fun setGlowLayers(value: Int) = getSharedPreferences().edit { putFloat(GLOW_LAYERS, value.toFloat()) }

    fun getGlowSpread(): Float = getSharedPreferences().getFloat(GLOW_SPREAD, 2F)
    fun setGlowSpread(value: Float) = getSharedPreferences().edit { putFloat(GLOW_SPREAD, value) }

    fun getGlowStrength(): Float = getSharedPreferences().getFloat(GLOW_STRENGTH, 1F)
    fun setGlowStrength(value: Float) = getSharedPreferences().edit { putFloat(GLOW_STRENGTH, value) }

    fun getHeadGlow(): Float = getSharedPreferences().getFloat(HEAD_GLOW, 1.5F)
    fun setHeadGlow(value: Float) = getSharedPreferences().edit { putFloat(HEAD_GLOW, value) }

    fun getTwinkle(): Float = getSharedPreferences().getFloat(TWINKLE, 0.35F)
    fun setTwinkle(value: Float) = getSharedPreferences().edit { putFloat(TWINKLE, value) }

    fun getHueDrift(): Float = getSharedPreferences().getFloat(HUE_DRIFT, 12F)
    fun setHueDrift(value: Float) = getSharedPreferences().edit { putFloat(HUE_DRIFT, value) }

    fun getMaxFps(): Float = getSharedPreferences().getFloat(MAX_FPS, 45F)
    fun setMaxFps(value: Float) = getSharedPreferences().edit { putFloat(MAX_FPS, value) }

    fun getReactGain(): Float = getSharedPreferences().getFloat(REACT_GAIN, 1F)
    fun setReactGain(value: Float) = getSharedPreferences().edit { putFloat(REACT_GAIN, value) }

    fun getReactPulse(): Float = getSharedPreferences().getFloat(REACT_PULSE, 0.6F)
    fun setReactPulse(value: Float) = getSharedPreferences().edit { putFloat(REACT_PULSE, value) }

    fun getReactKick(): Float = getSharedPreferences().getFloat(REACT_KICK, 1F)
    fun setReactKick(value: Float) = getSharedPreferences().edit { putFloat(REACT_KICK, value) }

    fun getReactSharp(): Float = getSharedPreferences().getFloat(REACT_SHARP, 5F)
    fun setReactSharp(value: Float) = getSharedPreferences().edit { putFloat(REACT_SHARP, value) }

    fun getSpeedMin(): Float = getSharedPreferences().getFloat(SPEED_MIN, 0F)
    fun setSpeedMin(value: Float) = getSharedPreferences().edit { putFloat(SPEED_MIN, value) }

    fun getSpeedMax(): Float = getSharedPreferences().getFloat(SPEED_MAX, 1.5F)
    fun setSpeedMax(value: Float) = getSharedPreferences().edit { putFloat(SPEED_MAX, value) }

    fun getSpeedChangeS(): Float = getSharedPreferences().getFloat(SPEED_CHANGE_S, 5F)
    fun setSpeedChangeS(value: Float) = getSharedPreferences().edit { putFloat(SPEED_CHANGE_S, value) }

    fun getPalette(): String = getSharedPreferences().getString(PALETTE, DEFAULT_PALETTE) ?: DEFAULT_PALETTE
    fun setPalette(value: String) = getSharedPreferences().edit { putString(PALETTE, value) }
}
