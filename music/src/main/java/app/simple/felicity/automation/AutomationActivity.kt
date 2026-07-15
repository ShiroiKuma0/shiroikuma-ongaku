package app.simple.felicity.automation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import app.simple.felicity.engine.managers.MediaPlaybackManager
import app.simple.felicity.engine.managers.PlaybackStateManager
import app.simple.felicity.engine.services.FelicityPlayerService
import app.simple.felicity.preferences.AutomationPreferences
import app.simple.felicity.repository.database.instances.AudioDatabase
import app.simple.felicity.repository.repositories.PlaylistRepository
import app.simple.felicity.utils.SongDeleter
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Fork (automation): exported, headless entry point for external automation — the
 * 自由作業盤 (OpenTasker fork) buttons and named play-playlist tasks (hand-off.md B.2).
 * Same shape as the jami fork's AutomationActivity.
 *
 * Accepts an intent with action [ACTION_AUTOMATION] and dispatches exactly one operation
 * in [onCreate], then finishes immediately (translucent theme, no UI). An Activity — not
 * a Service or receiver — is the entry point so a cold-process invocation from another
 * app is not blocked by Android's background-start limits.
 *
 * Every request is gated on [AutomationPreferences]: automation must be enabled in
 * Settings → 白い熊 音楽 UI → Automation and the request must carry a `token` extra
 * matching the stored secret. Unauthorized requests are dropped (logged + brief toast)
 * and never touch playback.
 *
 * Cold-start safety: every playback op connects a [MediaController] to
 * [FelicityPlayerService] — building the controller transparently starts the service —
 * and, when the session queue is empty, restores the last saved queue from the database
 * exactly like the widget's `WidgetActionReceiver.restoreQueueAndPlay`.
 *
 * Contract (frozen, hand-off.md B.2/E — only the ACTION string carries the
 * `shiroikuma.ongaku.` prefix):
 * - action [ACTION_AUTOMATION], extras [KEY_OP], [KEY_TOKEN]
 * - ops: [OP_TOGGLE_FAVORITE], [OP_DELETE_CURRENT], [OP_PLAY_PLAYLIST] (+ [KEY_PLAYLIST],
 *   optional [KEY_SHUFFLE]), [OP_PLAY_PAUSE], [OP_NEXT], [OP_PREVIOUS]
 */
@AndroidEntryPoint
@OptIn(UnstableApi::class)
class AutomationActivity : ComponentActivity() {

    @Inject
    lateinit var playlistRepository: PlaylistRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            handle(intent)
        } catch (e: Exception) {
            Log.w(TAG, "automation intent failed", e)
        }
        finish()
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        val op = intent.getStringExtra(KEY_OP)
        if (op.isNullOrEmpty()) {
            reject("missing op")
            return
        }

        // --- authorization gate ---
        if (!AutomationPreferences.isEnabled()) {
            reject("disabled (enable it in Settings → 白い熊 音楽 UI → Automation)")
            return
        }
        if (!AutomationPreferences.isAuthorized(intent.getStringExtra(KEY_TOKEN))) {
            reject("rejected: bad or missing token")
            return
        }

        val appContext = applicationContext

        when (op) {
            OP_TOGGLE_FAVORITE -> withController(appContext) { controller ->
                // Reuse the notification button's body verbatim: the service handles
                // COMMAND_TOGGLE_FAVORITE (AudioRepository.setFavorite + notification
                // button refresh) and emits TRACK_CHANGED afterwards (hand-off.md B.2).
                controller.sendCustomCommand(
                        SessionCommand(FelicityPlayerService.COMMAND_TOGGLE_FAVORITE, Bundle.EMPTY),
                        Bundle.EMPTY
                )
            }

            OP_DELETE_CURRENT -> withController(appContext) { controller ->
                val song = MediaPlaybackManager.getCurrentSong()
                if (song == null) {
                    Log.w(TAG, "DELETE_CURRENT: no current song")
                } else {
                    // Make sure the manager drives a live controller so the queue
                    // removal inside the helper can advance playback.
                    MediaPlaybackManager.setMediaController(controller)
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        SongDeleter.deleteSong(appContext, song, deleteLyrics = true)
                    }
                }
            }

            OP_PLAY_PLAYLIST -> {
                val name = intent.getStringExtra(KEY_PLAYLIST)?.trim()
                val shuffle = intent.getBooleanExtra(KEY_SHUFFLE, false)
                val track = intent.getStringExtra(KEY_TRACK)?.trim()
                if (name.isNullOrEmpty()) {
                    reject("PLAY_PLAYLIST: missing playlist name")
                    return
                }
                withController(appContext) { controller ->
                    playPlaylist(appContext, controller, name, shuffle, track)
                }
            }

            OP_PLAY_PAUSE, OP_NEXT, OP_PREVIOUS -> withController(appContext) { controller ->
                transport(appContext, controller, op)
            }

            else -> reject("unknown op '$op'")
        }
    }

    /**
     * Resolves the playlist by name (case-insensitive), loads its manually-ordered
     * songs and hands them to [MediaPlaybackManager.setSongs] with autoPlay. When the
     * `shuffle` extra is true the queue is shuffled up front (position 0 is then a
     * random song); the user's global shuffle preference is left untouched.
     */
    private fun playPlaylist(context: Context, controller: MediaController, name: String, shuffle: Boolean, track: String? = null) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val playlist = playlistRepository.getPlaylistByName(name)
                val songs = playlist?.let { playlistRepository.getSongsInPlaylistOrdered(it.id).first() }
                withContext(Dispatchers.Main) {
                    if (songs.isNullOrEmpty()) {
                        Log.w(TAG, "PLAY_PLAYLIST: playlist '$name' not found or empty")
                        Toast.makeText(context, "白い熊 音楽 automation: playlist \"$name\" not found or empty", Toast.LENGTH_SHORT).show()
                    } else {
                        MediaPlaybackManager.setMediaController(controller)

                        // Hand-off addendum: optional `track` extra — start the playlist at the
                        // song whose title matches case-insensitively. Track wins over shuffle;
                        // a miss warns and starts at 0 (never fail the op).
                        val startIndex: Int
                        val queue: List<app.simple.felicity.repository.models.Audio>
                        if (!track.isNullOrEmpty()) {
                            queue = songs
                            val found = songs.indexOfFirst { it.title?.trim().equals(track, ignoreCase = true) }
                            startIndex = if (found >= 0) found else 0
                            if (found < 0) {
                                Log.w(TAG, "PLAY_PLAYLIST: track '$track' not in '$name', starting at 0")
                                Toast.makeText(context, "白い熊 音楽 automation: \"$track\" not in \"$name\" — starting at the top", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            queue = if (shuffle) songs.shuffled() else songs
                            startIndex = 0
                        }

                        MediaPlaybackManager.setSongs(queue, startIndex, autoPlay = true)
                        Log.i(TAG, "PLAY_PLAYLIST: playing '${playlist.name}' (${queue.size} songs, shuffle=$shuffle, track=$track, start=$startIndex)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "PLAY_PLAYLIST failed: ${e.message}", e)
            }
        }
    }

    /**
     * PLAY_PAUSE / NEXT / PREVIOUS. With a populated session the ops go through
     * [MediaPlaybackManager] (so always-skip and the restart-on-previous semantics are
     * honored). With an empty session (cold start) the last saved queue is restored
     * from the database and playback starts — the widget's restoreQueueAndPlay pattern.
     */
    private fun transport(context: Context, controller: MediaController, op: String) {
        if (controller.mediaItemCount == 0) {
            // Cold start: the service was just spawned by the controller connection
            // and knows nothing yet — restore the saved queue and play.
            restoreQueueAndPlay(context, controller)
            return
        }

        if (MediaPlaybackManager.getSongs().isNotEmpty()) {
            // Hand the manager a live controller (heals any controller a previous
            // headless caller may have released) and run the op through it.
            MediaPlaybackManager.setMediaController(controller)
            when (op) {
                OP_PLAY_PAUSE -> MediaPlaybackManager.flipState()
                OP_NEXT -> MediaPlaybackManager.next()
                OP_PREVIOUS -> MediaPlaybackManager.previous()
            }
        } else {
            // Session has a queue but the in-process manager state is gone — drive
            // the controller directly (same fallback the widget uses).
            when (op) {
                OP_PLAY_PAUSE -> if (controller.isPlaying) controller.pause() else controller.play()
                OP_NEXT -> controller.seekToNextMediaItem()
                OP_PREVIOUS -> controller.seekToPreviousMediaItem()
            }
        }
    }

    /**
     * Loads the last saved queue from the database, hands it to [MediaPlaybackManager]
     * and starts playback — a verbatim mirror of `WidgetActionReceiver.restoreQueueAndPlay`.
     */
    private fun restoreQueueAndPlay(context: Context, controller: MediaController) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val db = AudioDatabase.getInstance(context)
                val playbackState = PlaybackStateManager.fetchPlaybackState(db)
                val savedQueue = PlaybackStateManager.getAudiosFromQueueIDs(db)

                if (!savedQueue.isNullOrEmpty() && playbackState != null) {
                    val restoredIndex = when {
                        playbackState.currentHash == 0L -> {
                            playbackState.index.coerceIn(0, savedQueue.size - 1)
                        }
                        else -> {
                            val byHash = savedQueue.indexOfFirst { it.hash == playbackState.currentHash }
                            if (byHash >= 0) byHash else 0
                        }
                    }

                    withContext(Dispatchers.Main) {
                        MediaPlaybackManager.setMediaController(controller)
                        MediaPlaybackManager.setActiveQueueId(playbackState.activeQueueId)
                        MediaPlaybackManager.setSongs(
                                audios = savedQueue,
                                position = restoredIndex,
                                startPositionMs = playbackState.position,
                                autoPlay = true,
                                isRestore = true
                        )
                    }
                } else {
                    Log.w(TAG, "No saved queue found — nothing to play.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring queue from database: ${e.message}", e)
            }
        }
    }

    /**
     * Connects a [MediaController] to [FelicityPlayerService] — starting the service if
     * it is not running — and runs [block] with it on the main thread.
     *
     * Unlike the widget receiver, the controller is NOT released after a delay: it is
     * parked in [activeController] (releasing the previous one) so that
     * [MediaPlaybackManager], which may now hold this controller, never ends up driving
     * a released instance between headless invocations.
     */
    private fun withController(context: Context, block: (MediaController) -> Unit) {
        val sessionToken = SessionToken(context, ComponentName(context, FelicityPlayerService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

        Futures.addCallback(controllerFuture, object : FutureCallback<MediaController> {
            override fun onSuccess(controller: MediaController) {
                val previous = activeController
                activeController = controller
                if (previous !== controller) {
                    previous?.release()
                }
                try {
                    block(controller)
                } catch (e: Exception) {
                    Log.w(TAG, "automation op failed: ${e.message}", e)
                }
            }

            override fun onFailure(t: Throwable) {
                Log.e(TAG, "Failed to connect to FelicityPlayerService: ${t.message}")
            }
        }, MoreExecutors.directExecutor())
    }

    private fun reject(reason: String) {
        Log.w(TAG, "automation $reason")
        Toast.makeText(applicationContext, "白い熊 音楽 automation $reason", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "AutomationActivity"

        /**
         * Public contract (hand-off.md B.2/E) — only this ACTION string carries the
         * `shiroikuma.ongaku.` prefix so it can never collide with upstream Felicity
         * installed side-by-side. The code namespace stays `app.simple.felicity.*`.
         */
        const val ACTION_AUTOMATION = "shiroikuma.ongaku.AUTOMATION"

        const val KEY_OP = "op"
        const val KEY_TOKEN = "token"
        const val KEY_PLAYLIST = "playlist"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_TRACK = "track"

        const val OP_TOGGLE_FAVORITE = "TOGGLE_FAVORITE"
        const val OP_DELETE_CURRENT = "DELETE_CURRENT"
        const val OP_PLAY_PLAYLIST = "PLAY_PLAYLIST"
        const val OP_PLAY_PAUSE = "PLAY_PAUSE"
        const val OP_NEXT = "NEXT"
        const val OP_PREVIOUS = "PREVIOUS"

        /**
         * The single live automation controller. Bounded to one instance: each new
         * invocation replaces (and releases) the previous one, so repeated headless
         * ops neither leak connections nor leave [MediaPlaybackManager] holding a
         * released controller.
         */
        private var activeController: MediaController? = null
    }
}
