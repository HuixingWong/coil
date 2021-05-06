package coil.intercept

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.annotation.VisibleForTesting
import coil.ComponentRegistry
import coil.EventListener
import coil.decode.DataSource
import coil.decode.DecodeResult
import coil.decode.DecodeUtils
import coil.decode.DrawableUtils
import coil.decode.Options
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.memory.MemoryCache
import coil.memory.MemoryCacheService
import coil.memory.RealMemoryCache
import coil.memory.RequestService
import coil.memory.StrongMemoryCache
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.size.OriginalSize
import coil.size.PixelSize
import coil.size.Size
import coil.transform.Transformation
import coil.util.Logger
import coil.util.SystemCallbacks
import coil.util.allowInexactSize
import coil.util.fetcher
import coil.util.foldIndices
import coil.util.invoke
import coil.util.log
import coil.util.mapData
import coil.util.requireDecoder
import coil.util.requireFetcher
import coil.util.safeConfig
import coil.util.toDrawable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** The last interceptor in the chain which executes the [ImageRequest]. */
internal class EngineInterceptor(
    private val components: ComponentRegistry,
    private val strongMemoryCache: StrongMemoryCache,
    private val memoryCacheService: MemoryCacheService,
    private val requestService: RequestService,
    private val systemCallbacks: SystemCallbacks,
    private val logger: Logger?
) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        try {
            // This interceptor uses some internal APIs.
            check(chain is RealInterceptorChain)

            val request = chain.request
            val context = request.context
            val data = request.data
            val size = chain.size
            val eventListener = chain.eventListener

            // Perform any data mapping.
            eventListener.mapStart(request, data)
            val mappedData = components.mapData(data)
            eventListener.mapEnd(request, mappedData)

            // Check the memory cache.
            val fetcher = request.fetcher(mappedData) ?: components.requireFetcher(mappedData)
            val memoryCacheKey = request.memoryCacheKey ?: computeMemoryCacheKey(request, mappedData, fetcher, size)
            val value = if (request.memoryCachePolicy.readEnabled) memoryCacheService[memoryCacheKey] else null

            // Short circuit if the cached bitmap is valid.
            if (value != null && isCachedValueValid(memoryCacheKey, value, request, size)) {
                return SuccessResult(
                    drawable = value.bitmap.toDrawable(context),
                    request = request,
                    memoryCacheKey = memoryCacheKey,
                    isSampled = value.isSampled,
                    dataSource = DataSource.MEMORY_CACHE,
                    isPlaceholderMemoryCacheKeyPresent = chain.cached != null
                )
            }

            // Fetch, decode, transform, and cache the image.
            return withContext(Dispatchers.Unconfined) {
                // Fetch and decode the image.
                val (drawable, isSampled, dataSource) = execute(mappedData, fetcher, request, size, eventListener)

                // Cache the result in the memory cache.
                val isCached = writeToMemoryCache(request, memoryCacheKey, drawable, isSampled)

                // Return the result.
                SuccessResult(
                    drawable = drawable,
                    request = request,
                    memoryCacheKey = memoryCacheKey.takeIf { isCached },
                    isSampled = isSampled,
                    dataSource = dataSource,
                    isPlaceholderMemoryCacheKeyPresent = chain.cached != null
                )
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            } else {
                return requestService.errorResult(chain.request, throwable)
            }
        }
    }

    /** Compute the complex cache key for this request. */
    @VisibleForTesting
    internal fun computeMemoryCacheKey(
        request: ImageRequest,
        data: Any,
        fetcher: Fetcher<Any>,
        size: Size
    ): MemoryCache.Key? {
        val base = fetcher.cacheKey(data) ?: return null
        return if (request.transformations.isEmpty()) {
            MemoryCache.Key(base, request.parameters)
        } else {
            MemoryCache.Key(base, request.transformations, size, request.parameters)
        }
    }

    /** Return 'true' if [cacheValue] satisfies the [request]. */
    @VisibleForTesting
    internal fun isCachedValueValid(
        cacheKey: MemoryCache.Key?,
        cacheValue: RealMemoryCache.Value,
        request: ImageRequest,
        size: Size
    ): Boolean {
        // Ensure the size of the cached bitmap is valid for the request.
        if (!isSizeValid(cacheKey, cacheValue, request, size)) {
            return false
        }

        // Ensure we don't return a hardware bitmap if the request doesn't allow it.
        if (!requestService.isConfigValidForHardware(request, cacheValue.bitmap.safeConfig)) {
            logger?.log(TAG, Log.DEBUG) {
                "${request.data}: Cached bitmap is hardware-backed, which is incompatible with the request."
            }
            return false
        }

        // Else, the cached drawable is valid and we can short circuit the request.
        return true
    }

    /** Return 'true' if [cacheValue]'s size satisfies the [request]. */
    private fun isSizeValid(
        cacheKey: MemoryCache.Key?,
        cacheValue: RealMemoryCache.Value,
        request: ImageRequest,
        size: Size
    ): Boolean {
        when (size) {
            is OriginalSize -> {
                if (cacheValue.isSampled) {
                    logger?.log(TAG, Log.DEBUG) {
                        "${request.data}: Requested original size, but cached image is sampled."
                    }
                    return false
                }
            }
            is PixelSize -> {
                val cachedWidth: Int
                val cachedHeight: Int
                when (val cachedSize = (cacheKey as? MemoryCache.Key.Complex)?.size) {
                    is PixelSize -> {
                        cachedWidth = cachedSize.width
                        cachedHeight = cachedSize.height
                    }
                    OriginalSize, null -> {
                        val bitmap = cacheValue.bitmap
                        cachedWidth = bitmap.width
                        cachedHeight = bitmap.height
                    }
                }

                // Short circuit the size check if the size is at most 1 pixel off in either dimension.
                // This accounts for the fact that downsampling can often produce images with one dimension
                // at most one pixel off due to rounding.
                if (abs(cachedWidth - size.width) <= 1 && abs(cachedHeight - size.height) <= 1) {
                    return true
                }

                val multiple = DecodeUtils.computeSizeMultiplier(
                    srcWidth = cachedWidth,
                    srcHeight = cachedHeight,
                    dstWidth = size.width,
                    dstHeight = size.height,
                    scale = request.scale
                )
                if (multiple != 1.0 && !request.allowInexactSize) {
                    logger?.log(TAG, Log.DEBUG) {
                        "${request.data}: Cached image's request size ($cachedWidth, $cachedHeight) does not " +
                            "exactly match the requested size (${size.width}, ${size.height}, ${request.scale})."
                    }
                    return false
                }
                if (multiple > 1.0 && cacheValue.isSampled) {
                    logger?.log(TAG, Log.DEBUG) {
                        "${request.data}: Cached image's request size ($cachedWidth, $cachedHeight) is smaller " +
                            "than the requested size (${size.width}, ${size.height}, ${request.scale})."
                    }
                    return false
                }
            }
        }

        return true
    }

    /** Load the [data] as a [Drawable]. Apply any [Transformation]s. */
    private suspend inline fun execute(
        data: Any,
        fetcher: Fetcher<Any>,
        request: ImageRequest,
        size: Size,
        eventListener: EventListener
    ): DrawableResult {
        // Fetch the data.
        val options: Options
        val fetchResult: FetchResult
        withContext(request.fetcherDispatcher) {
            options = requestService.options(request, size, systemCallbacks.isOnline)
            eventListener.fetchStart(request, fetcher, options)
            fetchResult = fetcher.fetch(data, options)
            eventListener.fetchEnd(request, fetcher, options, fetchResult)
        }

        val baseResult = when (fetchResult) {
            is SourceResult -> {
                // Decode the data.
                val decodeResult: DecodeResult
                withContext(request.decoderDispatcher) {
                    val decoder = request.decoder ?: components.requireDecoder(request.data, fetchResult.source, fetchResult.mimeType)
                    eventListener.decodeStart(request, decoder, options)
                    decodeResult = decoder.decode(fetchResult.source, options)
                    eventListener.decodeEnd(request, decoder, options, decodeResult)
                }

                // Combine the fetch and decode operations' results.
                DrawableResult(
                    drawable = decodeResult.drawable,
                    isSampled = decodeResult.isSampled,
                    dataSource = fetchResult.dataSource
                )
            }
            is DrawableResult -> fetchResult
        }

        // Apply any transformations and prepare to draw.
        val finalResult = applyTransformations(baseResult, request, options, eventListener)
        (finalResult.drawable as? BitmapDrawable)?.bitmap?.prepareToDraw()
        return finalResult
    }

    /** Apply any [Transformation]s and return an updated [DrawableResult]. */
    @VisibleForTesting
    internal suspend inline fun applyTransformations(
        result: DrawableResult,
        request: ImageRequest,
        options: Options,
        eventListener: EventListener
    ): DrawableResult {
        val transformations = request.transformations
        if (transformations.isEmpty()) return result

        // Convert the drawable into a bitmap with a valid config.
        return withContext(request.transformationDispatcher) {
            val input = convertDrawableToBitmap(result.drawable, options, transformations)
            eventListener.transformStart(request, input)
            val output = transformations.foldIndices(input) { bitmap, transformation ->
                transformation.transform(bitmap, options.size).also { coroutineContext.ensureActive() }
            }
            eventListener.transformEnd(request, output)
            result.copy(drawable = output.toDrawable(request.context))
        }
    }

    /** Write [drawable] to the memory cache. Return 'true' if it was added to the cache. */
    private fun writeToMemoryCache(
        request: ImageRequest,
        key: MemoryCache.Key?,
        drawable: Drawable,
        isSampled: Boolean
    ): Boolean {
        if (!request.memoryCachePolicy.writeEnabled) {
            return false
        }

        if (key != null) {
            val bitmap = (drawable as? BitmapDrawable)?.bitmap
            if (bitmap != null) {
                strongMemoryCache.set(key, bitmap, isSampled)
                return true
            }
        }
        return false
    }

    /** Convert [drawable] to a [Bitmap]. */
    private fun convertDrawableToBitmap(
        drawable: Drawable,
        options: Options,
        transformations: List<Transformation>
    ): Bitmap {
        if (drawable is BitmapDrawable) {
            var bitmap = drawable.bitmap
            val config = bitmap.safeConfig
            if (config !in RequestService.VALID_TRANSFORMATION_CONFIGS) {
                logger?.log(TAG, Log.INFO) {
                    "Converting bitmap with config $config to apply transformations: $transformations"
                }
                bitmap = DrawableUtils.convertToBitmap(drawable,
                    options.config, options.size, options.scale, options.allowInexactSize)
            }
            return bitmap
        }

        logger?.log(TAG, Log.INFO) {
            val type = drawable::class.java.canonicalName
            "Converting drawable of type $type to apply transformations: $transformations"
        }
        return DrawableUtils.convertToBitmap(drawable,
            options.config, options.size, options.scale, options.allowInexactSize)
    }

    companion object {
        private const val TAG = "EngineInterceptor"
    }
}
