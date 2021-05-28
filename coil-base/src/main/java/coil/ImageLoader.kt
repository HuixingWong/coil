@file:Suppress("unused")

package coil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import coil.decode.Decoder
import coil.drawable.CrossfadeDrawable
import coil.fetch.Fetcher
import coil.intercept.Interceptor
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.DefaultRequestOptions
import coil.request.Disposable
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.SuccessResult
import coil.size.Precision
import coil.target.ViewTarget
import coil.transform.Transformation
import coil.transition.CrossfadeTransition
import coil.transition.Transition
import coil.util.DEFAULT_REQUEST_OPTIONS
import coil.util.ImageLoaderOptions
import coil.util.Logger
import coil.util.Utils
import coil.util.buildForImageLoader
import coil.util.getDrawableCompat
import coil.util.lazyCallFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.Call
import okhttp3.OkHttpClient
import java.io.File

/**
 * A service class that loads images by executing [ImageRequest]s. Image loaders handle caching, data fetching,
 * image decoding, request management, memory management, and more.
 *
 * Image loaders are designed to be shareable and work best when you create a single instance and
 * share it throughout your app.
 */
interface ImageLoader {

    /**
     * The default options that are used to fill in unset [ImageRequest] values.
     */
    val defaults: DefaultRequestOptions

    /**
     * The components used to fulfil image requests.
     */
    val components: ComponentRegistry

    /**
     * An in-memory cache of recently loaded images.
     */
    val memoryCache: MemoryCache

    /**
     * Enqueue the [request] to be executed asynchronously.
     *
     * @param request The request to execute.
     * @return A [Disposable] which can be used to cancel or check the status of the request.
     */
    fun enqueue(request: ImageRequest): Disposable

    /**
     * Execute the [request] in the current coroutine scope.
     *
     * NOTE: If [ImageRequest.target] is a [ViewTarget], the job will automatically be cancelled
     * if its view is detached.
     *
     * @param request The request to execute.
     * @return A [SuccessResult] if the request completes successfully. Else, returns an [ErrorResult].
     */
    suspend fun execute(request: ImageRequest): ImageResult

    /**
     * Shutdown this image loader.
     *
     * Any closeable resources will be freed, new requests will fail before starting,
     * and in progress requests will be cancelled immediately.
     *
     * Shutting down an image loader is optional. It will be cleaned up automatically if dereferenced.
     */
    fun shutdown()

    /**
     * Create an [ImageLoader.Builder] that shares the same resources and configuration as this image loader.
     */
    fun newBuilder(): Builder

    class Builder {

        private val applicationContext: Context
        private var defaults: DefaultRequestOptions
        private var callFactory: Call.Factory?
        private var eventListenerFactory: EventListener.Factory?
        private var componentRegistry: ComponentRegistry?
        private var options: ImageLoaderOptions
        private var logger: Logger?
        private var memoryCache: MemoryCache?

        constructor(context: Context) {
            applicationContext = context.applicationContext
            defaults = DEFAULT_REQUEST_OPTIONS
            callFactory = null
            eventListenerFactory = null
            componentRegistry = null
            options = ImageLoaderOptions()
            logger = null
            memoryCache = null
        }

        internal constructor(imageLoader: RealImageLoader) {
            applicationContext = imageLoader.context.applicationContext
            defaults = imageLoader.defaults
            callFactory = imageLoader.callFactory
            eventListenerFactory = imageLoader.eventListenerFactory
            componentRegistry = imageLoader.componentRegistry
            options = imageLoader.options
            logger = imageLoader.logger
            memoryCache = imageLoader.memoryCache
        }

        /**
         * Set the [OkHttpClient] used for network requests.
         *
         * This is a convenience function for calling `callFactory(Call.Factory)`.
         *
         * NOTE: You must use [OkHttpClient.Builder.buildForImageLoader] instead of
         * [OkHttpClient.Builder.build] to enable disk caching.
         */
        fun okHttpClient(okHttpClient: OkHttpClient) = callFactory(okHttpClient)

        /**
         * Set a lazy callback to create the [OkHttpClient] used for network requests.
         *
         * This is a convenience function for calling `callFactory(() -> Call.Factory)`.
         *
         * NOTE: You must use [OkHttpClient.Builder.buildForImageLoader] instead of
         * [OkHttpClient.Builder.build] to enable disk caching.
         */
        fun okHttpClient(initializer: () -> OkHttpClient) = callFactory(initializer)

        /**
         * Set the [Call.Factory] used for network requests.
         *
         * Calling [okHttpClient] automatically sets this value.
         *
         * NOTE: You must use [OkHttpClient.Builder.buildForImageLoader] instead of
         * [OkHttpClient.Builder.build] to enable disk caching.
         */
        fun callFactory(callFactory: Call.Factory) = apply {
            this.callFactory = callFactory
        }

        /**
         * Set a lazy callback to create the [Call.Factory] used for network requests.
         *
         * This allows lazy creation of the [Call.Factory] on a background thread.
         * [initializer] is guaranteed to be called at most once.
         *
         * Prefer using this instead of `callFactory(Call.Factory)`.
         *
         * Calling [okHttpClient] automatically sets this value.
         *
         * NOTE: You must use [OkHttpClient.Builder.buildForImageLoader] instead of
         * [OkHttpClient.Builder.build] to enable disk caching.
         */
        fun callFactory(initializer: () -> Call.Factory) = apply {
            this.callFactory = lazyCallFactory(initializer)
        }

        /**
         * Build and set the [ComponentRegistry].
         */
        @JvmSynthetic
        inline fun componentRegistry(
            builder: ComponentRegistry.Builder.() -> Unit
        ) = componentRegistry(ComponentRegistry.Builder().apply(builder).build())

        /**
         * Set the [ComponentRegistry].
         */
        fun componentRegistry(registry: ComponentRegistry) = apply {
            this.componentRegistry = registry
        }

        /**
         * Set the [MemoryCache].
         */
        fun memoryCache(memoryCache: MemoryCache) = apply {
            this.memoryCache = memoryCache
        }

        /**
         * Allow the use of [Bitmap.Config.HARDWARE].
         *
         * If false, any use of [Bitmap.Config.HARDWARE] will be treated as [Bitmap.Config.ARGB_8888].
         *
         * NOTE: Setting this to false this will reduce performance on API 26 and above. Only disable if necessary.
         *
         * Default: true
         */
        fun allowHardware(enable: Boolean) = apply {
            this.defaults = this.defaults.copy(allowHardware = enable)
        }

        /**
         * Allow automatically using [Bitmap.Config.RGB_565] when an image is guaranteed to not have alpha.
         *
         * This will reduce the visual quality of the image, but will also reduce memory usage.
         *
         * Prefer only enabling this for low memory and resource constrained devices.
         *
         * Default: false
         */
        fun allowRgb565(enable: Boolean) = apply {
            this.defaults = this.defaults.copy(allowRgb565 = enable)
        }

        /**
         * Enables adding [File.lastModified] to the memory cache key when loading an image from a [File].
         *
         * This allows subsequent requests that load the same file to miss the memory cache if the file has been updated.
         * However, if the memory cache check occurs on the main thread (see [interceptorDispatcher]) calling
         * [File.lastModified] will cause a strict mode violation.
         *
         * Default: true
         */
        fun addLastModifiedToFileCacheKey(enable: Boolean) = apply {
            this.options = this.options.copy(addLastModifiedToFileCacheKey = enable)
        }

        /**
         * Set a single [EventListener] that will receive all callbacks for requests launched by this image loader.
         *
         * @see eventListenerFactory
         */
        fun eventListener(listener: EventListener) = eventListenerFactory(EventListener.Factory(listener))

        /**
         * Set the [EventListener.Factory] to create per-request [EventListener]s.
         */
        fun eventListenerFactory(factory: EventListener.Factory) = apply {
            this.eventListenerFactory = factory
        }

        /**
         * Enable a crossfade animation with duration [CrossfadeDrawable.DEFAULT_DURATION] milliseconds
         * when a request completes successfully.
         *
         * Default: false
         */
        fun crossfade(enable: Boolean) = crossfade(if (enable) CrossfadeDrawable.DEFAULT_DURATION else 0)

        /**
         * Enable a crossfade animation with [durationMillis] milliseconds when a request completes successfully.
         *
         * @see `crossfade(Boolean)`
         */
        fun crossfade(durationMillis: Int) = apply {
            val factory = if (durationMillis > 0) {
                CrossfadeTransition.Factory(durationMillis)
            } else {
                Transition.Factory.NONE
            }
            transitionFactory(factory)
        }

        /**
         * Set the default [Transition.Factory] for each request.
         */
        fun transitionFactory(factory: Transition.Factory) = apply {
            this.defaults = this.defaults.copy(transitionFactory = factory)
        }

        /**
         * Set the default precision for a request. [Precision] controls whether the size of the
         * loaded image must match the request's size exactly or not.
         *
         * Default: [Precision.AUTOMATIC]
         */
        fun precision(precision: Precision) = apply {
            this.defaults = this.defaults.copy(precision = precision)
        }

        /**
         * Set the preferred [Bitmap.Config].
         *
         * This is not guaranteed and a different config may be used in some situations.
         *
         * Default: [Utils.DEFAULT_BITMAP_CONFIG]
         */
        fun bitmapConfig(bitmapConfig: Bitmap.Config) = apply {
            this.defaults = this.defaults.copy(bitmapConfig = bitmapConfig)
        }

        /**
         * The [CoroutineDispatcher] that the [Interceptor] chain will be executed on.
         *
         * Default: `Dispatchers.Main.immediate`
         */
        fun interceptorDispatcher(dispatcher: CoroutineDispatcher) = apply {
            this.defaults = this.defaults.copy(interceptorDispatcher = dispatcher)
        }

        /**
         * The [CoroutineDispatcher] that [Fetcher.fetch] will be executed on.
         *
         * Default: [Dispatchers.IO]
         */
        fun fetcherDispatcher(dispatcher: CoroutineDispatcher) = apply {
            this.defaults = this.defaults.copy(fetcherDispatcher = dispatcher)
        }

        /**
         * The [CoroutineDispatcher] that [Decoder.decode] will be executed on.
         *
         * Default: [Dispatchers.Default]
         */
        fun decoderDispatcher(dispatcher: CoroutineDispatcher) = apply {
            this.defaults = this.defaults.copy(decoderDispatcher = dispatcher)
        }

        /**
         * The [CoroutineDispatcher] that [Transformation.transform] will be executed on.
         *
         * Default: [Dispatchers.Default]
         */
        fun transformationDispatcher(dispatcher: CoroutineDispatcher) = apply {
            this.defaults = this.defaults.copy(transformationDispatcher = dispatcher)
        }

        /**
         * Set the default placeholder drawable to use when a request starts.
         */
        fun placeholder(@DrawableRes drawableResId: Int) =
            placeholder(applicationContext.getDrawableCompat(drawableResId))

        /**
         * Set the default placeholder drawable to use when a request starts.
         */
        fun placeholder(drawable: Drawable?) = apply {
            this.defaults = this.defaults.copy(placeholder = drawable)
        }

        /**
         * Set the default error drawable to use when a request fails.
         */
        fun error(@DrawableRes drawableResId: Int) =
            error(applicationContext.getDrawableCompat(drawableResId))

        /**
         * Set the default error drawable to use when a request fails.
         */
        fun error(drawable: Drawable?) = apply {
            this.defaults = this.defaults.copy(error = drawable)
        }

        /**
         * Set the default fallback drawable to use if [ImageRequest.data] is null.
         */
        fun fallback(@DrawableRes drawableResId: Int) =
            fallback(applicationContext.getDrawableCompat(drawableResId))

        /**
         * Set the default fallback drawable to use if [ImageRequest.data] is null.
         */
        fun fallback(drawable: Drawable?) = apply {
            this.defaults = this.defaults.copy(fallback = drawable)
        }

        /**
         * Set the default memory cache policy.
         */
        fun memoryCachePolicy(policy: CachePolicy) = apply {
            this.defaults = this.defaults.copy(memoryCachePolicy = policy)
        }

        /**
         * Set the default disk cache policy.
         */
        fun diskCachePolicy(policy: CachePolicy) = apply {
            this.defaults = this.defaults.copy(diskCachePolicy = policy)
        }

        /**
         * Set the default network cache policy.
         *
         * NOTE: Disabling writes has no effect.
         */
        fun networkCachePolicy(policy: CachePolicy) = apply {
            this.defaults = this.defaults.copy(networkCachePolicy = policy)
        }

        /**
         * Set the [Logger] to write logs to.
         *
         * NOTE: Setting a [Logger] can reduce performance and should be avoided in release builds.
         */
        fun logger(logger: Logger?) = apply {
            this.logger = logger
        }

        /**
         * Create a new [ImageLoader] instance.
         */
        fun build(): ImageLoader {
            return RealImageLoader(
                context = applicationContext,
                defaults = defaults,
                memoryCache = memoryCache ?: MemoryCache(applicationContext),
                callFactory = callFactory ?: buildDefaultCallFactory(),
                eventListenerFactory = eventListenerFactory ?: EventListener.Factory.NONE,
                componentRegistry = componentRegistry ?: ComponentRegistry(),
                options = options,
                logger = logger
            )
        }

        private fun buildDefaultCallFactory() = lazyCallFactory {
            OkHttpClient.Builder().buildForImageLoader(applicationContext)
        }
    }

    companion object {
        /** Create a new [ImageLoader] without configuration. */
        @JvmStatic
        @JvmName("create")
        operator fun invoke(context: Context) = Builder(context).build()
    }
}
