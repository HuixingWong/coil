package coil.request

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import coil.ImageLoader
import coil.size.Precision
import coil.transition.Transition
import coil.util.Utils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * A set of default options that are used to fill in unset [ImageRequest] values.
 *
 * @see ImageLoader.defaults
 * @see ImageRequest.defaults
 */
data class DefaultRequestOptions(
    val interceptorDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    val fetcherDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val decoderDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val transformationDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val transitionFactory: Transition.Factory = Transition.Factory.NONE,
    val precision: Precision = Precision.AUTOMATIC,
    val bitmapConfig: Bitmap.Config = Utils.DEFAULT_BITMAP_CONFIG,
    val allowHardware: Boolean = true,
    val allowRgb565: Boolean = false,
    val placeholder: Drawable? = null,
    val error: Drawable? = null,
    val fallback: Drawable? = null,
    val memoryCachePolicy: CachePolicy = CachePolicy.ENABLED,
    val diskCachePolicy: CachePolicy = CachePolicy.ENABLED,
    val networkCachePolicy: CachePolicy = CachePolicy.ENABLED
)
