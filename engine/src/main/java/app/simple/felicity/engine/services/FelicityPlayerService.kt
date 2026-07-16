package app.simple.felicity.engine.services

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.simple.felicity.engine.R
import app.simple.felicity.engine.artwork.FelicityArtworkBitmapLoader
import app.simple.felicity.engine.audio.FelicityAudioSink
import app.simple.felicity.engine.broadcasts.AutomationBroadcasts
import app.simple.felicity.engine.managers.AudioPipelineManager
import app.simple.felicity.engine.managers.AudioProcessorManager
import app.simple.felicity.engine.managers.EqualizerManager
import app.simple.felicity.engine.managers.MediaPlaybackManager
import app.simple.felicity.engine.managers.PlaybackStateManager
import app.simple.felicity.engine.managers.VisualizerManager
import app.simple.felicity.engine.model.AudioPipelineSnapshot
import app.simple.felicity.engine.notifications.PlaybackErrorNotifier
import app.simple.felicity.engine.usb.UsbDacDriver
import app.simple.felicity.engine.usb.UsbDacManager
import app.simple.felicity.engine.views.EdgeMeteorsView
import app.simple.felicity.manager.SharedPreferences.initRegisterSharedPreferenceChangeListener
import app.simple.felicity.manager.SharedPreferences.unregisterSharedPreferenceChangeListener
import app.simple.felicity.preferences.AppearancePreferences
import app.simple.felicity.preferences.AudioPreferences
import app.simple.felicity.preferences.EqualizerPreferences
import app.simple.felicity.preferences.MeteorPreferences
import app.simple.felicity.preferences.PlayerPreferences
import app.simple.felicity.preferences.ShufflePreferences
import app.simple.felicity.preferences.UserInterfacePreferences
import app.simple.felicity.repository.constants.MediaConstants
import app.simple.felicity.repository.database.instances.AudioDatabase
import app.simple.felicity.repository.models.Audio
import app.simple.felicity.repository.repositories.AudioRepository
import app.simple.felicity.repository.repositories.PlaylistRepository
import app.simple.felicity.repository.repositories.SongStatRepository
import app.simple.felicity.repository.utils.AudioUtils.getProperAlbum
import app.simple.felicity.repository.utils.AudioUtils.getProperArtists
import app.simple.felicity.repository.utils.AudioUtils.getProperTitle
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * Service responsible for managing audio playback using ExoPlayer with dynamic decoder switching support.
 */
@AndroidEntryPoint
@OptIn(UnstableApi::class)
class FelicityPlayerService : MediaLibraryService(), SharedPreferences.OnSharedPreferenceChangeListener {

    @Inject
    lateinit var audioRepository: AudioRepository

    @Inject
    lateinit var songStatRepository: SongStatRepository

    /**
     * Fork (Android Auto): playlists are one of the top-level folders in the car
     * browse tree, so the service needs direct access to the playlist tables.
     */
    @Inject
    lateinit var playlistRepository: PlaylistRepository

    private var mediaSession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private var renderersFactory: DefaultRenderersFactory? = null

    /**
     * The mediaId of the media item that was playing before the most recent item transition.
     * Used in conjunction with [previousItemEndPositionMs] and [previousItemDurationMs] to
     * decide whether the previous song was skipped.
     */
    private var previousItemMediaId: String? = null

    /**
     * The playback position (ms) captured just before the most recent item transition.
     * Populated in {@link Player.Listener#onPositionDiscontinuity}.
     */
    private var previousItemEndPositionMs: Long = 0L

    /**
     * The total duration (ms) of the previous media item captured just before the transition.
     * Populated in {@link Player.Listener#onPositionDiscontinuity}.
     */
    private var previousItemDurationMs: Long = 0L

    /**
     * Whether the player was actively playing audio just before the most recent item transition.
     * This acts as a gatekeeper so stats are only recorded for songs the user actually heard,
     * not ones they skipped past while the player was paused or idle.
     */
    private var wasPlayingBeforeTransition: Boolean = false

    /**
     * Holds the media ID of a song that needs its play recorded once the player actually
     * starts playing. This happens when a new queue is set — ExoPlayer fires the item
     * transition callback before [Player.play] is called, so [Player.playWhenReady] is
     * still false at that moment and the play would be silently missed. We park the ID here
     * and flush it as soon as playback begins.
     */
    private var pendingPlayRecordMediaId: String? = null

    /**
     * The duration of the song that is CURRENTLY loaded and ready to play, kept fresh by
     * reading [player.duration] once the player reaches STATE_READY (the only state where
     * the duration is guaranteed to be accurate).
     *
     * Why do we need this? When the user skips a track, ExoPlayer fires
     * onPositionDiscontinuity AFTER it has already transitioned its internal state to the
     * new item. At that point, player.duration already reflects the INCOMING song (or
     * C.TIME_UNSET if it is still buffering) — not the outgoing one we actually want.
     * By caching the duration here we always have the correct value ready when we need it.
     */
    private var currentItemDurationMs: Long = 0L

    /**
     * Manages the balance and downmix [androidx.media3.common.audio.ChannelMixingAudioProcessor]
     * instances. Extracted to keep audio processing logic out of the service.
     */
    private val audioProcessorManager = AudioProcessorManager()

    /**
     * Posts silent error notifications when a track cannot be played.
     * Initialized in [onCreate] once a valid [Context] is available.
     */
    private lateinit var playbackErrorNotifier: PlaybackErrorNotifier

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var periodicStateSaveJob: Job? = null

    /**
     * An in-memory copy of the full audio library, kept fresh by collecting the Room
     * Flow in [onCreate]. Using a Flow means we never need to hit the database on every
     * [onGetChildren] call — the list is always ready and up to date.
     *
     * Populated asynchronously on first launch; empty list is the safe default while
     * the first DB emission is still on its way.
     */
    private var cachedSongList: List<Audio> = emptyList()

    /**
     * Tracks whether we are currently in a silent FFmpeg fallback retry for a failed track.
     * When true the next decoding error on the same item is treated as a final failure,
     * the original decoder is restored and the track is skipped.
     */
    private var ffmpegFallbackActive = false

    /** The [MediaItem] that triggered the decoding error we are retrying via FFmpeg. */
    private var ffmpegFallbackItem: MediaItem? = null

    /** The decoder the user had configured before a fallback attempt was started. */
    private var preFallbackDecoder: Int = AudioPreferences.LOCAL_DECODER

    /**
     * The name of the most recently initialized audio decoder, captured via [analyticsListener].
     * Defaults to "Unknown" until [AnalyticsListener.onAudioDecoderInitialized] fires.
     */
    private var currentDecoderName: String = "Unknown"

    /**
     * The compressed source [Format] most recently delivered to the audio renderer.
     * Updated via [AnalyticsListener.onAudioInputFormatChanged]; `null` before the
     * first track is decoded.
     */
    private var currentAudioInputFormat: Format? = null

    /**
     * The currently active audio output device, or `null` if detection has not yet
     * run. Updated whenever [audioDeviceCallback] fires or [detectActiveOutputDevice]
     * is called explicitly.
     */
    private var currentOutputDevice: AudioDeviceInfo? = null

    /** Coroutine job that pushes a fresh [AudioPipelineSnapshot] every 3 seconds while playing. */
    private var snapshotPulseJob: Job? = null

    /**
     * Debounce job that coalesces rapid-fire [buildAndPushSnapshot] calls into a single
     * trailing push. During a song transition, up to 6 callbacks can fire within ~200ms
     * (decoder init, format change, item transition, state ready, playing changed, pulse).
     * Rather than pushing 6 duplicate snapshots, we cancel any pending push and schedule
     * one new push after a short cooldown — only the last call in the burst wins.
     */
    private var snapshotDebounceJob: Job? = null

    /**
     * Fork (音楽端灯): the system-wide edge-meteors overlay — a [EdgeMeteorsView] hosted in
     * a TYPE_APPLICATION_OVERLAY full-screen non-touchable window while music plays, so
     * the edge lighting shows over OTHER apps. Non-null only while the window is attached.
     */
    private var meteorOverlayView: EdgeMeteorsView? = null

    /** Fork (音楽端灯): screen interactive state — no overlay frames burn while it is off. */
    private var isScreenOn = true

    /**
     * Fork (音楽端灯): removes the overlay when the display turns off and restores it
     * (playback state permitting) when it turns back on.
     */
    private val meteorScreenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOn = false
                    updateMeteorOverlay()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    updateMeteorOverlay()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        initRegisterSharedPreferenceChangeListener(applicationContext)
        playbackErrorNotifier = PlaybackErrorNotifier(applicationContext)

        // Register the USB DAC driver so it starts listening for permission results.
        // This must happen before any USB device could be plugged in during the service lifetime.
        UsbDacDriver.getInstance(applicationContext).attach()

        // Listen for USB DAC attach/detach so the audio pipeline snapshot stays current
        // and we can log routing changes for diagnostics.
        UsbDacManager.addListener(usbDacManagerListener)

        // Expose the processor via VisualizerManager so the player fragment can call
        // setDirectOutput() and wire the lock-free twin-buffer path without a service bind.
        VisualizerManager.processor = audioProcessorManager.visualizerProcessor

        // Wire the native DSP processor into EqualizerManager so gain, preamp, and enable
        // changes driven by the UI are forwarded to the live audio pipeline immediately.
        EqualizerManager.attachProcessor(audioProcessorManager.nativeDspProcessor)

        // Initialize the RenderersFactory once.
        renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableOffload: Boolean): AudioSink {
                val hiresEnabled = AudioPreferences.isHiresOutputEnabled()

                // Check if the user WANTS to preserve surround sound for their USB DAC
                // You'd add this boolean to your AudioPreferences
                val forceStereoDownmix = AudioPreferences.isStereoDownmixForced()

                audioProcessorManager.applyBalance(EqualizerPreferences.getBalance())
                audioProcessorManager.applyStereoWidth(EqualizerPreferences.getStereoWidth())
                audioProcessorManager.applyTapeSaturationDrive(EqualizerPreferences.getTapeSaturationDrive())
                audioProcessorManager.applyKaraokeMode(EqualizerPreferences.isKaraokeModeEnabled())
                audioProcessorManager.applyNightMode(EqualizerPreferences.isNightModeEnabled())
                // applyEqualizerState covers 10-band EQ, bass, treble, preamp, and enabled flag.
                audioProcessorManager.applyEqualizerState()
                // Reverb is applied after all tone-shaping so it adds only spatial depth.
                audioProcessorManager.applyReverb(
                        EqualizerPreferences.getReverbMix(),
                        EqualizerPreferences.getReverbDecay(),
                        EqualizerPreferences.getReverbDamp(),
                        EqualizerPreferences.getReverbSize()
                )

                // Build the processor array dynamically
                val processors = mutableListOf<AudioProcessor>()

                if (AudioPreferences.isSkipSilenceEnabled()) {
                    // Trim digital silence first while the stream is uncolored.
                    processors.add(audioProcessorManager.silenceTrimmingProcessor)
                }

                if (forceStereoDownmix) {
                    processors.add(audioProcessorManager.downmixProcessor)
                }

                // Vocal removal runs before the EQ/effects chain so center-channel
                // subtraction is not colored by subsequent tonal processing.
                processors.add(audioProcessorManager.karaokeProcessor)

                // Unified native DSP: EQ → bass/treble shelves → M/S widening → balance → saturation.
                // Also feeds the processed mono downmix to the shared FFTContext.
                processors.add(audioProcessorManager.nativeDspProcessor)

                // Dynamic range compression runs after all tonal/spatial effects so it
                // can respond to the final loudness of the mix.
                processors.add(audioProcessorManager.nightModeProcessor)

                // Visualizer always goes last so the spectrum display reflects every
                // active effect in the chain.
                processors.add(audioProcessorManager.visualizerProcessor)

                val audioSink = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(hiresEnabled)
                    // CRITICAL FOR USB DACs: Tell ExoPlayer to read the USB/HDMI capabilities
                    .setAudioCapabilities(AudioCapabilities.getCapabilities(context))
                    .setAudioProcessors(processors.toTypedArray())
                    .build()

                // If the user has a home theater / USB DAC, we MIGHT want offload for Atmos/Dolby
                val offloadMode = if (!forceStereoDownmix) {
                    if (AudioPreferences.isGaplessPlaybackEnabled()) {
                        DefaultAudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_REQUIRED
                    } else {
                        DefaultAudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_NOT_REQUIRED
                    }
                } else {
                    DefaultAudioSink.OFFLOAD_MODE_DISABLED
                }

                audioSink.setOffloadMode(offloadMode)

                /**
                 * Wrap the [DefaultAudioSink] with [FelicityAudioSink] as an exclusive router.
                 * The wrapper selects exactly one output path — USB DAC, AAudio, Oboe, or the
                 * standard AudioTrack — and routes all audio there. The [DefaultAudioSink] is
                 * only used when the AudioTrack path is active; for native paths it is kept
                 * idle (via the factory) so no AudioTrack is ever created unnecessarily.
                 */
                return FelicityAudioSink(
                        defaultSinkProvider = { audioSink },
                        context = context,
                        nativeDsp = audioProcessorManager.nativeDspProcessor,
                        visualizer = audioProcessorManager.visualizerProcessor)
            }

            override fun buildAudioRenderers(
                    context: Context,
                    extensionRendererMode: Int,
                    mediaCodecSelector: MediaCodecSelector,
                    enableDecoderFallback: Boolean,
                    audioSink: AudioSink,
                    eventHandler: Handler,
                    eventListener: AudioRendererEventListener,
                    out: ArrayList<Renderer>
            ) {
                super.buildAudioRenderers(
                        context,
                        extensionRendererMode,
                        mediaCodecSelector,
                        enableDecoderFallback,
                        audioSink,
                        eventHandler,
                        eventListener,
                        out
                )
            }
        }

        // Build the initial player instance
        buildPlayer()

        // Initialize MediaSession
        val sessionActivityIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivityIntent!!)
            .setId("ExoPlayerServiceSession")
            // Fork (Android Auto): resolve felicity-art:// artwork URIs on browse items
            // (and keep default behavior for everything else, e.g. embedded tag art).
            .setBitmapLoader(CacheBitmapLoader(FelicityArtworkBitmapLoader(applicationContext, audioRepository, serviceScope)))
            .build()

        // Set initial repeat button in the notification
        mediaSession?.setCustomLayout(listOf(buildRepeatCommandButton(PlayerPreferences.getRepeatMode())))

        // Detect the current output device and subscribe to future device changes so the
        // snapshot is refreshed whenever headphones or a BT device is connected / disconnected.
        currentOutputDevice = detectActiveOutputDevice()
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))

        // Respond to on-demand snapshot requests emitted by the UI (e.g., AudioPipelineDialog
        // opening). The collect runs on the main dispatcher so player APIs are safe to call.
        serviceScope.launch(Dispatchers.Main.immediate) {
            AudioPipelineManager.refreshRequestFlow.collect {
                buildAndPushSnapshot()
            }
        }

        // Keep an up-to-date song list in memory. Room emits a fresh list every time the
        // library changes (scan, delete, etc.) so onGetChildren always returns current data
        // without a blocking database call.
        serviceScope.launch {
            audioRepository.getAllAudio().collect { list ->
                cachedSongList = list
            }
        }

        // Fork (音楽端灯): track screen on/off so the system-wide meteor overlay never
        // burns frames with the display off (hand-off.md A.5).
        registerReceiver(meteorScreenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
    }

    // ------------------------------------------------------------------ 音楽端灯 overlay

    /**
     * Fork (音楽端灯): reconciles the system-wide overlay window with the current state —
     * attached while (master enable AND overlay mode AND playing AND screen on AND the
     * SYSTEM_ALERT_WINDOW permission is granted), detached otherwise. If the permission is
     * missing the overlay is skipped silently; the settings switch handler is responsible
     * for steering 白い熊 to ACTION_MANAGE_OVERLAY_PERMISSION.
     */
    private fun updateMeteorOverlay() {
        val wanted = MeteorPreferences.isEnabled() &&
                MeteorPreferences.isOverlayEnabled() &&
                ::player.isInitialized && player.isPlaying &&
                isScreenOn &&
                Settings.canDrawOverlays(this)
        if (wanted) addMeteorOverlay() else removeMeteorOverlay()
    }

    private fun meteorOverlayParams(): WindowManager.LayoutParams {
        val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT)
        // Cover the full panel including any cutout region — on the Mate XT folded cover
        // panel a TYPE_APPLICATION_OVERLAY covers the real screen where full-screen
        // Activities get clipped (hand-off.md A.5).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // Pin the window to the REAL panel size at (0,0). With MATCH_PARENT the window is
        // sized to the "available" area, which excludes the status-bar strip while an app
        // is foreground — the band then starts below the status bar. Explicit real-size
        // bounds + fitInsetsTypes=0 keep the band on the true screen edge regardless of
        // what is in front; recomputed on every (fold/unfold) configuration change.
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            params.width = bounds.width()
            params.height = bounds.height()
            params.fitInsetsTypes = 0
        } else {
            val size = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(size)
            params.width = size.x
            params.height = size.y
        }
        params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
        params.x = 0
        params.y = 0

        return params
    }

    private fun addMeteorOverlay() {
        if (meteorOverlayView != null) return
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = EdgeMeteorsView(this)
        runCatching {
            windowManager.addView(view, meteorOverlayParams())
        }.onSuccess {
            meteorOverlayView = view
            Log.i(TAG, "音楽端灯: system-wide overlay attached")
        }.onFailure {
            Log.w(TAG, "音楽端灯: overlay attach failed: ${it.message}")
        }
    }

    private fun removeMeteorOverlay() {
        val view = meteorOverlayView ?: return
        meteorOverlayView = null
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        }.onFailure {
            Log.w(TAG, "音楽端灯: overlay detach failed: ${it.message}")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Fork (音楽端灯): fold/unfold (Mate XT) changes the panel geometry — force the
        // overlay window through a fresh measure/layout pass so the perimeter band matches
        // the new panel (hand-off.md A.5). MATCH_PARENT re-resolves on updateViewLayout.
        meteorOverlayView?.let { view ->
            runCatching {
                (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(view, meteorOverlayParams())
            }
        }
    }

    /**
     * configures the RenderersFactory based on user preferences and builds a new ExoPlayer instance.
     * If a player already exists, it is released before creating the new one.
     */
    private fun buildPlayer() {
        // Configure extension mode based on preferences
        val extensionMode = if (AudioPreferences.getAudioDecoder() == AudioPreferences.FFMPEG) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        } else {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
        }

        renderersFactory?.setExtensionRendererMode(extensionMode)

        // Configure LoadControl with optimized buffer settings based on hi-res mode
        val hiresEnabled = AudioPreferences.isHiresOutputEnabled()

        val loadControl = if (hiresEnabled) {
            // Hi-Res mode: 32-bit float processing requires larger buffers
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        /* minBufferMs = */ 5000,   // 5s minimum for smooth float processing
                        /* maxBufferMs = */ 15000,  // 15s maximum for hi-res content
                        /* bufferForPlaybackMs = */ 2000,   // 2s to start playback
                        /* bufferForPlaybackAfterRebufferMs = */ 3000  // 3s rebuffer threshold
                )
                .setPrioritizeTimeOverSizeThresholds(false) // Prioritize size for hi-res
                .build()
        } else {
            // Standard mode: 16-bit PCM processing uses smaller, efficient buffers
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        /* minBufferMs = */ 2500,   // 2.5s minimum for standard playback
                        /* maxBufferMs = */ 10000,  // 10s maximum for efficiency
                        /* bufferForPlaybackMs = */ 1000,   // 1s quick start
                        /* bufferForPlaybackAfterRebufferMs = */ 2000  // 2s rebuffer threshold
                )
                .setPrioritizeTimeOverSizeThresholds(true) // Prioritize time for responsiveness
                .build()
        }

        Log.i(TAG, "LoadControl configured for ${if (hiresEnabled) "Hi-Res" else "Standard"} mode")

        // Build new player instance
        player = ExoPlayer.Builder(this, renderersFactory!!)
            .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_NEVER)
                        .build(),
                    true
            )
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        // Set initial silence state based on preferences
        setSilenceState()

        // Configure gapless playback
        configureGaplessPlayback()

        // Apply saved repeat mode
        applyRepeatMode(PlayerPreferences.getRepeatMode())

        // Restore the saved playback speed and pitch so the user's settings are honored
        // from the very first song — no need to touch a knob to get it going.
        applyPlaybackParameters(
                EqualizerPreferences.getPlaybackSpeed(),
                EqualizerPreferences.getPitch()
        )

        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)
    }

    /**
     * Handles the dynamic switching of the audio decoder.
     * Captures current playback state (full queue + position), rebuilds the player with new
     * decoder settings, restores the entire queue and resumes from the same track/position.
     */
    private fun switchDecoder() {
        val mediaItems = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        val currentIndex = player.currentMediaItemIndex
        val currentPos = player.currentPosition
        val playWhenReady = player.playWhenReady

        // Release the old player to free up codecs/resources
        player.removeListener(playerListener)
        player.release()

        // Build the new player with updated Factory settings
        buildPlayer()

        // Restore the full queue and position
        if (mediaItems.isNotEmpty()) {
            player.setMediaItems(mediaItems, currentIndex, currentPos)
            player.playWhenReady = playWhenReady
            player.prepare()
        }

        // Update the session to point to the new player instance
        mediaSession?.player = player
    }

    /**
     * Handles the dynamic switching between hi-res and standard audio modes.
     * Captures current playback state, rebuilds the player with new audio output settings,
     * restores the state seamlessly for real-time mode switching.
     */
    private fun switchAudioMode() {
        val mediaItems = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        val currentIndex = player.currentMediaItemIndex
        val currentPos = player.currentPosition
        val playWhenReady = player.playWhenReady
        val hiresEnabled = AudioPreferences.isHiresOutputEnabled()

        Log.i(TAG, "Switching audio mode to: ${if (hiresEnabled) "Hi-Res (32-bit Float)" else "Standard (16-bit PCM)"}")

        // Release the old player to free up audio resources
        player.removeListener(playerListener)
        player.release()

        // Build the new player with updated audio sink and buffer settings
        buildPlayer()

        // Restore the full queue and position seamlessly
        if (mediaItems.isNotEmpty()) {
            player.setMediaItems(mediaItems, currentIndex, currentPos)
            player.playWhenReady = playWhenReady
            player.prepare()
        }

        // Update the session to point to the new player instance
        mediaSession?.player = player

        Log.i(TAG, "Audio mode switch completed successfully")
    }

    /**
     * Configures gapless playback based on user preferences.
     * When enabled, the player will seamlessly transition between tracks without silence.
     */
    private fun configureGaplessPlayback() {
        val gaplessEnabled = AudioPreferences.isGaplessPlaybackEnabled()
        player.pauseAtEndOfMediaItems = !gaplessEnabled
    }

    private fun applyRepeatMode(repeatMode: Int) {
        when (repeatMode) {
            MediaConstants.REPEAT_ONE -> {
                player.repeatMode = Player.REPEAT_MODE_ONE
            }
            MediaConstants.REPEAT_QUEUE -> {
                player.repeatMode = Player.REPEAT_MODE_ALL
            }
            else -> { // REPEAT_OFF
                player.repeatMode = Player.REPEAT_MODE_OFF
            }
        }
        MediaPlaybackManager.notifyRepeatMode(repeatMode)
        // Push the updated repeat button to the media notification alongside the favorite button
        val isFavorite = MediaPlaybackManager.getCurrentSong()?.isFavorite ?: false
        mediaSession?.setCustomLayout(listOf(buildRepeatCommandButton(repeatMode), buildFavoriteCommandButton(isFavorite)))
        Log.d(TAG, "Repeat mode applied: $repeatMode")
    }

    /** Builds a CommandButton representing the current repeat state for the notification. */
    @Suppress("DEPRECATION")
    private fun buildRepeatCommandButton(repeatMode: Int): CommandButton {
        val (iconRes, displayName) = when (repeatMode) {
            MediaConstants.REPEAT_ONE -> Pair(R.drawable.ic_repeat_one, "Repeat One")
            MediaConstants.REPEAT_QUEUE -> Pair(R.drawable.ic_repeat, "Repeat Queue")
            else -> Pair(R.drawable.ic_repeat_off, "Repeat Off")
        }

        return CommandButton.Builder(
                CommandButton.ICON_REPEAT_OFF)
            .setDisplayName(displayName)
            .setIconResId(iconRes)
            .setSessionCommand(SessionCommand(COMMAND_TOGGLE_REPEAT, Bundle.EMPTY))
            .build()
    }

    /**
     * Builds a CommandButton for the notification that lets the user toggle the current song
     * as a favorite. The icon respects the user's preference for a heart vs. thumbs-up style.
     *
     * @param isFavorite Whether the current song is already marked as a favorite.
     */
    @Suppress("DEPRECATION")
    private fun buildFavoriteCommandButton(isFavorite: Boolean): CommandButton {
        val useLikeIcon = UserInterfacePreferences.isLikeIconInsteadOfThumb()
        val (iconRes, displayName) = if (isFavorite) {
            if (useLikeIcon) Pair(R.drawable.ic_thumb_up, "Remove from Favorites")
            else Pair(R.drawable.ic_favorite_filled, "Remove from Favorites")
        } else {
            if (useLikeIcon) Pair(R.drawable.ic_thumb_up_off, "Add to Favorites")
            else Pair(R.drawable.ic_favorite_border, "Add to Favorites")
        }

        return CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName(displayName)
            .setIconResId(iconRes)
            .setSessionCommand(SessionCommand(COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY))
            .build()
    }

    private fun setSilenceState() {
        // Skip silence is always disabled for natural audio playback
        player.skipSilenceEnabled = AudioPreferences.isSkipSilenceEnabled()
    }

    /**
     * Silently retries [failedItem] using the FFmpeg extension decoder.
     *
     * The full queue and playback position are preserved; only the renderer mode is changed.
     * The preference store is NOT modified so the user's chosen decoder is kept intact.
     * If [failedItem] is null the call is a no-op (track already gone).
     */
    private fun retryWithFfmpegFallback(failedItem: MediaItem?) {
        if (failedItem == null) {
            Log.w(TAG, "retryWithFfmpegFallback: no failed item, aborting.")
            ffmpegFallbackActive = false
            return
        }

        val mediaItems = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        val currentIndex = player.currentMediaItemIndex

        // Grab the play/pause state BEFORE we tear down the player so we can put
        // things back exactly the way the user left them after the engine is rebuilt.
        // Without this, a paused player would spring back to life after the fallback
        // swap — not what anyone wants at 2 AM with the volume cranked up.
        val wasPlayWhenReady = player.playWhenReady

        // Temporarily force the FFmpeg extension without touching user preferences.
        renderersFactory?.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        player.removeListener(playerListener)
        player.release()

        buildPlayerWithExtensionMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        if (mediaItems.isNotEmpty()) {
            // Always retry from the very beginning of the track (position 0) since we
            // can't guarantee the partial buffer from the failed decoder is usable.
            // However, we do respect the user's pause state — paused stays paused.
            player.setMediaItems(mediaItems, currentIndex, 0L)
            player.playWhenReady = wasPlayWhenReady
            player.prepare()
        }

        mediaSession?.player = player
        Log.i(TAG, "FFmpeg fallback: re-trying '${failedItem.mediaId}' from the start with FFmpeg.")
    }

    /**
     * Restores the engine to [decoderMode] without writing to shared preferences.
     * Called after a failed FFmpeg fallback so the user sees no change in settings.
     */
    private fun restoreDecoderMode(decoderMode: Int) {
        val extensionMode = if (decoderMode == AudioPreferences.FFMPEG) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        } else {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
        }

        val mediaItems = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        val currentIndex = player.currentMediaItemIndex
        val currentPos = player.currentPosition
        val playWhenReady = player.playWhenReady

        player.removeListener(playerListener)
        player.release()

        buildPlayerWithExtensionMode(extensionMode)

        if (mediaItems.isNotEmpty()) {
            player.setMediaItems(mediaItems, currentIndex, currentPos)
            player.playWhenReady = playWhenReady
            player.prepare()
        }

        mediaSession?.player = player
        Log.d(TAG, "Decoder restored to mode $decoderMode (extensionMode=$extensionMode) without preference change.")
    }

    /**
     * Skips to the next track if available; otherwise restarts the current item.
     * Shared helper used by the fallback logic.
     */
    private fun skipOrRestartTrack() {
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            Log.i(TAG, "Skipped to next track after decoder failure.")
        } else {
            player.seekToDefaultPosition()
            Log.i(TAG, "Restarted current track (no next track available).")
        }
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * Variant of [buildPlayer] that uses a specific [extensionMode] directly, bypassing the
     * shared-preference read. Used for transient fallback / restore operations.
     */
    private fun buildPlayerWithExtensionMode(extensionMode: Int) {
        renderersFactory?.setExtensionRendererMode(extensionMode)

        val hiresEnabled = AudioPreferences.isHiresOutputEnabled()
        val loadControl = if (hiresEnabled) {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(5000, 15000, 2000, 3000)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(2500, 10000, 1000, 2000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }

        player = ExoPlayer.Builder(this, renderersFactory!!)
            .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .setSpatializationBehavior(C.SPATIALIZATION_BEHAVIOR_NEVER)
                        .build(),
                    true
            )
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        setSilenceState()
        configureGaplessPlayback()
        applyRepeatMode(PlayerPreferences.getRepeatMode())
        applyPlaybackParameters(
                EqualizerPreferences.getPlaybackSpeed(),
                EqualizerPreferences.getPitch()
        )
        player.addListener(playerListener)
        player.addAnalyticsListener(analyticsListener)
    }

    /**
     * Delegates balance panning to [audioProcessorManager].
     *
     * @param pan Stereo pan value in the range [-1.0, 1.0].
     */
    private fun applyBalanceToProcessor(pan: Float) {
        audioProcessorManager.applyBalance(pan)
    }

    /**
     * Delegates stereo width to [audioProcessorManager].
     *
     * @param width Stereo width in the range [0.0, 2.0]. 1.0 = natural stereo (no change).
     */
    private fun applyStereoWidthToProcessor(width: Float) {
        audioProcessorManager.applyStereoWidth(width)
    }

    /**
     * Delegates tape saturation drive to [audioProcessorManager].
     *
     * @param drive Saturation drive in [0.0, 4.0]. 0.0 = off (clean bypass).
     */
    private fun applyTapeSaturationDriveToProcessor(drive: Float) {
        audioProcessorManager.applyTapeSaturationDrive(drive)
    }

    /**
     * Delegates karaoke mode toggle to [audioProcessorManager].
     *
     * @param enabled True to activate center-channel removal, false to bypass.
     */
    private fun applyKaraokeModeToProcessor(enabled: Boolean) {
        audioProcessorManager.applyKaraokeMode(enabled)
    }

    /**
     * Delegates night mode toggle to [audioProcessorManager].
     *
     * @param enabled True to activate the dynamic compressor, false to bypass.
     */
    private fun applyNightModeToProcessor(enabled: Boolean) {
        audioProcessorManager.applyNightMode(enabled)
    }

    /**
     * Parses an embedded ReplayGain gain string (e.g. "+5.32 dB" or "-2.1dB") into a float
     * in dB. Returns 0.0 if the string is null, empty, or cannot be parsed.
     *
     * The function strips the trailing " dB" / "dB" suffix (case-insensitive) and any
     * leading/trailing whitespace before parsing the number, so it handles all common
     * tag formats from EAC, foobar2000, MusicBrainz Picard, and mp3gain.
     */
    private fun parseReplayGainDb(audio: Audio, mode: String): Float {
        val raw = if (mode == EqualizerPreferences.REPLAY_GAIN_MODE_ALBUM) {
            audio.replayGainAlbumGain ?: audio.replayGainTrackGain
        } else {
            audio.replayGainTrackGain ?: audio.replayGainAlbumGain
        } ?: return 0f

        return try {
            raw.trim()
                .replace(Regex("(?i)\\s*dB$"), "")
                .trim()
                .toFloat()
        } catch (_: NumberFormatException) {
            Log.w(TAG, "Could not parse ReplayGain string: '$raw'")
            0f
        }
    }

    /** Applies a new pan value immediately to the processor and persists it. */
    fun setBalance(pan: Float) {
        EqualizerPreferences.setBalance(pan)
        audioProcessorManager.applyBalance(pan)
    }

    /**
     * Applies playback speed and pitch to the ExoPlayer instance.
     *
     * Speed is a direct multiplier in [0.5 .. 2.0] (1.0 = normal). Pitch is supplied
     * as a semitone offset in [-12 .. +12] and is converted here using the standard
     * equal-temperament formula (multiplier = 2^(n/12)) before being handed off to
     * ExoPlayer, keeping the math in one place and the stored preferences human-readable.
     *
     * @param speed          Playback speed multiplier in [0.5 .. 2.0]. 1.0 = normal.
     * @param pitchSemitones Pitch offset in semitones. 0 = concert pitch, ±12 = ±1 octave.
     */
    private fun applyPlaybackParameters(speed: Float, pitchSemitones: Float) {
        val pitchMultiplier = 2f.pow(pitchSemitones / 12f).coerceIn(0.5f, 2.0f)
        player.playbackParameters = PlaybackParameters(speed, pitchMultiplier)
    }

    private val playerListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            super.onAudioSessionIdChanged(audioSessionId)
            Log.d(TAG, "Audio session ID changed: $audioSessionId")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val format = player.audioFormat
            if (format != null && format.pcmEncoding != C.ENCODING_INVALID) {
                val encodingName = when (format.pcmEncoding) {
                    C.ENCODING_PCM_16BIT -> "16-bit"
                    C.ENCODING_PCM_FLOAT -> "32-bit Float"
                    C.ENCODING_PCM_24BIT -> "24-bit"
                    C.ENCODING_PCM_32BIT -> "32-bit"
                    else -> "Other (${format.pcmEncoding})"
                }
                Log.i(TAG, "Audio Engine: ${format.sampleRate}Hz | Output: $encodingName")
                Log.i(TAG, "Song Info: Channels: ${format.channelCount}, Encoding: ${format.pcmEncoding}, Sample Rate: ${format.sampleRate}")
            }

            if (isPlaying) {
                MediaPlaybackManager.notifyPlaybackState(MediaConstants.PLAYBACK_PLAYING)
                startPeriodicStateSaving()
                startSnapshotPulse() // internally calls buildAndPushSnapshot() immediately, no need to call it again here
                broadcastWidgetUpdate()
                broadcastAutomationStatusChanged() // Fork (automation): hand-off.md B.1
            } else if (player.playbackState == Player.STATE_READY) {
                MediaPlaybackManager.notifyPlaybackState(MediaConstants.PLAYBACK_PAUSED)
                stopPeriodicStateSaving()
                stopSnapshotPulse()
                savePlaybackStateToDatabase() // Save immediately when paused
                broadcastWidgetUpdate()
                broadcastAutomationStatusChanged() // Fork (automation): hand-off.md B.1
            }

            // Fork (音楽端灯): the system-wide overlay lives exactly as long as playback —
            // attach on play, detach on pause/stop.
            updateMeteorOverlay()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) {
                // Silence the active native route (AAudio/Oboe) right now, on this thread,
                // instead of waiting for the internal playback thread to get around to its
                // own queued pause(). That thread may still be working through a backlog of
                // already-decoded buffers, which is what used to make pause() take seconds
                // to actually go quiet. See FelicityAudioSink.requestImmediatePause for why
                // this is safe to call from here.
                FelicityAudioSink.requestImmediatePause()
            }

            if (playWhenReady) {
                // If there's a song that was waiting for playback to start, record its play now.
                // This covers the case where a new queue is created and play() is called after
                // the item transition callback already fired (with playWhenReady still false).
                val deferredMediaId = pendingPlayRecordMediaId
                if (deferredMediaId != null) {
                    pendingPlayRecordMediaId = null
                    val audioId = deferredMediaId.toLongOrNull()
                    if (audioId != null) {
                        serviceScope.launch(Dispatchers.IO) {
                            val audio = audioRepository.getAudioById(audioId) ?: return@launch
                            songStatRepository.recordPlay(audio.hash)
                            Log.d(TAG, "Deferred play recorded for: ${audio.title}")
                        }
                    }
                }
            }

            if (AudioPreferences.isGaplessPlaybackEnabled().not()) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    // The track ended and the player paused itself automatically.
                    // Now we introduce our artificial gap.
                    serviceScope.launch(Dispatchers.Main) {
                        delay(GAP_DURATION_MS.milliseconds) // time of silence
                        player.play() // Move on to the next track
                    }
                }
            } else {
                // If gapless is enabled, we don't need to do anything special here.
                // The player will handle seamless transitions automatically.
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> MediaPlaybackManager.notifyPlaybackState(MediaConstants.PLAYBACK_BUFFERING)
                Player.STATE_READY -> {
                    // Emit the dedicated ready event first so observers (e.g. waveform loading)
                    // can react the moment the decoder is ready, regardless of whether the player
                    // will immediately start playing or remain paused.
                    MediaPlaybackManager.notifyPlaybackState(MediaConstants.PLAYBACK_READY)
                    if (player.playWhenReady) MediaPlaybackManager.notifyPlaybackState(MediaConstants.PLAYBACK_PLAYING)
                    else MediaPlaybackManager.notifyPlaybackState(MediaConstants.PLAYBACK_PAUSED)
                    // STATE_READY is the only moment when player.duration is guaranteed to be
                    // valid for the current track. We cache it here so that when the user skips
                    // the song, onPositionDiscontinuity can grab the outgoing track's duration
                    // from this field instead of asking player.duration (which by then already
                    // reflects the incoming track and is often C.TIME_UNSET while buffering).
                    if (player.duration > 0) {
                        currentItemDurationMs = player.duration
                    }
                    buildAndPushSnapshot()
                }
                Player.STATE_ENDED -> {
                    // Only treat as a true "ended" event in REPEAT_OFF mode.
                    // For REPEAT_ONE / REPEAT_QUEUE, ExoPlayer loops automatically and
                    // STATE_ENDED is never actually reached.
                    if (PlayerPreferences.getRepeatMode() == MediaConstants.REPEAT_OFF) {
                        MediaPlaybackManager.handleQueueEnded()
                    }
                    stopPeriodicStateSaving()
                    stopSnapshotPulse()
                    savePlaybackStateToDatabase()
                }
                Player.STATE_IDLE -> {
                    MediaPlaybackManager.notifyPlaybackState(MediaConstants.PLAYBACK_STOPPED)
                    stopPeriodicStateSaving()
                    stopSnapshotPulse()
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            when (error.errorCode) {
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> {
                    Log.e(TAG, "Decoding error for current track: ${error.message} (code: ${error.errorCode})")

                    val failedItem = player.currentMediaItem

                    if (ffmpegFallbackActive) {
                        // FFmpeg also failed – give up, restore original decoder and skip.
                        Log.w(TAG, "FFmpeg fallback also failed for '${failedItem?.mediaId}', skipping track and restoring decoder.")
                        ffmpegFallbackActive = false
                        ffmpegFallbackItem = null
                        // Notify user that the track could not be played by any available decoder.
                        playbackErrorNotifier.notifyPlaybackError(
                                failedItem?.mediaId,
                                error
                        )
                        // Restore user's original decoder choice silently (no pref write – just engine mode).
                        restoreDecoderMode(preFallbackDecoder)
                        skipOrRestartTrack()
                    } else if (AudioPreferences.isFallbackToSoftwareDecoderEnabled()
                            && AudioPreferences.getAudioDecoder() != AudioPreferences.FFMPEG) {
                        // Primary decoder failed and fallback is enabled – try FFmpeg silently.
                        Log.i(TAG, "Primary decoder failed; silently retrying '${failedItem?.mediaId}' with FFmpeg.")
                        preFallbackDecoder = AudioPreferences.getAudioDecoder()
                        ffmpegFallbackActive = true
                        ffmpegFallbackItem = failedItem
                        retryWithFfmpegFallback(failedItem)
                    } else {
                        // Fallback disabled, or already on FFmpeg – just skip.
                        Log.w(TAG, "Skipping track (fallback disabled or already using FFmpeg).")
                        ffmpegFallbackActive = false
                        ffmpegFallbackItem = null
                        // Notify user why the track was skipped.
                        playbackErrorNotifier.notifyPlaybackError(
                                failedItem?.mediaId,
                                error
                        )
                        skipOrRestartTrack()
                    }
                }
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> {
                    Log.e(TAG, "File not found: ${error.message} (code: ${error.errorCode})")
                    playbackErrorNotifier.notifyPlaybackError(
                            player.currentMediaItem?.mediaId,
                            error
                    )
                    skipOrRestartTrack()
                }
                else -> {
                    Log.e(TAG, "Playback error: ${error.message} (code: ${error.errorCode})")
                    Log.e(TAG, "Player error: ${error.errorCodeName}", error)
                    playbackErrorNotifier.notifyPlaybackError(
                            player.currentMediaItem?.mediaId,
                            error
                    )
                    MediaPlaybackManager.notifyPlaybackState(MediaConstants.PLAYBACK_ERROR)
                    stopPeriodicStateSaving()
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // If a track transition happened naturally (not via fallback retry), clear any stale fallback state.
            if (ffmpegFallbackActive && mediaItem != ffmpegFallbackItem) {
                Log.d(TAG, "Track transitioned away from fallback item; restoring original decoder and clearing fallback state.")
                ffmpegFallbackActive = false
                ffmpegFallbackItem = null
                restoreDecoderMode(preFallbackDecoder)
            }

            // Only bother recording anything if the player was actually running before the
            // transition — there's no point counting stats for songs the user skipped while
            // the player was sitting paused in the background.
            val prevMediaId = previousItemMediaId
            if (wasPlayingBeforeTransition && prevMediaId != null) {
                // A skip is when the user actively moves FORWARD away from a song before hearing
                // at least 30% of it. Two things can trigger this in a forward direction:
                //   1. The user taps next / seeks to a later track (REASON_SEEK, going forward).
                //   2. The user swipes the current song out of the queue (REASON_PLAYLIST_CHANGED).
                // A natural song ending (REASON_AUTO) is never a skip — the song finished!
                // Going backward is also never a skip — that's a replay, handled separately below.
                val isForwardNavigation = MediaPlaybackManager.lastNavigationDirection
                val isManuallySwitched = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK
                        || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
                if (isManuallySwitched
                        && isForwardNavigation
                        && previousItemDurationMs > 0
                        && previousItemEndPositionMs < previousItemDurationMs * SKIP_THRESHOLD) {
                    serviceScope.launch(Dispatchers.IO) {
                        val audioId = prevMediaId.toLongOrNull() ?: return@launch
                        val audio = audioRepository.getAudioById(audioId) ?: return@launch
                        songStatRepository.recordSkip(audio.hash)
                        Log.d(TAG, "Skip recorded for: ${audio.title} (pos=${previousItemEndPositionMs}ms / dur=${previousItemDurationMs}ms, reason=$reason)")
                    }
                }
            }

            // Record a play event for the newly active song, but only when the player is
            // actually going to play it — skip this if the queue is being browsed while paused.
            // Also check whether this was a backward navigation and count it as a replay if so.
            mediaItem?.let { item ->
                previousItemMediaId = item.mediaId
                // While a queue replacement is in flight, ExoPlayer fires intermediate transitions
                // (e.g. the first item of the new list lands before seekTo moves to the real target).
                // We must not record those as plays — instead park the ID so it can be flushed once
                // the replacement is complete and the intended song actually starts playing.
                val replacingQueue = MediaPlaybackManager.isQueueBeingReplaced
                if (player.playWhenReady && !replacingQueue) {
                    pendingPlayRecordMediaId = null
                    val audioId = item.mediaId.toLongOrNull() ?: return@let
                    val isBackwardNavigation = !MediaPlaybackManager.lastNavigationDirection
                    serviceScope.launch(Dispatchers.IO) {
                        val audio = audioRepository.getAudioById(audioId) ?: return@launch
                        songStatRepository.recordPlay(audio.hash)
                        Log.d(TAG, "Play recorded for: ${audio.title}")
                        // When the user goes back to a song on purpose, that counts as a replay —
                        // it's their way of saying "that one was worth hearing again!"
                        if (isBackwardNavigation && reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                            songStatRepository.recordReplay(audio.hash)
                            Log.d(TAG, "Replay recorded for: ${audio.title}")
                        }
                    }
                } else {
                    // Either the player isn't set to play yet, or the queue is still being
                    // replaced — park the ID and wait for playback to actually begin.
                    pendingPlayRecordMediaId = item.mediaId
                    Log.d(TAG, "Deferring play stat for: ${item.mediaId} (playWhenReady=${player.playWhenReady}, replacingQueue=$replacingQueue)")
                }
            } ?: run {
                previousItemMediaId = null
                pendingPlayRecordMediaId = null
            }

            MediaPlaybackManager.notifyCurrentPosition(player.currentMediaItemIndex)

            // After notifyCurrentPosition runs, the queue-replacement guard may have just been
            // lifted (it clears itself once ExoPlayer confirms the intended seek position).
            // If the player is already set to play, and we still have a parked ID, this is our
            // window to flush it — onPlayWhenReadyChanged won't fire because playWhenReady
            // never went false during an already-playing queue swap.
            val deferredId = pendingPlayRecordMediaId
            if (deferredId != null && player.playWhenReady && !MediaPlaybackManager.isQueueBeingReplaced) {
                pendingPlayRecordMediaId = null
                val audioId = deferredId.toLongOrNull()
                if (audioId != null) {
                    serviceScope.launch(Dispatchers.IO) {
                        val audio = audioRepository.getAudioById(audioId) ?: return@launch
                        songStatRepository.recordPlay(audio.hash)
                        Log.d(TAG, "Deferred play flushed (post-replace) for: ${audio.title}")
                    }
                }
            }

            savePlaybackStateToDatabase() // Save when track changes
            buildAndPushSnapshot()
            broadcastWidgetUpdate()
            broadcastAutomationTrackChanged() // Fork (automation): hand-off.md B.1

            // Apply tag-based ReplayGain for the incoming track when auto-RG is on.
            // We look up the Audio object from the database on the IO thread, parse the
            // gain string (e.g. "+5.32 dB"), and forward the dB value to the processor.
            // When auto-RG is off, or the track has no tag, we reset to unity (0 dB) so
            // the previous track's gain never bleeds into the next one.
            mediaItem?.let { item ->
                val audioId = item.mediaId.toLongOrNull()
                if (audioId != null) {
                    serviceScope.launch(Dispatchers.IO) {
                        val audio = audioRepository.getAudioById(audioId)
                        val db = if (EqualizerPreferences.isAutoReplayGainEnabled() && audio != null) {
                            parseReplayGainDb(audio, EqualizerPreferences.getReplayGainMode())
                        } else {
                            0f
                        }
                        audioProcessorManager.applyTagReplayGain(db)
                        if (db != 0f) {
                            Log.d(TAG, "Auto-RG applied: ${db}dB for track: ${audio?.title}")
                        }
                    }
                }
            }

            // Refresh the notification custom layout so the favorite button reflects
            // the new song's favorite state straight away, without any extra user interaction.
            val isFavorite = MediaPlaybackManager.getCurrentSong()?.isFavorite ?: false
            val repeatMode = PlayerPreferences.getRepeatMode()
            mediaSession?.setCustomLayout(listOf(buildRepeatCommandButton(repeatMode), buildFavoriteCommandButton(isFavorite)))
        }

        override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
        ) {
            super.onPositionDiscontinuity(oldPosition, newPosition, reason)
            // We want to capture the outgoing song's position and duration before ExoPlayer
            // hands control to the next item. This fires BEFORE onMediaItemTransition, so
            // player.duration still reflects the old track — perfect timing for us.
            //
            // We also handle DISCONTINUITY_REASON_REMOVE here because when the currently
            // playing song is swiped away from the queue, the new current item can land at
            // the same index slot (e.g., song 0 removed → song 1 becomes the new index 0),
            // so checking mediaItemIndex alone would miss that removal entirely.
            val isItemRemoved = reason == Player.DISCONTINUITY_REASON_REMOVE
            val isItemSwitch = oldPosition.mediaItemIndex != newPosition.mediaItemIndex
            if (isItemRemoved || isItemSwitch) {
                previousItemEndPositionMs = oldPosition.positionMs
                // Use our cached duration rather than player.duration — by this point
                // ExoPlayer has already pointed its internal state at the next item, so
                // player.duration would give us the INCOMING song's length (or C.TIME_UNSET
                // if it is still buffering), not the outgoing song we actually want to measure.
                previousItemDurationMs = currentItemDurationMs
                // Snapshot the playing intent NOW — by the time onMediaItemTransition fires the
                // player is often already buffering the next track, which makes isPlaying return
                // false even though the user is absolutely in "play" mode. Using playWhenReady
                // instead captures what the user actually wants, not a fleeting buffer state.
                wasPlayingBeforeTransition = player.playWhenReady
                // Reset the cached duration so it does not bleed into the next track's skip
                // check in the unlikely event that STATE_READY is delayed for the new item.
                currentItemDurationMs = 0L
            }
        }
    }

    /**
     * Captures decoder initialization and compressed-source format changes so the
     * [AudioPipelineSnapshot] always reflects the active decoder name and track format.
     *
     * Both callbacks fire on the main thread, so accessing [player] and calling
     * [buildAndPushSnapshot] is safe without any additional dispatching.
     */
    private val analyticsListener = object : AnalyticsListener {

        override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
        ) {
            currentDecoderName = decoderName
            Log.d(TAG, "Audio decoder initialized: $decoderName")
            buildAndPushSnapshot()
        }

        override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            currentAudioInputFormat = format
            Log.d(TAG, "Audio input format changed: ${format.sampleMimeType} @ ${format.sampleRate}Hz")
            buildAndPushSnapshot()
        }
    }

    /**
     * Listens for audio output device additions and removals (e.g., plugging in wired
     * headphones or connecting a Bluetooth device). On each change the active output device
     * is re-detected and a fresh snapshot is pushed.
     */
    private val audioDeviceCallback = object : AudioDeviceCallback() {

        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            currentOutputDevice = detectActiveOutputDevice()
            Log.d(TAG, "Audio device added: ${addedDevices.firstOrNull()?.productName}")
            buildAndPushSnapshot()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            currentOutputDevice = detectActiveOutputDevice()
            Log.d(TAG, "Audio device removed: ${removedDevices.firstOrNull()?.productName}")
            buildAndPushSnapshot()
        }
    }

    /**
     * Tracks USB DAC connect/disconnect events so the pipeline snapshot is refreshed
     * any time the direct USB output path is activated or deactivated.
     *
     * When the DAC attaches, the snapshot will reflect the new direct USB output. When it
     * detaches, the snapshot reverts to showing whichever path (AAudio or AudioTrack) takes
     * over. The actual audio rerouting is handled inside [FelicityAudioSink], not here.
     */
    private val usbDacManagerListener = object : UsbDacManager.Listener {
        override fun onUsbDacAttached(sampleRate: Int, channelCount: Int) {
            currentOutputDevice = detectActiveOutputDevice()
            Log.i(TAG, "USB DAC stream active — rebuilding pipeline snapshot " +
                    "(DAC negotiated at $sampleRate Hz / $channelCount ch)")
            buildAndPushSnapshot()
        }

        override fun onUsbDacDetached() {
            currentOutputDevice = detectActiveOutputDevice()
            Log.i(TAG, "USB DAC stream stopped — reverting pipeline snapshot")
            buildAndPushSnapshot()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            AudioPreferences.AUDIO_DECODER -> {
                Log.d(TAG, "Audio decoder preference changed, switching decoder...")
                switchDecoder()
            }
            AudioPreferences.HIRES_OUTPUT -> {
                val hiresEnabled = AudioPreferences.isHiresOutputEnabled()
                Log.d(TAG, "Hi-Res output preference changed to: $hiresEnabled")
                switchAudioMode()
            }
            AudioPreferences.GAPLESS_PLAYBACK -> {
                // Reconfigure gapless playback when preference changes
                configureGaplessPlayback()
                Log.d(TAG, "Gapless playback preference changed to: ${AudioPreferences.isGaplessPlaybackEnabled()}")
            }
            AudioPreferences.SKIP_SILENCE -> {
                setSilenceState()
                Log.d(TAG, "Skip silence preference changed to: ${AudioPreferences.isSkipSilenceEnabled()} (Note: Skip silence is currently disabled for all modes)")
            }
            AudioPreferences.IS_STEREO_DOWNMIX_FORCED -> {
                val enabled = AudioPreferences.isStereoDownmixForced()
                Log.d(TAG, "Stereo downmix preference changed to: $enabled — rebuilding audio pipeline...")
                // Rebuilding the player re-invokes buildAudioSink which re-reads the preference
                // and re-assembles the processor chain with or without the downmix processor.
                switchAudioMode()
            }
            AudioPreferences.OUTPUT_SINK -> {
                val sink = AudioPreferences.getOutputSink()
                Log.d(TAG, "Output sink preference changed to: $sink — rebuilding audio pipeline...")
                // Rebuilding the player creates a fresh FelicityAudioSink, which will open the
                // correct stream immediately. Without this, the change only takes effect the
                // next time a song starts — the existing sink never learns about the preference flip.
                switchAudioMode()
            }
            PlayerPreferences.REPEAT_MODE -> {
                val repeatMode = PlayerPreferences.getRepeatMode()
                Log.d(TAG, "Repeat mode preference changed to: $repeatMode")
                applyRepeatMode(repeatMode)
            }
            ShufflePreferences.SHUFFLE -> {
                val shuffleEnabled = ShufflePreferences.isShuffleEnabled()
                Log.d(TAG, "Shuffle preference changed to: $shuffleEnabled")
                MediaPlaybackManager.setShuffleEnabled(shuffleEnabled)
            }
            MeteorPreferences.ENABLED,
            MeteorPreferences.OVERLAY -> {
                // Fork (音楽端灯): reconcile the system-wide overlay with the new toggles.
                updateMeteorOverlay()
            }
            AppearancePreferences.THEME,
            AppearancePreferences.ACCENT_COLOR -> {
                // Theme or accent color changed — nudge the widget so it redraws
                // with the fresh colors without waiting for the next song event.
                broadcastWidgetUpdate()
            }
            EqualizerPreferences.BALANCE -> {
                val pan = EqualizerPreferences.getBalance()
                Log.d(TAG, "Balance preference changed to: $pan")
                applyBalanceToProcessor(pan)
            }
            EqualizerPreferences.STEREO_WIDTH -> {
                val width = EqualizerPreferences.getStereoWidth()
                Log.d(TAG, "Stereo width preference changed to: $width")
                applyStereoWidthToProcessor(width)
            }
            EqualizerPreferences.TAPE_SATURATION_DRIVE -> {
                val drive = EqualizerPreferences.getTapeSaturationDrive()
                Log.d(TAG, "Tape saturation drive preference changed to: $drive")
                applyTapeSaturationDriveToProcessor(drive)
            }
            EqualizerPreferences.KARAOKE_MODE_ENABLED -> {
                val enabled = EqualizerPreferences.isKaraokeModeEnabled()
                Log.d(TAG, "Karaoke mode preference changed to: $enabled")
                applyKaraokeModeToProcessor(enabled)
            }
            EqualizerPreferences.NIGHT_MODE_ENABLED -> {
                val enabled = EqualizerPreferences.isNightModeEnabled()
                Log.d(TAG, "Night mode preference changed to: $enabled")
                applyNightModeToProcessor(enabled)
            }
            EqualizerPreferences.EQ_ENABLED -> {
                val enabled = EqualizerPreferences.isEqEnabled()
                Log.d(TAG, "Equalizer enabled preference changed to: $enabled")
                EqualizerManager.setEnabled(enabled)
            }
            EqualizerPreferences.EQ_MODE -> {
                // When the user flips between graphic and parametric mode, reload the
                // appropriate EQ state so the transition is seamless with no dead silence.
                val mode = EqualizerPreferences.getEqMode()
                Log.d(TAG, "EQ mode changed to: $mode")
                if (EqualizerPreferences.isParametricEqMode()) {
                    EqualizerManager.applyPeqBandsFromPreference()
                } else {
                    audioProcessorManager.applyEqualizerState()
                }
            }
            EqualizerPreferences.PEQ_BANDS_RAW -> {
                Log.d(TAG, "Parametric EQ bands preference changed")
                EqualizerManager.applyPeqBandsFromPreference()
            }
            EqualizerPreferences.PREAMP_DB -> {
                Log.d(TAG, "EQ preamp preference changed")
                EqualizerManager.applyPreampFromPreference()
            }
            EqualizerPreferences.BASS_DB -> {
                val db = EqualizerPreferences.getBassDb()
                Log.d(TAG, "Bass gain preference changed to: ${db}dB")
                audioProcessorManager.applyBass(db)
            }
            EqualizerPreferences.TREBLE_DB -> {
                val db = EqualizerPreferences.getTrebleDb()
                Log.d(TAG, "Treble gain preference changed to: ${db}dB")
                audioProcessorManager.applyTreble(db)
            }
            EqualizerPreferences.REPLAY_GAIN_DB -> {
                val db = EqualizerPreferences.getReplayGainDb()
                Log.d(TAG, "Replay gain preference changed to: ${db}dB")
                audioProcessorManager.applyReplayGain(db)
            }
            EqualizerPreferences.REVERB_MIX,
            EqualizerPreferences.REVERB_DECAY,
            EqualizerPreferences.REVERB_DAMP,
            EqualizerPreferences.REVERB_SIZE -> {
                val mix = EqualizerPreferences.getReverbMix()
                val decay = EqualizerPreferences.getReverbDecay()
                val damp = EqualizerPreferences.getReverbDamp()
                val size = EqualizerPreferences.getReverbSize()
                Log.d(TAG, "Reverb preference changed — mix=$mix, decay=$decay, damp=$damp, size=$size")
                audioProcessorManager.applyReverb(mix, decay, damp, size)
            }
            EqualizerPreferences.PITCH,
            EqualizerPreferences.PLAYBACK_SPEED -> {
                val pitch = EqualizerPreferences.getPitch()
                val speed = EqualizerPreferences.getPlaybackSpeed()
                Log.d(TAG, "Playback parameters changed — speed=${speed}x, pitch=${pitch}x")
                applyPlaybackParameters(speed, pitch)
            }
            else -> {
                // Handle each individual EQ band preference change
                if (key != null && key.startsWith(EqualizerPreferences.EQ_BAND_KEY_PREFIX)) {
                    val bandIndex = key.removePrefix(EqualizerPreferences.EQ_BAND_KEY_PREFIX).toIntOrNull()
                    if (bandIndex != null) {
                        Log.d(TAG, "EQ band $bandIndex preference changed")
                        EqualizerManager.applyBandFromPreference(bandIndex)
                    }
                }
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePlaybackStateToDatabase()
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        savePlaybackStateToDatabase()
        unregisterSharedPreferenceChangeListener()

        // Fork (音楽端灯): drop the system-wide overlay and its screen-state receiver
        // before the service context disappears.
        removeMeteorOverlay()
        runCatching { unregisterReceiver(meteorScreenReceiver) }

        // Remove the service-level USB DAC listener first so it no longer receives
        // attach/detach events during the rest of teardown.
        UsbDacManager.removeListener(usbDacManagerListener)

        // Stop the periodic snapshot pulse before releasing resources.
        stopSnapshotPulse()

        // Unregister the audio-device-change callback so no stale reference is held after teardown.
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)

        // Clear the snapshot so observers know the pipeline is no longer active.
        AudioPipelineManager.updateSnapshot(null)

        // Detach the equalizer processor reference before releasing the player so the
        // manager does not hold a stale reference after teardown.
        EqualizerManager.detachProcessor()

        // Clear the visualizer processor reference so no stale direct-output connection
        // remains after the service has been destroyed.
        VisualizerManager.processor = null

        // Release the player and media session first. ExoPlayer.release() blocks until its
        // internal playback thread finishes, which includes FelicityAudioSink.release().
        // That call removes the sink's USB DAC listener from UsbDacManager on the correct
        // playback thread. Only after this is safe to call UsbDacDriver.detach(), because
        // notifyDetached() will find no sink listener left and won't try to release
        // DefaultAudioSink from the wrong thread (which is what was crashing before).
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }

        // Now it is safe to tear down the USB driver. The sink listener is already gone,
        // so notifyDetached() won't cascade into DefaultAudioSink.release().
        UsbDacDriver.getInstance(applicationContext).detach()

        super.onDestroy()
    }

    private fun savePlaybackStateToDatabase() {
        serviceScope.launch {
            PlaybackStateManager.saveCurrentPlaybackState(applicationContext, TAG)
        }
    }

    /**
     * Sends a broadcast to the home screen widget so it can redraw itself with the
     * latest song info and play/pause icon — no polling required.
     *
     * The broadcast is targeted directly at [FelicityWidgetProvider] so it never
     * leaks to other apps. The song metadata is attached as Intent extras; the
     * widget saves them to [WidgetStatePrefs] on receipt so the next cold draw
     * (days later) still shows the correct song title.
     *
     * Called from the player listener whenever the song or isPlaying state changes.
     */
    private fun broadcastWidgetUpdate() {
        val mediaItem = player.currentMediaItem ?: return
        val currentSong = MediaPlaybackManager.getCurrentSong()
        val title = currentSong?.getProperTitle()
        val artist = currentSong?.getProperArtists()
        val isPlaying = player.isPlaying
        val songId = mediaItem.mediaId.toLongOrNull() ?: -1L

        val intent = Intent("app.simple.felicity.ACTION_WIDGET_UPDATE").apply {
            component = ComponentName(
                    applicationContext.packageName,
                    "app.simple.felicity.widget.FelicityWidgetProvider"
            )
            putExtra("extra_title", title)
            putExtra("extra_artist", artist)
            putExtra("extra_is_playing", isPlaying)
            putExtra("extra_song_id", songId)
        }
        sendBroadcast(intent)
    }

    /**
     * Fork (automation): emits the `shiroikuma.ongaku.STATUS_CHANGED` contract broadcast
     * consumed by the 自由作業盤 (hand-off.md B.1). Called from the player listener on
     * every play/pause flip, right next to [broadcastWidgetUpdate].
     */
    private fun broadcastAutomationStatusChanged() {
        AutomationBroadcasts.sendStatusChanged(
                applicationContext,
                MediaPlaybackManager.getCurrentSong(),
                !player.isPlaying
        )
    }

    /**
     * Fork (automation): emits the `shiroikuma.ongaku.TRACK_CHANGED` contract broadcast
     * (hand-off.md B.1). Called on every media-item transition and after every favorite
     * toggle that goes through [COMMAND_TOGGLE_FAVORITE].
     */
    private fun broadcastAutomationTrackChanged() {
        AutomationBroadcasts.sendTrackChanged(
                applicationContext,
                MediaPlaybackManager.getCurrentSong(),
                !player.isPlaying
        )
    }

    /**
     * Starts a coroutine that pushes a refreshed [AudioPipelineSnapshot] to
     * [AudioPipelineManager] every 3 seconds while playback is active.
     *
     * The coroutine runs on the main dispatcher so [player] state can be read safely.
     * If a pulse job is already active this is a no-op.
     */
    private fun startSnapshotPulse() {
        if (snapshotPulseJob?.isActive == true) return

        snapshotPulseJob = serviceScope.launch(Dispatchers.Main.immediate) {
            buildAndPushSnapshot() // fire once immediately so the UI never waits
            while (isActive) {
                delay(3_000L.milliseconds)
                buildAndPushSnapshot()
            }
        }

        Log.d(TAG, "Started snapshot pulse")
    }

    /**
     * Cancels the running snapshot pulse and any pending debounced push.
     */
    private fun stopSnapshotPulse() {
        snapshotPulseJob?.cancel()
        snapshotPulseJob = null
        snapshotDebounceJob?.cancel()
        snapshotDebounceJob = null
        Log.d(TAG, "Stopped snapshot pulse")
    }

    /**
     * Assembles a fully-populated [AudioPipelineSnapshot] from live system sources and
     * pushes it to [AudioPipelineManager].
     *
     * Every field here is read directly from the player, the system's AudioManager, or the
     * DSP processor's own state — never from user preferences — so the snapshot always
     * reflects what is actually happening in the audio pipeline rather than what the user
     * asked for.
     *
     * Must be called from the main thread because several [ExoPlayer] API calls
     * (e.g., [ExoPlayer.audioFormat]) are not thread-safe. All call sites guarantee
     * this by using [Dispatchers.Main] or being inside main-thread callbacks.
     *
     * This method is debounced: rapid successive calls (e.g. during a song transition when
     * the analytics listener, player listener, and snapshot pulse all fire within ~200ms)
     * are coalesced into a single trailing push. Only the last call in a burst wins.
     */
    private fun buildAndPushSnapshot() {
        if (!::player.isInitialized) return
        snapshotDebounceJob?.cancel()
        snapshotDebounceJob = serviceScope.launch(Dispatchers.Main) {
            delay(SNAPSHOT_DEBOUNCE_MS.milliseconds)
            buildAndPushSnapshotNow()
        }
    }

    /**
     * Performs the actual snapshot assembly and push without any debounce guard.
     * All callers must route through [buildAndPushSnapshot] for debounce protection;
     * this function exists only as the target of the debounced coroutine.
     */
    private fun buildAndPushSnapshotNow() {
        if (!::player.isInitialized) return

        val inputFormat = currentAudioInputFormat
        val dspInputFormat = audioProcessorManager.nativeDspProcessor.currentInputFormat

        val outputDevice = currentOutputDevice ?: detectActiveOutputDevice().also {
            currentOutputDevice = it
        }

        // Track metadata from the compressed source format
        val trackFormat = mimeTypeToFormatString(inputFormat?.sampleMimeType)
        val bitDepth = when {
            inputFormat?.pcmEncoding != null && inputFormat.pcmEncoding != Format.NO_VALUE -> {
                pcmEncodingToBitDepth(inputFormat.pcmEncoding)
            }
            else -> 16
        }
        val sampleRateHz = inputFormat?.sampleRate?.takeIf { it > 0 } ?: 0
        val bitrateKbps = (inputFormat?.bitrate?.takeIf { it != Format.NO_VALUE } ?: 0) / 1000
        val channels = inputFormat?.channelCount?.takeIf { it > 0 } ?: 0

        // Decoder info
        val decoderLabel = when {
            currentDecoderName.contains("ffmpeg", ignoreCase = true) -> "白い熊 音楽 Native FFmpeg Decoder"
            currentDecoderName.contains("c2.", ignoreCase = true) -> currentDecoderName
            currentDecoderName != "Unknown" -> currentDecoderName
            AudioPreferences.getAudioDecoder() == AudioPreferences.FFMPEG -> "白い熊 音楽 Native FFmpeg Decoder (pending)"
            else -> "Android Built-in (pending)"
        }

        // Resampler state: keep source and DSP rates for later characterization
        val inputSampleRate = sampleRateHz

        // The DSP processor runs at the same sample rate the decoder hands it, which in normal
        // operation equals the source track's rate. However, `dspInputFormat` can briefly hold
        // a stale value from the previous track because ExoPlayer fires `onAudioInputFormatChanged`
        // (which updates `sampleRateHz`) before it reconfigures the audio processor chain for
        // the new track. Trusting `dspInputFormat.sampleRate` unconditionally during that window
        // would produce phantom "SW + HW resampling" entries on every track transition.
        //
        // The safe rule: use `dspInputFormat.sampleRate` only when it agrees with the source
        // rate, meaning the processor chain has been freshly configured for this track. If they
        // disagree, the source rate IS the ground truth (ExoPlayer does not resample between
        // its decoder and our processor chain under standard conditions).
        val dspSampleRateHz = when {
            sampleRateHz > 0 && dspInputFormat.sampleRate == sampleRateHz -> dspInputFormat.sampleRate
            sampleRateHz > 0 -> sampleRateHz
            dspInputFormat.sampleRate > 0 -> dspInputFormat.sampleRate
            else -> 0
        }

        // DSP state
        val dspFormatStr = pcmEncodingToFormatString(dspInputFormat.encoding)
        val activeEqName = when {
            !EqualizerPreferences.isEqEnabled() -> null
            EqualizerPreferences.getAllBandGains().all { it == 0f }
                    && EqualizerPreferences.getBassDb() == 0f
                    && EqualizerPreferences.getTrebleDb() == 0f -> "Flat"
            else -> "Custom"
        }
        val stereoExpandPercent = (EqualizerPreferences.getStereoWidth() * 100).roundToInt()

        // Buffer and latency estimation from actual AudioTrack minimum buffer size
        val (buffersStr, latencyEstimateMs) = computeBufferInfo(dspInputFormat)

        // Forward the current pipeline latency to the native DSP engine so the FFT
        // visualizer pre-delays its input by exactly this amount. This call covers all
        // trigger paths — playback start, track transition, format change, and device
        // change — because every one of them routes through buildAndPushSnapshot().
        audioProcessorManager.applyOutputLatency(latencyEstimateMs)

        // The PCM encoding that reaches the AudioSink is whatever the DSP processor is
        // configured to receive — it is the last processor in the chain before the sink.
        // We deliberately do NOT use `player.audioFormat?.pcmEncoding` here because that
        // returns the encoding from the compressed container metadata (e.g., 24-bit FLAC),
        // not the decoded PCM encoding. ExoPlayer's decoders truncate 24-bit PCM to 16-bit
        // unless the hi-res float output path is active, so `dspInputFormat.encoding` is the
        // only source that reflects the actual bit depth flowing through the pipeline right now.
        val deviceBitDepthIn = pcmEncodingToBitDepth(
                dspInputFormat.encoding.takeIf { it != Format.NO_VALUE } ?: C.ENCODING_PCM_16BIT
        )

        // The hardware HAL mixer rate is what AudioFlinger actually runs at, which may differ
        // from the source or DSP rate. Reading it directly from AudioManager avoids the guess
        // work of picking from a device's supported-rates list.
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val halNativeSampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0

        // Hardware output device info
        val deviceName = outputDevice?.productName?.toString() ?: "Unknown"
        val deviceBitDepthOut = getDeviceBitDepth(outputDevice, deviceBitDepthIn)

        // Use the HAL's declared native rate as the true hardware sample rate, falling back
        // to probing the device's supported rates list if the property isn't available.
        val deviceSampleRate = if (halNativeSampleRate > 0) {
            halNativeSampleRate
        } else {
            getDeviceSampleRate(outputDevice, sampleRateHz)
        }

        // Full resampler characterisation — requires deviceSampleRate to detect HAL-level resampling.
        // SW resampling: ExoPlayer/Android pipeline changes rate before AudioTrack.
        // HW resampling: the AudioTrack/HAL resamples because its native rate ≠ what we write.
        val swResampling = inputSampleRate > 0 && inputSampleRate != dspSampleRateHz
        val hwResampling = dspSampleRateHz > 0 && dspSampleRateHz != deviceSampleRate
        val resamplerType = when {
            swResampling && hwResampling -> "SW + HW"
            swResampling -> "Software"
            hwResampling -> "Hardware (HAL)"
            else -> "None"
        }
        val resamplerQuality = when {
            swResampling && hwResampling -> "Android SRC + HAL"
            swResampling -> "Android SRC"
            hwResampling -> "HAL Native"
            else -> "Passthrough"
        }
        // Nyquist anti-aliasing cutoff = min rate in the chain ÷ 2
        val resamplerCutoffHz = if (swResampling || hwResampling) {
            listOf(inputSampleRate, dspSampleRateHz, deviceSampleRate)
                .filter { it > 0 }
                .minOrNull()
                ?.div(2) ?: 0
        } else {
            0
        }

        // Determine the true boundaries of the resampling chain for the UI
        // If SW resampling happens, the chain starts at the input file's rate. Otherwise, it starts at the DSP rate.
        val effectiveInRate = if (swResampling) inputSampleRate else dspSampleRateHz

        // If HW resampling happens, the chain ends at the hardware's forced rate. Otherwise, it ends at the DSP rate.
        val effectiveOutRate = if (hwResampling) deviceSampleRate else dspSampleRateHz

        // Reflect the true active output sink, not just user preferences. The priority matches
        // FelicityAudioSink's own routing logic: USB DAC beats AAudio, which beats AudioTrack.
        // If AAudio was enabled but the stream failed to open, this correctly shows "AudioTrack"
        // instead of falsely advertising a low-latency path that isn't actually running.
        val audioOutputMode = when {
            UsbDacManager.isActive -> "USB DAC (Direct)"
            FelicityAudioSink.isAAudioStreamActive -> "AAudio"
            FelicityAudioSink.isOboeStreamActive -> "Oboe"
            else -> "AudioTrack"
        }

        val snapshot = AudioPipelineSnapshot(
                trackFormat = trackFormat,
                bitDepth = bitDepth,
                sampleRateHz = sampleRateHz,
                bitrateKbps = bitrateKbps,
                channels = channels,
                decoderName = decoderLabel,
                inputSampleRate = inputSampleRate,
                outputSampleRate = dspSampleRateHz,
                resamplerType = resamplerType,
                resamplerQuality = resamplerQuality,
                resamplerCutoffHz = resamplerCutoffHz,
                effectiveInputSampleRate = effectiveInRate,
                effectiveOutputSampleRate = effectiveOutRate,
                dspFormat = dspFormatStr,
                dspSampleRate = dspSampleRateHz,
                activeEqName = activeEqName,
                stereoExpandPercent = stereoExpandPercent,
                buffers = buffersStr,
                latencyMs = latencyEstimateMs,
                visualizerLatencyMs = latencyEstimateMs,
                audioOutputMode = audioOutputMode,
                deviceName = deviceName,
                deviceBitDepthIn = deviceBitDepthIn,
                deviceBitDepthOut = deviceBitDepthOut,
                deviceSampleRate = deviceSampleRate
        )

        AudioPipelineManager.updateSnapshot(snapshot)
        Log.v(TAG, "Pipeline snapshot updated: $trackFormat @ ${sampleRateHz}Hz via $decoderLabel → $deviceName")
    }

    /**
     * Converts a MIME type string (e.g., `"audio/flac"`) to a short human-readable format
     * label (e.g., `"FLAC"`). Falls back to the subtype in uppercase for unknown types.
     *
     * @param mimeType The MIME type from [Format.sampleMimeType], or `null`.
     * @return A short uppercase label describing the audio format.
     */
    private fun mimeTypeToFormatString(mimeType: String?): String = when {
        mimeType == null -> "Unknown"
        mimeType.contains("flac", ignoreCase = true) -> "FLAC"
        mimeType.contains("mp4a") || mimeType.contains("aac") -> "AAC"
        mimeType.contains("mpeg") || mimeType.contains("mp3") -> "MP3"
        mimeType.contains("vorbis") -> "OGG"
        mimeType.contains("opus") -> "OPUS"
        mimeType.contains("wav") || mimeType.contains("wave") -> "WAV"
        mimeType.contains("alac") -> "ALAC"
        mimeType.contains("aiff") -> "AIFF"
        mimeType.contains("wma") -> "WMA"
        mimeType.contains("raw") -> "PCM"
        mimeType.contains("dsd") || mimeType.contains("dsf") -> "DSD"
        mimeType.contains("ape") -> "APE"
        else -> mimeType.substringAfterLast('/', mimeType).uppercase()
    }

    /**
     * Maps a Media3 [C.ENCODING_PCM_*] constant to a bit-depth integer.
     *
     * @param encoding A PCM encoding constant from [C].
     * @return The bit depth (8, 16, 24, or 32), defaulting to 16 for unknown encodings.
     */
    private fun pcmEncodingToBitDepth(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT -> 32
        C.ENCODING_PCM_FLOAT -> 32
        else -> 16
    }

    /**
     * Maps a Media3 [C.ENCODING_PCM_*] constant to a human-readable DSP format string.
     *
     * @param encoding A PCM encoding constant from [C].
     * @return A display string such as `"PCM 16-bit"` or `"Float32"`.
     */
    private fun pcmEncodingToFormatString(encoding: Int): String = when (encoding) {
        C.ENCODING_PCM_8BIT -> "PCM 8-bit"
        C.ENCODING_PCM_16BIT -> "PCM 16-bit"
        C.ENCODING_PCM_24BIT -> "PCM 24-bit"
        C.ENCODING_PCM_32BIT -> "PCM 32-bit"
        C.ENCODING_PCM_FLOAT -> "Float32"
        else -> "Unknown"
    }

    /**
     * Estimates the AudioTrack double-buffer size and total audio chain latency for the
     * given [dspInputFormat].
     *
     * Uses [AudioTrack.getMinBufferSize] to derive the minimum frame count at the
     * DSP sample rate, then estimates end-to-end latency as twice the buffer duration
     * plus a fixed 15 ms hardware/driver overhead.
     *
     * @param dspInputFormat The [AudioProcessor.AudioFormat] currently active in [NativeDspAudioProcessor].
     * @return A pair of (human-readable buffer string, estimated latency in ms).
     */
    private fun computeBufferInfo(dspInputFormat: AudioProcessor.AudioFormat): Pair<String, Int> {
        val sr = dspInputFormat.sampleRate.takeIf { it > 0 } ?: 44100
        val ch = dspInputFormat.channelCount.takeIf { it > 0 } ?: 2

        val channelConfig = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val afEncoding = when (dspInputFormat.encoding) {
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> AudioFormat.ENCODING_PCM_FLOAT
            else -> AudioFormat.ENCODING_PCM_16BIT
        }

        val minBufBytes = AudioTrack.getMinBufferSize(sr, channelConfig, afEncoding).coerceAtLeast(1)
        val bytesPerFrame = when (dspInputFormat.encoding) {
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4 * ch
            C.ENCODING_PCM_24BIT -> 3 * ch
            else -> 2 * ch
        }

        val framesInBuffer = minBufBytes / bytesPerFrame.coerceAtLeast(1)
        val bufferMs = framesInBuffer * 1000 / sr
        // Double-buffer (2×) is ExoPlayer's DefaultAudioSink default; add 15 ms for hardware latency.
        val latencyEstimate = bufferMs * 2 + 15

        return Pair("2x (${bufferMs}ms, $framesInBuffer frames)", latencyEstimate)
    }

    /**
     * Selects the highest-priority active audio output device from the system device list.
     *
     * Priority order: USB headset / USB device → Bluetooth A2DP → Bluetooth SCO →
     * wired headset → wired headphones → built-in earpiece → built-in speaker → other.
     *
     * @return The best-matching [AudioDeviceInfo], or `null` if no output devices are found.
     */
    private fun detectActiveOutputDevice(): AudioDeviceInfo? {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.maxByOrNull { outputDevicePriority(it.type) }
    }

    /**
     * Returns a numeric priority for the given [AudioDeviceInfo] type so the most
     * desirable (highest fidelity) output device wins in [detectActiveOutputDevice].
     *
     * @param type An [AudioDeviceInfo.TYPE_*] constant.
     * @return Priority integer; higher means more preferred.
     */
    private fun outputDevicePriority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET -> 100
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 80
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 75
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 60
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 55
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> 20
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 10
        else -> 0
    }

    /**
     * Returns the maximum PCM bit depth supported by [device] by inspecting
     * [AudioDeviceInfo.getEncodings]. Falls back to [fallback] when the device
     * reports no encodings or when [device] is `null`.
     *
     * @param device   The output device to inspect, or `null`.
     * @param fallback Bit depth to return when no encoding info is available.
     * @return Maximum supported bit depth: 8, 16, 24, or 32.
     */
    private fun getDeviceBitDepth(device: AudioDeviceInfo?, fallback: Int): Int {
        device ?: return fallback
        val encodings = device.encodings
        if (encodings.isEmpty()) return fallback
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && encodings.contains(AudioFormat.ENCODING_PCM_32BIT) -> 32
            encodings.contains(AudioFormat.ENCODING_PCM_FLOAT) -> 32
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && encodings.contains(AudioFormat.ENCODING_PCM_24BIT_PACKED) -> 24
            encodings.contains(AudioFormat.ENCODING_PCM_16BIT) -> 16
            else -> fallback
        }
    }

    /**
     * Returns the best matching sample rate supported by [device] for the given [sourceSampleRate].
     *
     * Prefers the highest rate that does not exceed [sourceSampleRate] so the hardware
     * does not up-sample unnecessarily. If all device rates are above the source rate the
     * minimum device rate is returned. Returns [sourceSampleRate] when [device] is `null`
     * or its sample-rate list is empty.
     *
     * @param device           The output device to inspect, or `null`.
     * @param sourceSampleRate The source track's sample rate in Hz.
     * @return The best-matching hardware sample rate in Hz.
     */
    private fun getDeviceSampleRate(device: AudioDeviceInfo?, sourceSampleRate: Int): Int {
        device ?: return sourceSampleRate
        val rates = device.sampleRates
        if (rates.isEmpty()) return sourceSampleRate
        return rates.filter { it <= sourceSampleRate }.maxOrNull()
            ?: rates.minOrNull()
            ?: sourceSampleRate
    }

    private fun startPeriodicStateSaving() {
        if (periodicStateSaveJob?.isActive == true) return

        periodicStateSaveJob = serviceScope.launch {
            while (isActive) {
                delay(10000.milliseconds) // Save every 10 seconds
                savePlaybackStateToDatabase()
            }
        }

        Log.d(TAG, "Started periodic state saving")
    }

    private fun stopPeriodicStateSaving() {
        periodicStateSaveJob?.cancel()
        periodicStateSaveJob = null
        Log.d(TAG, "Stopped periodic state saving")
    }

    // ------------------------------------------------------------------ Android Auto browse tree

    /**
     * Fork (Android Auto): converts the stored URI string to a playable [Uri].
     * SAF-scanned songs store a content:// URI; legacy entries store a plain path.
     * Mirrors the private helper in MediaPlaybackManager so both queue paths
     * produce identical playback URIs.
     */
    private fun String.toPlaybackUri(): Uri {
        return if (startsWith("content://")) toUri() else File(this).toUri()
    }

    /**
     * Fork (Android Auto): the full library, served from the in-memory cache that the
     * Room flow keeps fresh, with a one-shot database fallback for cold starts where
     * the first flow emission has not arrived yet (Auto binds the service directly,
     * long before any app UI runs).
     */
    private suspend fun allLibrarySongs(): List<Audio> {
        return cachedSongList.ifEmpty {
            runCatching { audioRepository.getAllAudioList() }.getOrElse { emptyList() }
        }
    }

    /** Fork (Android Auto): builds a browsable (non-playable) folder node. */
    private fun browsableFolder(
            id: String,
            title: String,
            subtitle: String? = null,
            mediaType: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
            artworkAudioId: Long? = null
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(mediaType)
        if (artworkAudioId != null) {
            metadata.setArtworkUri(FelicityArtworkBitmapLoader.artworkUriFor(artworkAudioId))
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metadata.build())
            .build()
    }

    /**
     * Fork (Android Auto): a playable song row shown inside a browse folder. The media
     * id embeds the parent folder ([SONG_PREFIX]parentId|audioId) so that when the item
     * is tapped in the car, [LibraryCallback.onSetMediaItems] can rebuild the whole
     * containing folder as the queue and start at this song.
     */
    private fun Audio.toBrowsableSongItem(parentId: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId("$SONG_PREFIX$parentId|$id")
            .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(getProperTitle())
                        .setArtist(getProperArtists())
                        .setAlbumTitle(getProperAlbum())
                        .setDurationMs(duration.takeIf { it > 0 })
                        .setArtworkUri(FelicityArtworkBitmapLoader.artworkUriFor(id))
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build()
            )
            .build()
    }

    /**
     * Fork (Android Auto): a fully resolved, immediately playable [MediaItem] with the
     * same plain-numeric media id the in-app queue uses. Keeping the id identical to
     * [MediaPlaybackManager]'s items means every existing media-id consumer (song
     * statistics, widget broadcasts, error notifier) keeps working no matter whether
     * the queue was built in the app or in the car.
     */
    private fun Audio.toPlayableMediaItem(): MediaItem {
        return MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(uri.toPlaybackUri())
            .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(getProperTitle())
                        .setArtist(getProperArtists())
                        .setAlbumTitle(getProperAlbum())
                        .setDurationMs(duration.takeIf { it > 0 })
                        .setArtworkUri(FelicityArtworkBitmapLoader.artworkUriFor(id))
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build()
            )
            .build()
    }

    /** Fork (Android Auto): true when [parentId] identifies a folder whose children are songs. */
    private fun isSongParent(parentId: String): Boolean {
        return parentId == NODE_RECENT || parentId == NODE_FAVORITES ||
                parentId.startsWith(ALBUM_PREFIX) || parentId.startsWith(ARTIST_PREFIX) ||
                parentId.startsWith(PLAYLIST_PREFIX) || parentId.startsWith(SEARCH_PREFIX)
    }

    /**
     * Fork (Android Auto): resolves the ordered song list of a browse folder. Used both
     * for listing children ([LibraryCallback.onGetChildren]) and for building the play
     * queue when a song inside the folder is tapped ([LibraryCallback.onSetMediaItems]),
     * so what the user sees is exactly what gets queued.
     */
    private suspend fun songsForBrowseParent(parentId: String): List<Audio> {
        return runCatching {
            when {
                parentId == NODE_RECENT -> {
                    val recent = runCatching { audioRepository.getRecentAudio().first() }
                        .getOrElse { emptyList() }
                    recent.ifEmpty {
                        // Nothing added in the last 30 days — fall back to the newest songs
                        // so the folder is never uselessly empty in the car.
                        allLibrarySongs().sortedByDescending { it.dateAdded }
                    }.take(RECENT_LIMIT)
                }
                parentId == NODE_FAVORITES -> {
                    runCatching { audioRepository.getFavoriteAudio().first() }
                        .getOrElse { allLibrarySongs().filter { it.isFavorite } }
                }
                parentId.startsWith(ALBUM_PREFIX) -> {
                    val albumName = Uri.decode(parentId.removePrefix(ALBUM_PREFIX))
                    allLibrarySongs().filter { it.album == albumName }
                        .sortedWith(compareBy({ it.track }, { it.getProperTitle().lowercase() }))
                }
                parentId.startsWith(ARTIST_PREFIX) -> {
                    val artistName = Uri.decode(parentId.removePrefix(ARTIST_PREFIX))
                    allLibrarySongs()
                        .filter { AudioRepository.artistFieldMatchesName(it.artist, artistName) }
                        .sortedWith(compareBy({ it.album?.lowercase() ?: "" }, { it.track }))
                }
                parentId.startsWith(PLAYLIST_PREFIX) -> {
                    val playlistId = parentId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
                    if (playlistId == null) {
                        emptyList()
                    } else {
                        playlistRepository.getSongsInPlaylistOrdered(playlistId).first()
                    }
                }
                parentId.startsWith(SEARCH_PREFIX) -> {
                    searchLibrary(Uri.decode(parentId.removePrefix(SEARCH_PREFIX)))
                }
                else -> emptyList()
            }
        }.getOrElse {
            Log.e(TAG, "songsForBrowseParent failed for '$parentId'", it)
            emptyList()
        }
    }

    /** Fork (Android Auto): case-insensitive library search over title, artist, and album. */
    private suspend fun searchLibrary(query: String): List<Audio> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return allLibrarySongs().filter { audio ->
            audio.title?.contains(trimmed, ignoreCase = true) == true ||
                    audio.artist?.contains(trimmed, ignoreCase = true) == true ||
                    audio.album?.contains(trimmed, ignoreCase = true) == true
        }.take(SEARCH_LIMIT)
    }

    /** Fork (Android Auto): applies the browser's page/pageSize window to a full child list. */
    private fun paginate(items: List<MediaItem>, page: Int, pageSize: Int): ImmutableList<MediaItem> {
        val safePage = page.coerceAtLeast(0)
        val startIndex = safePage.toLong() * pageSize.toLong()
        if (startIndex >= items.size || pageSize <= 0) {
            return if (safePage == 0) ImmutableList.copyOf(items) else ImmutableList.of()
        }
        val endIndex = minOf(startIndex + pageSize, items.size.toLong())
        return ImmutableList.copyOf(items.subList(startIndex.toInt(), endIndex.toInt()))
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        private val toggleRepeatCommand = SessionCommand(COMMAND_TOGGLE_REPEAT, Bundle.EMPTY)
        private val toggleFavoriteCommand = SessionCommand(COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY)

        /**
         * Advertise the custom repeat and favorite commands so the system notification controller can use them.
         */
        override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(toggleRepeatCommand)
                .add(toggleFavoriteCommand)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        /**
         * Handle the repeat toggle and favorite toggle commands sent from the notification buttons.
         */
        override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == COMMAND_TOGGLE_REPEAT) {
                val current = PlayerPreferences.getRepeatMode()
                val next = when (current) {
                    MediaConstants.REPEAT_OFF -> MediaConstants.REPEAT_QUEUE
                    MediaConstants.REPEAT_QUEUE -> MediaConstants.REPEAT_ONE
                    else -> MediaConstants.REPEAT_OFF
                }
                PlayerPreferences.setRepeatMode(next)
                applyRepeatMode(next)
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            if (customCommand.customAction == COMMAND_TOGGLE_FAVORITE) {
                val currentSong = MediaPlaybackManager.getCurrentSong()
                if (currentSong != null) {
                    val newFavoriteState = !currentSong.isFavorite
                    serviceScope.launch(Dispatchers.IO) {
                        audioRepository.setFavorite(currentSong.id, newFavoriteState)
                        // Update the in-memory Audio object so the button reflects the new state
                        // without waiting for the next database read to come through.
                        currentSong.isFavorite = newFavoriteState
                        val repeatMode = PlayerPreferences.getRepeatMode()
                        serviceScope.launch(Dispatchers.Main) {
                            // Notify the active player fragment so its favorite button updates too.
                            MediaPlaybackManager.notifyCurrentSongUpdated()
                            mediaSession?.setCustomLayout(
                                    listOf(
                                            buildRepeatCommandButton(repeatMode),
                                            buildFavoriteCommandButton(newFavoriteState)
                                    )
                            )
                            // Fork (automation): favorite flips re-emit TRACK_CHANGED so the
                            // 自由作業盤's cached state self-corrects (hand-off.md B.1). This
                            // path also covers the AutomationActivity TOGGLE_FAVORITE op,
                            // which is routed through this same session command.
                            broadcastAutomationTrackChanged()
                        }
                    }
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> = serviceScope.future {
            Log.d(TAG, "onGetLibraryRoot called by: ${browser.packageName}")

            val rootItem = MediaItem.Builder()
                .setMediaId(ROOT_ID)
                .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setTitle("白い熊 音楽")
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .build()
                )
                .build()

            LibraryResult.ofItem(rootItem, params)
        }

        /**
         * Fork (Android Auto): serves the browse tree. The root exposes five top-level
         * folders (Recently added, Favorites, Albums, Artists, Playlists); the Albums,
         * Artists, and Playlists folders contain one browsable node per collection; every
         * other node resolves to playable song rows via [songsForBrowseParent].
         */
        override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = serviceScope.future {
            Log.d(TAG, "onGetChildren called for parentId: $parentId, page: $page, pageSize: $pageSize")

            when {
                parentId == ROOT_ID -> {
                    val folders = listOf(
                            browsableFolder(NODE_RECENT, "Recently added"),
                            browsableFolder(NODE_FAVORITES, "Favorites"),
                            browsableFolder(NODE_ALBUMS, "Albums", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                            browsableFolder(NODE_ARTISTS, "Artists", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS),
                            browsableFolder(NODE_PLAYLISTS, "Playlists", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS)
                    )
                    LibraryResult.ofItemList(paginate(folders, page, pageSize), params)
                }
                parentId == NODE_ALBUMS -> {
                    val albums = allLibrarySongs()
                        .filter { !it.album.isNullOrEmpty() }
                        .groupBy { it.album!! }
                        .toList()
                        .sortedBy { (name, _) -> name.lowercase() }
                        .map { (name, songs) ->
                            browsableFolder(
                                    id = ALBUM_PREFIX + Uri.encode(name),
                                    title = name,
                                    subtitle = songs.firstOrNull()?.getProperArtists(),
                                    mediaType = MediaMetadata.MEDIA_TYPE_ALBUM,
                                    artworkAudioId = songs.firstOrNull()?.id
                            )
                        }
                    LibraryResult.ofItemList(paginate(albums, page, pageSize), params)
                }
                parentId == NODE_ARTISTS -> {
                    val artists = runCatching { audioRepository.getAllArtistsWithAggregation().first() }
                        .getOrElse { emptyList() }
                        .mapNotNull { artist ->
                            val name = artist.name ?: return@mapNotNull null
                            browsableFolder(
                                    id = ARTIST_PREFIX + Uri.encode(name),
                                    title = name,
                                    subtitle = "${artist.trackCount} songs",
                                    mediaType = MediaMetadata.MEDIA_TYPE_ARTIST
                            )
                        }
                    LibraryResult.ofItemList(paginate(artists, page, pageSize), params)
                }
                parentId == NODE_PLAYLISTS -> {
                    val playlists = runCatching { playlistRepository.getAllPlaylistsWithSongs().first() }
                        .getOrElse { emptyList() }
                        .sortedWith(compareByDescending<app.simple.felicity.repository.models.PlaylistWithSongs> { it.playlist.isPinned }
                                        .thenBy { it.playlist.name.lowercase() })
                        .map { entry ->
                            browsableFolder(
                                    id = PLAYLIST_PREFIX + entry.playlist.id,
                                    title = entry.playlist.name,
                                    subtitle = "${entry.songs.size} songs",
                                    mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                    artworkAudioId = entry.songs.firstOrNull()?.id
                            )
                        }
                    LibraryResult.ofItemList(paginate(playlists, page, pageSize), params)
                }
                isSongParent(parentId) -> {
                    val songs = songsForBrowseParent(parentId).map { it.toBrowsableSongItem(parentId) }
                    LibraryResult.ofItemList(paginate(songs, page, pageSize), params)
                }
                else -> {
                    Log.w(TAG, "Unknown parent ID: $parentId")
                    LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                }
            }
        }

        /**
         * Fork (Android Auto): resolves a single browse node by id — some browsers
         * (including the Auto host) call getItem() for the node being displayed.
         */
        override fun onGetItem(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> = serviceScope.future {
            when {
                mediaId == ROOT_ID -> LibraryResult.ofItem(
                        browsableFolder(ROOT_ID, "白い熊 音楽"), null)
                mediaId == NODE_RECENT -> LibraryResult.ofItem(browsableFolder(NODE_RECENT, "Recently added"), null)
                mediaId == NODE_FAVORITES -> LibraryResult.ofItem(browsableFolder(NODE_FAVORITES, "Favorites"), null)
                mediaId == NODE_ALBUMS -> LibraryResult.ofItem(
                        browsableFolder(NODE_ALBUMS, "Albums", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS), null)
                mediaId == NODE_ARTISTS -> LibraryResult.ofItem(
                        browsableFolder(NODE_ARTISTS, "Artists", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS), null)
                mediaId == NODE_PLAYLISTS -> LibraryResult.ofItem(
                        browsableFolder(NODE_PLAYLISTS, "Playlists", mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS), null)
                mediaId.startsWith(ALBUM_PREFIX) -> {
                    val name = Uri.decode(mediaId.removePrefix(ALBUM_PREFIX))
                    LibraryResult.ofItem(browsableFolder(mediaId, name, mediaType = MediaMetadata.MEDIA_TYPE_ALBUM), null)
                }
                mediaId.startsWith(ARTIST_PREFIX) -> {
                    val name = Uri.decode(mediaId.removePrefix(ARTIST_PREFIX))
                    LibraryResult.ofItem(browsableFolder(mediaId, name, mediaType = MediaMetadata.MEDIA_TYPE_ARTIST), null)
                }
                mediaId.startsWith(PLAYLIST_PREFIX) -> {
                    val id = mediaId.removePrefix(PLAYLIST_PREFIX).toLongOrNull()
                    val name = id?.let { runCatching { playlistRepository.getPlaylistByIdFlow(it).first()?.name }.getOrNull() }
                    LibraryResult.ofItem(browsableFolder(mediaId, name ?: "Playlist", mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST), null)
                }
                mediaId.startsWith(SONG_PREFIX) -> {
                    val body = mediaId.removePrefix(SONG_PREFIX)
                    val separator = body.lastIndexOf('|')
                    val parentId = if (separator > 0) body.substring(0, separator) else ""
                    val audio = body.substringAfterLast('|').toLongOrNull()?.let { audioRepository.getAudioById(it) }
                    if (audio != null) {
                        LibraryResult.ofItem(audio.toBrowsableSongItem(parentId), null)
                    } else {
                        LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    }
                }
                else -> {
                    val audio = mediaId.toLongOrNull()?.let { audioRepository.getAudioById(it) }
                    if (audio != null) {
                        LibraryResult.ofItem(audio.toPlayableMediaItem(), null)
                    } else {
                        LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    }
                }
            }
        }

        /**
         * Fork (Android Auto): browse-tree search. Notifies the browser how many songs
         * match; the results themselves are delivered by [onGetSearchResult].
         */
        override fun onSearch(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                query: String,
                params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> = serviceScope.future {
            val count = searchLibrary(query).size
            session.notifySearchResultChanged(browser, query, count, params)
            LibraryResult.ofVoid(params)
        }

        /**
         * Fork (Android Auto): returns the actual search hits as playable song rows.
         * The parent id embeds the query so tapping a hit queues the full result list.
         */
        override fun onGetSearchResult(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                query: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = serviceScope.future {
            val parentId = SEARCH_PREFIX + Uri.encode(query.trim())
            val items = searchLibrary(query).map { it.toBrowsableSongItem(parentId) }
            LibraryResult.ofItemList(paginate(items, page, pageSize), params)
        }

        /**
         * Fork (Android Auto): a tap on a browse-tree song arrives here as a single item
         * whose media id carries the containing folder. The queue becomes that folder's
         * full ordered song list, starting at the tapped song — matching in-app behavior
         * where tapping a song in a list plays the list.
         */
        override fun onSetMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
                startIndex: Int,
                startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val single = mediaItems.singleOrNull()
            if (single != null && single.mediaId.startsWith(SONG_PREFIX) && single.localConfiguration == null) {
                return serviceScope.future {
                    val body = single.mediaId.removePrefix(SONG_PREFIX)
                    val separator = body.lastIndexOf('|')
                    val parentId = if (separator > 0) body.substring(0, separator) else ""
                    val audioId = body.substringAfterLast('|').toLongOrNull()

                    val queue = songsForBrowseParent(parentId)
                    if (queue.isNotEmpty()) {
                        val start = queue.indexOfFirst { it.id == audioId }.coerceAtLeast(0)
                        Log.d(TAG, "onSetMediaItems: queueing '$parentId' (${queue.size} songs) starting at $start")
                        MediaSession.MediaItemsWithStartPosition(queue.map { it.toPlayableMediaItem() }, start, C.TIME_UNSET)
                    } else {
                        val audio = audioId?.let { audioRepository.getAudioById(it) }
                            ?: throw IllegalStateException("Cannot resolve media id: ${single.mediaId}")
                        MediaSession.MediaItemsWithStartPosition(listOf(audio.toPlayableMediaItem()), 0, C.TIME_UNSET)
                    }
                }
            }
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }

        /**
         * Fork (Android Auto): cold-start playback resumption. When the car (or the
         * system's media resumption UI) hits play before any queue exists, restore the
         * queue that was persisted by the periodic state saver — same data the app UI
         * restores from — and fall back to the recently-added songs when the database
         * holds no saved queue yet.
         */
        override fun onPlaybackResumption(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                isForPlayback: Boolean
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future {
            val db = AudioDatabase.getInstance(applicationContext)
            val state = PlaybackStateManager.fetchPlaybackState(db)
            val savedQueue = PlaybackStateManager.getAudiosFromQueueIDs(db).orEmpty()

            if (savedQueue.isNotEmpty()) {
                val index = (state?.index ?: 0).coerceIn(0, savedQueue.size - 1)
                val seek = state?.position ?: 0L
                Log.d(TAG, "onPlaybackResumption: restored saved queue (${savedQueue.size} songs, index=$index)")
                MediaSession.MediaItemsWithStartPosition(savedQueue.map { it.toPlayableMediaItem() }, index, seek)
            } else {
                val fallback = songsForBrowseParent(NODE_RECENT)
                if (fallback.isEmpty()) {
                    throw IllegalStateException("No saved queue and empty library — nothing to resume")
                }
                Log.d(TAG, "onPlaybackResumption: no saved queue, using ${fallback.size} recent songs")
                MediaSession.MediaItemsWithStartPosition(fallback.map { it.toPlayableMediaItem() }, 0, C.TIME_UNSET)
            }
        }

        /**
         * Handle "Play [Song Name]" commands from Assistant (Search Intent)
         */
        override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> = serviceScope.future {
            Log.d(TAG, "onAddMediaItems called with ${mediaItems.size} items")

            val updatedMediaItems = mediaItems.mapNotNull { mediaItem ->
                // If the mediaItem comes from a search query, it often lacks a URI
                if (mediaItem.requestMetadata.searchQuery != null) {
                    val query = mediaItem.requestMetadata.searchQuery!!
                    Log.d(TAG, "Assistant requested search for: $query")

                    // Search for the song in the AudioRepository
                    // Try title search first, then artist search
                    val titleResults = audioRepository.searchByTitle(query)
                    val artistResults = audioRepository.searchByArtist(query)
                    val audio = titleResults.firstOrNull() ?: artistResults.firstOrNull()

                    if (audio != null) {
                        Log.d(TAG, "Found audio: ${audio.title} by ${audio.artist}")
                        // Return the fully populated MediaItem with URI
                        MediaItem.Builder()
                            .setMediaId(audio.id.toString())
                            .setUri(audio.uri)
                            .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(audio.getProperTitle())
                                        .setArtist(audio.getProperArtists())
                                        .setAlbumTitle(audio.getProperAlbum())
                                        .setIsPlayable(true)
                                        .build()
                            )
                            .build()
                    } else {
                        Log.w(TAG, "No audio found for query: $query")
                        null
                    }
                } else if (mediaItem.localConfiguration != null) {
                    // Already has a URI, return as-is
                    mediaItem
                } else {
                    // Try to resolve by media ID. Browse-tree song ids carry the containing
                    // folder ("song|<parent>|<audioId>") — strip that down to the numeric id.
                    val mediaId = if (mediaItem.mediaId.startsWith(SONG_PREFIX)) {
                        mediaItem.mediaId.substringAfterLast('|')
                    } else {
                        mediaItem.mediaId
                    }
                    if (mediaId.isNotEmpty()) {
                        val audioId = mediaId.toLongOrNull()
                        if (audioId != null) {
                            // Get audio by ID from database
                            val query = "SELECT * FROM audio WHERE id = ?"
                            val args = arrayOf<Any>(audioId)
                            val results = audioRepository.executeRawQuery(query, args)
                            val audio = results.firstOrNull()

                            if (audio != null) {
                                Log.d(TAG, "Resolved media ID $mediaId to audio: ${audio.title}")
                                MediaItem.Builder()
                                    .setMediaId(audio.id.toString())
                                    .setUri(audio.uri)
                                    .setMediaMetadata(
                                            MediaMetadata.Builder()
                                                .setTitle(audio.getProperTitle())
                                                .setArtist(audio.getProperArtists())
                                                .setAlbumTitle(audio.getProperAlbum())
                                                .setIsPlayable(true)
                                                .build()
                                    )
                                    .build()
                            } else {
                                Log.w(TAG, "No audio found for media ID: $mediaId")
                                null
                            }
                        } else {
                            Log.w(TAG, "Invalid media ID format: $mediaId")
                            null
                        }
                    } else {
                        Log.w(TAG, "MediaItem has no URI, search query, or valid media ID")
                        null
                    }
                }
            }.toMutableList()

            Log.d(TAG, "Resolved ${updatedMediaItems.size} media items")
            updatedMediaItems
        }
    }

    companion object {
        private const val TAG = "FelicityPlayerService"
        private const val GAP_DURATION_MS = 800L // Duration of silence gap when gapless playback is disabled

        /**
         * Fraction of a song's duration that must have elapsed before a transition is NOT
         * counted as a skip. Songs navigated away from before this threshold increment
         * the skip counter in the song statistics database.
         */
        private const val SKIP_THRESHOLD = 0.30

        /**
         * Cooldown window in milliseconds that coalesces rapid-fire [buildAndPushSnapshot]
         * calls during a song transition. Up to 6 callbacks (decoder init, format change,
         * item transition, state ready, playing changed, snapshot pulse) can fire within
         * ~200ms of each other. This debounce ensures only the last call in a burst
         * produces a snapshot, reducing duplicate pipeline updates from 6 to 1.
         */
        private const val SNAPSHOT_DEBOUNCE_MS = 200L

        /** Custom session command sent when the user taps the repeat button in the notification. */
        const val COMMAND_TOGGLE_REPEAT = "app.simple.felicity.TOGGLE_REPEAT"

        /** Custom session command sent when the user taps the favorite button in the notification. */
        const val COMMAND_TOGGLE_FAVORITE = "app.simple.felicity.TOGGLE_FAVORITE"

        // ---------------------------------------------------------------- Android Auto browse ids

        /** Fork (Android Auto): media id of the browse-tree root. */
        private const val ROOT_ID = "root"

        /** Fork (Android Auto): top-level folder — songs added in the last 30 days. */
        private const val NODE_RECENT = "recent"

        /** Fork (Android Auto): top-level folder — favorite songs. */
        private const val NODE_FAVORITES = "favorites"

        /** Fork (Android Auto): top-level folder — one browsable node per album. */
        private const val NODE_ALBUMS = "albums"

        /** Fork (Android Auto): top-level folder — one browsable node per artist. */
        private const val NODE_ARTISTS = "artists"

        /** Fork (Android Auto): top-level folder — one browsable node per playlist. */
        private const val NODE_PLAYLISTS = "playlists"

        /** Fork (Android Auto): album folder id prefix, followed by the Uri-encoded album name. */
        private const val ALBUM_PREFIX = "album/"

        /** Fork (Android Auto): artist folder id prefix, followed by the Uri-encoded artist name. */
        private const val ARTIST_PREFIX = "artist/"

        /** Fork (Android Auto): playlist folder id prefix, followed by the numeric playlist id. */
        private const val PLAYLIST_PREFIX = "playlist/"

        /** Fork (Android Auto): virtual parent id prefix for search results (encodes the query). */
        private const val SEARCH_PREFIX = "search/"

        /**
         * Fork (Android Auto): playable browse-row id prefix. Full format is
         * `song|<parentId>|<audioId>` so a tapped row can rebuild its folder as the queue.
         */
        private const val SONG_PREFIX = "song|"

        /** Fork (Android Auto): cap for the Recently-added folder. */
        private const val RECENT_LIMIT = 200

        /** Fork (Android Auto): cap for browse-tree search results. */
        private const val SEARCH_LIMIT = 100
    }
}