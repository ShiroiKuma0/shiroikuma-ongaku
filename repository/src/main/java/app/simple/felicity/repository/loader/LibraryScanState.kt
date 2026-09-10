package app.simple.felicity.repository.loader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The library scan, as something the UI can watch.
 *
 * Fork (白い熊, 2026-09-10): the scan already reports itself — to the notification shade. Inside
 * the app there was nothing: a freshly restored copy opened on an empty home screen and sat there
 * for the minutes a full scan takes, indistinguishable from a broken one. This mirrors exactly
 * what [app.simple.felicity.repository.notifications.LoaderNotification] shows, so the activity
 * can say "scanning — 楽曲 1234/8942" in the one place 白い熊 is actually looking.
 */
object LibraryScanState {

    /** [total] is 0 until the files have been counted; [scanned] counts files evaluated. */
    data class Scan(val running: Boolean = false, val scanned: Int = 0, val total: Int = 0)

    private val _state = MutableStateFlow(Scan())
    val state: StateFlow<Scan> = _state.asStateFlow()

    fun begin() {
        _state.value = Scan(running = true)
    }

    fun total(total: Int) {
        _state.update { if (it.running) it.copy(total = total) else it }
    }

    fun progress(scanned: Int) {
        _state.update { if (it.running) it.copy(scanned = scanned) else it }
    }

    fun end() {
        _state.value = Scan()
    }
}
