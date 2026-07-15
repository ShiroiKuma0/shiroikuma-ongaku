package app.simple.felicity.ui.pages

/**
 * Fragment that displays the playlist page, showing all constituent songs alongside
 * aggregated albums, artists, and genres derived from those songs.
 *
 * Observes [PlaylistViewerViewModel] and updates the UI reactively whenever the
 * playlist's song membership changes. All common interaction callbacks are handled
 * by [app.simple.felicity.extensions.fragments.BasePageFragment]; only the playlist-specific overflow menu is implemented here.
 *
 * @author Hamza417
 */

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import app.simple.felicity.R
import app.simple.felicity.adapters.ui.page.PageAdapter
import app.simple.felicity.databinding.FragmentPageArtistBinding
import app.simple.felicity.decorations.views.PopupMenuItem
import app.simple.felicity.decorations.views.SharedScrollViewPopup
import app.simple.felicity.dialogs.playlists.AddMultipleToPlaylistDialog.Companion.showAddMultipleToPlaylistDialog
import app.simple.felicity.dialogs.playlists.PlaylistSongsSort.Companion.showPlaylistSongsSort
import app.simple.felicity.engine.managers.MediaPlaybackManager
import app.simple.felicity.extensions.fragments.BasePageFragment
import app.simple.felicity.repository.constants.BundleConstants
import app.simple.felicity.repository.models.Playlist
import app.simple.felicity.utils.ParcelUtils.parcelable
import app.simple.felicity.viewmodels.viewer.PlaylistViewerViewModel
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PlaylistPage : BasePageFragment() {

    private lateinit var binding: FragmentPageArtistBinding

    private val playlist: Playlist by lazy {
        requireArguments().parcelable(BundleConstants.PLAYLIST)
            ?: throw IllegalArgumentException("Playlist is required")
    }

    private val playlistViewerViewModel: PlaylistViewerViewModel by viewModels(
            ownerProducer = { this },
            extrasProducer = {
                defaultViewModelCreationExtras.withCreationCallback<PlaylistViewerViewModel.Factory> {
                    it.create(playlist = playlist)
                }
            }
    )

    override val pageRecyclerView: RecyclerView
        get() = binding.recyclerView

    override val pageType: PageAdapter.PageType by lazy { PageAdapter.PageType.PlaylistPage(playlist) }

    private var itemTouchHelper: ItemTouchHelper? = null

    /** True once at least one row move happened during the current drag gesture. */
    private var dragMoved = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentPageArtistBinding.inflate(inflater, container, false)
        return binding.root
    }

    /**
     * Sets up the RecyclerView and begins collecting [PageData] from [PlaylistViewerViewModel].
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collectPageData { playlistViewerViewModel.data }

        viewLifecycleOwner.lifecycleScope.launch {
            playlistViewerViewModel.currentPlaylist.collect { playlist ->
                pageAdapter?.updatePlaylist(playlist)
            }
        }
    }

    /**
     * Attaches an [ItemTouchHelper] to the page RecyclerView so songs can be manually
     * reordered by dragging their row handle (the same right-edge handle the playing-queue
     * screen uses; long-press keeps opening the song menu as before). Only song rows are
     * draggable — the header and the albums/artists/genres sections are neither drag
     * sources nor drop targets. On drop, the full new order is written to the database in
     * one transaction and the playlist switches to manual ("As Added") sort mode.
     */
    override fun onPageAdapterCreated() {
        val helper = ItemTouchHelper(object : ItemTouchHelper.Callback() {

            override fun isLongPressDragEnabled(): Boolean = false

            override fun isItemViewSwipeEnabled(): Boolean = false

            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                return if (pageAdapter?.isSongItem(viewHolder.bindingAdapterPosition) == true) {
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                } else {
                    0
                }
            }

            override fun canDropOver(recyclerView: RecyclerView, current: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                return pageAdapter?.isSongItem(target.bindingAdapterPosition) == true
            }

            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val moved = pageAdapter?.moveSongItem(
                        viewHolder.bindingAdapterPosition, target.bindingAdapterPosition) == true
                if (moved) dragMoved = true
                return moved
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    pageAdapter?.onDragStarted()
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                val adapter = pageAdapter ?: return
                val moved = dragMoved
                dragMoved = false
                if (moved) {
                    playlistViewerViewModel.persistManualOrder(adapter.getCurrentSongOrder())
                }
                // After a persisted reorder any mid-drag emission is stale — the write above
                // triggers a fresh one. Without moves, apply whatever arrived while dragging.
                adapter.onDragEnded(discardPending = moved)
            }
        })

        helper.attachToRecyclerView(pageRecyclerView)
        itemTouchHelper = helper
        pageAdapter?.setSongDragListener { holder -> itemTouchHelper?.startDrag(holder) }
    }

    override fun onDestroyView() {
        itemTouchHelper?.attachToRecyclerView(null)
        itemTouchHelper = null
        super.onDestroyView()
    }

    /**
     * Delegates sort re-ordering to the [PlaylistViewerViewModel] without a database round-trip.
     */
    override fun resortPageData() {
        playlistViewerViewModel.resort()
    }

    /**
     * Opens [PlaylistSongsSort] instead of the generic page sort dialog so that the
     * sort preference is saved per-playlist in the database rather than globally.
     */
    override fun onSortDialogRequested(view: View) {
        childFragmentManager.showPlaylistSongsSort(playlistViewerViewModel.currentPlaylist.value)
    }

    /**
     * Displays the playlist overflow menu with play, shuffle, and send actions.
     *
     * @param view The anchor [View] for the popup.
     */
    override fun onMenuClicked(view: View) {
        viewLifecycleOwner.lifecycleScope.launch {
            val currentData = playlistViewerViewModel.data.value ?: return@launch

            SharedScrollViewPopup(
                    container = requireContainerView(),
                    anchorView = view,
                    menuItems = listOf(
                            PopupMenuItem(title = R.string.play, icon = R.drawable.ic_play),
                            PopupMenuItem(title = R.string.shuffle, icon = R.drawable.ic_shuffle),
                            PopupMenuItem(title = R.string.add_to_queue, icon = R.drawable.ic_add_to_queue),
                            PopupMenuItem(title = R.string.add_to_playlist, icon = R.drawable.ic_add_to_playlist),
                            PopupMenuItem(title = R.string.send, icon = R.drawable.ic_send)
                    ),
                    onMenuItemClick = {
                        when (it) {
                            R.string.play -> setMediaItems(currentData.songs.toMutableList(), 0)
                            R.string.shuffle -> shuffleMediaItems(currentData.songs)
                            R.string.add_to_queue -> currentData.songs.forEach { song -> MediaPlaybackManager.addToQueue(song) }
                            R.string.add_to_playlist -> parentFragmentManager.showAddMultipleToPlaylistDialog(currentData.songs)
                            R.string.send -> shareAudioList(currentData.songs)
                        }
                    },
                    onDismiss = {}
            ).show()
        }
    }

    companion object {
        const val TAG = "PlaylistPage"

        /**
         * Creates a new instance of [PlaylistPage] with the given [playlist] bundled as arguments.
         *
         * @param playlist The [Playlist] whose data will be displayed in this fragment.
         * @return A new [PlaylistPage] instance ready to be committed via a fragment transaction.
         */
        fun newInstance(playlist: Playlist): PlaylistPage {
            val args = Bundle()
            args.putParcelable(BundleConstants.PLAYLIST, playlist)
            val fragment = PlaylistPage()
            fragment.arguments = args
            return fragment
        }
    }
}
