@file:JvmName("-Extensions")
@file:Suppress("NOTHING_TO_INLINE")

package coil.util

import android.content.res.Configuration
import android.graphics.ColorSpace
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import android.net.Uri
import android.os.Looper
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.ImageView.ScaleType.CENTER_INSIDE
import android.widget.ImageView.ScaleType.FIT_CENTER
import android.widget.ImageView.ScaleType.FIT_END
import android.widget.ImageView.ScaleType.FIT_START
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat
import coil.base.R
import coil.decode.DataSource
import coil.decode.Decoder
import coil.fetch.Fetcher
import coil.memory.MemoryCache
import coil.memory.TargetDelegate
import coil.memory.ViewTargetRequestManager
import coil.request.DefaultRequestOptions
import coil.request.ImageResult
import coil.request.Parameters
import coil.size.Scale
import coil.size.Size
import coil.target.ViewTarget
import coil.transform.Transformation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import okhttp3.Call
import okhttp3.Headers
import java.io.Closeable
import kotlin.coroutines.CoroutineContext

internal val View.requestManager: ViewTargetRequestManager
    get() {
        var manager = getTag(R.id.coil_request_manager) as? ViewTargetRequestManager
        if (manager == null) {
            manager = synchronized(this) {
                // Check again in case coil_request_manager was just set.
                (getTag(R.id.coil_request_manager) as? ViewTargetRequestManager)
                    ?.let { return@synchronized it }

                ViewTargetRequestManager().apply {
                    addOnAttachStateChangeListener(this)
                    setTag(R.id.coil_request_manager, this)
                }
            }
        }
        return manager
    }

internal val DataSource.emoji: String
    get() = when (this) {
        DataSource.MEMORY_CACHE,
        DataSource.MEMORY -> Emoji.BRAIN
        DataSource.DISK -> Emoji.FLOPPY
        DataSource.NETWORK -> Emoji.CLOUD
    }

internal val Drawable.width: Int
    get() = (this as? BitmapDrawable)?.bitmap?.width ?: intrinsicWidth

internal val Drawable.height: Int
    get() = (this as? BitmapDrawable)?.bitmap?.height ?: intrinsicHeight

internal val Drawable.isVector: Boolean
    get() = this is VectorDrawable || this is VectorDrawableCompat

internal fun Closeable.closeQuietly() {
    try {
        close()
    } catch (exception: RuntimeException) {
        throw exception
    } catch (_: Exception) {}
}

internal val ImageView.scale: Scale
    get() = when (scaleType) {
        FIT_START, FIT_CENTER, FIT_END, CENTER_INSIDE -> Scale.FIT
        else -> Scale.FILL
    }

/**
 * Wrap a [Call.Factory] factory as a [Call.Factory] instance.
 * [initializer] is called only once the first time [Call.Factory.newCall] is called.
 */
internal fun lazyCallFactory(initializer: () -> Call.Factory): Call.Factory {
    val lazy: Lazy<Call.Factory> = lazy(initializer)
    return Call.Factory { lazy.value.newCall(it) } // Intentionally not a method reference.
}

/** Modified from [MimeTypeMap.getFileExtensionFromUrl] to be more permissive with special characters. */
internal fun MimeTypeMap.getMimeTypeFromUrl(url: String?): String? {
    if (url.isNullOrBlank()) {
        return null
    }

    val extension = url
        .substringBeforeLast('#') // Strip the fragment.
        .substringBeforeLast('?') // Strip the query.
        .substringAfterLast('/') // Get the last path segment.
        .substringAfterLast('.', missingDelimiterValue = "") // Get the file extension.

    return getMimeTypeFromExtension(extension)
}

internal val Uri.firstPathSegment: String?
    get() = pathSegments.firstOrNull()

internal val Configuration.nightMode: Int
    get() = uiMode and Configuration.UI_MODE_NIGHT_MASK

internal val DEFAULT_REQUEST_OPTIONS = DefaultRequestOptions()

/** Required for compatibility with API 25 and below. */
internal val NULL_COLOR_SPACE: ColorSpace? = null

internal val EMPTY_HEADERS = Headers.Builder().build()

internal fun Headers?.orEmpty() = this ?: EMPTY_HEADERS

internal fun Parameters?.orEmpty() = this ?: Parameters.EMPTY

internal fun isMainThread() = Looper.myLooper() == Looper.getMainLooper()

internal inline val Any.identityHashCode: Int
    get() = System.identityHashCode(this)

internal inline val CoroutineContext.job: Job get() = get(Job)!!

@OptIn(ExperimentalStdlibApi::class)
internal inline val CoroutineContext.dispatcher: CoroutineDispatcher get() = get(CoroutineDispatcher)!!

internal var TargetDelegate.result: ImageResult?
    get() = (target as? ViewTarget<*>)?.view?.requestManager?.result
    set(value) {
        (target as? ViewTarget<*>)?.view?.requestManager?.result = value
    }

internal fun <T : Any> Fetcher<T>.asFactory() = Fetcher.Factory<T> { _, _, _ -> this }

internal fun Decoder.asFactory() = Decoder.Factory { _, _, _, _ -> this }

internal inline operator fun MemoryCache.Key.Companion.invoke(
    base: String,
    parameters: Parameters
): MemoryCache.Key {
    return MemoryCache.Key.Complex(
        base = base,
        transformations = emptyList(),
        size = null,
        parameters = parameters.cacheKeys()
    )
}

internal inline operator fun MemoryCache.Key.Companion.invoke(
    base: String,
    transformations: List<Transformation>,
    size: Size,
    parameters: Parameters
): MemoryCache.Key {
    return MemoryCache.Key.Complex(
        base = base,
        transformations = transformations.mapIndices { it.cacheKey },
        size = size,
        parameters = parameters.cacheKeys()
    )
}

internal inline operator fun MemoryCache.get(key: MemoryCache.Key?) = key?.let(::get)

inline fun synchronized(lock: Any, block: () -> R) {
    synchronized(lock, block)
}
