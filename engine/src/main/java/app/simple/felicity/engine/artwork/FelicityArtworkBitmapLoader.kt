package app.simple.felicity.engine.artwork

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import app.simple.felicity.repository.covers.AudioCover
import app.simple.felicity.repository.repositories.AudioRepository
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import kotlin.math.max

/**
 * Fork (Android Auto): a [BitmapLoader] for the media session that understands the
 * app's own artwork scheme in addition to everything the default loader handles.
 *
 * Song items in the Android Auto browse tree carry an artwork URI of the form
 * `felicity-art://audio/<audioId>`. When the session (or the legacy browser stub
 * serving Android Auto) asks for that URI, this loader resolves the [app.simple.felicity.repository.models.Audio]
 * row and delegates to [AudioCover.load], which walks the same artwork sources as
 * the in-app UI (MediaStore album-art cache, sidecar cover files, embedded tags).
 *
 * Every other URI — and all byte-array decodes, e.g. artwork embedded in the
 * playing file's tags for the notification — is forwarded untouched to a
 * [DataSourceBitmapLoader], so existing behavior is preserved.
 */
@OptIn(UnstableApi::class)
class FelicityArtworkBitmapLoader(
        private val context: Context,
        private val audioRepository: AudioRepository,
        private val scope: CoroutineScope
) : BitmapLoader {

    private val delegate = DataSourceBitmapLoader(context)

    override fun supportsMimeType(mimeType: String): Boolean {
        return delegate.supportsMimeType(mimeType)
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        return delegate.decodeBitmap(data)
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        if (uri.scheme != SCHEME) {
            return delegate.loadBitmap(uri)
        }

        return scope.future(Dispatchers.IO) {
            val audioId = uri.lastPathSegment?.toLongOrNull()
                ?: throw IllegalArgumentException("Bad artwork uri: $uri")
            val audio = audioRepository.getAudioById(audioId)
                ?: throw IllegalStateException("No audio for artwork uri: $uri")
            val bitmap = AudioCover.load(context, audio)
                ?: throw IllegalStateException("No artwork for audio ${audio.id}")
            scaleDown(bitmap)
        }
    }

    /**
     * Caps the bitmap at [MAX_ART_DIMENSION] px on its longest side. Car head units
     * receive artwork over binder, so full-size embedded covers (often 1000+ px)
     * are wasteful and can trip the transaction size limit.
     */
    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= MAX_ART_DIMENSION) return bitmap
        val scale = MAX_ART_DIMENSION.toFloat() / largest
        return Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
        )
    }

    companion object {
        /** URI scheme understood only by this loader. */
        const val SCHEME = "felicity-art"

        /** Builds the artwork URI for a library song. */
        fun artworkUriFor(audioId: Long): Uri =
            Uri.parse("$SCHEME://audio/$audioId")

        private const val MAX_ART_DIMENSION = 512
    }
}
