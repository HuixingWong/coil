package coil

import android.content.Context
import android.util.Log
import androidx.annotation.MainThread
import coil.decode.BitmapFactoryDecoder
import coil.fetch.AssetUriFetcher
import coil.fetch.BitmapFetcher
import coil.fetch.ByteBufferFetcher
import coil.fetch.ContentUriFetcher
import coil.fetch.DrawableFetcher
import coil.fetch.FileFetcher
import coil.fetch.HttpUrlFetcher
import coil.fetch.ResourceUriFetcher
import coil.intercept.EngineInterceptor
import coil.intercept.RealInterceptorChain
import coil.key.CompositeKeyer
import coil.map.FileUriMapper
import coil.map.ResourceIntMapper
import coil.map.ResourceUriMapper
import coil.map.StringMapper
import coil.memory.MemoryCache
import coil.memory.RequestService
import coil.request.DefaultRequestOptions
import coil.request.Disposable
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.NullRequestData
import coil.request.NullRequestDataException
import coil.request.OneShotDisposable
import coil.request.SuccessResult
import coil.request.ViewTargetDisposable
import coil.target.Target
import coil.target.ViewTarget
import coil.transition.NoneTransition
import coil.transition.TransitionTarget
import coil.util.Emoji
import coil.util.ImageLoaderOptions
import coil.util.Logger
import coil.util.SystemCallbacks
import coil.util.Utils.REQUEST_TYPE_ENQUEUE
import coil.util.Utils.REQUEST_TYPE_EXECUTE
import coil.util.awaitStarted
import coil.util.emoji
import coil.util.get
import coil.util.job
import coil.util.log
import coil.util.requestManager
import coil.util.result
import coil.util.toDrawable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.Call
import kotlin.coroutines.coroutineContext

internal class RealImageLoader(
    val context: Context,
    override val defaults: DefaultRequestOptions,
    override val memoryCache: MemoryCache,
    val callFactory: Call.Factory,
    val eventListenerFactory: EventListener.Factory,
    val componentRegistry: ComponentRegistry,
    val options: ImageLoaderOptions,
    val logger: Logger?
) : ImageLoader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate +
        CoroutineExceptionHandler { _, throwable -> logger?.log(TAG, throwable) })
    private val systemCallbacks = SystemCallbacks(this, context)
    private val requestService = RequestService(this, systemCallbacks, logger)
    override val components = componentRegistry.newBuilder()
        // Mappers
        .add(StringMapper())
        .add(FileUriMapper())
        .add(ResourceUriMapper())
        .add(ResourceIntMapper())
        // Keyers
        .add(CompositeKeyer(options.addLastModifiedToFileCacheKey))
        // Fetchers
        .add(HttpUrlFetcher.Factory(callFactory))
        .add(FileFetcher.Factory())
        .add(AssetUriFetcher.Factory())
        .add(ContentUriFetcher.Factory())
        .add(ResourceUriFetcher.Factory())
        .add(DrawableFetcher.Factory())
        .add(BitmapFetcher.Factory())
        .add(ByteBufferFetcher.Factory())
        // Decoders
        .add(BitmapFactoryDecoder.Factory())
        .build()
    private val interceptors = components.interceptors + EngineInterceptor(this, requestService, logger)

    override fun enqueue(request: ImageRequest): Disposable {
        // Start executing the request on the main thread.
        val deferred = scope.async {
            executeMain(request, REQUEST_TYPE_ENQUEUE).also { result ->
                if (result is ErrorResult) logger?.log(TAG, result.throwable)
            }
        }

        // Update the current request attached to the view and return a new disposable.
        return if (request.target is ViewTarget<*>) {
            val requestId = request.target.view.requestManager.setCurrentRequestJob(deferred)
            ViewTargetDisposable(requestId, request.target)
        } else {
            OneShotDisposable(deferred)
        }
    }

    override suspend fun execute(request: ImageRequest): ImageResult {
        // Start executing the request on the main thread.
        val deferred = scope.async {
            executeMain(request, REQUEST_TYPE_EXECUTE).also { result ->
                if (result is ErrorResult) throw result.throwable
            }
        }

        // Update the current request attached to the view and await the result.
        if (request.target is ViewTarget<*>) {
            request.target.view.requestManager.setCurrentRequestJob(deferred)
        }
        return deferred.await()
    }

    @MainThread
    private suspend fun executeMain(initialRequest: ImageRequest, type: Int): ImageResult {
        // Apply this image loader's defaults to this request.
        val request = initialRequest.newBuilder().defaults(defaults).build()

        // Create a new event listener.
        val eventListener = eventListenerFactory.create(request)

        // Wrap the request to manage its lifecycle.
        val requestDelegate = requestService.createRequestDelegate(request, coroutineContext.job)

        try {
            // Fail before starting if data is null.
            if (request.data == NullRequestData) throw NullRequestDataException()

            // Enqueued requests suspend until the lifecycle is started.
            if (type == REQUEST_TYPE_ENQUEUE) request.lifecycle.awaitStarted()

            // Set the placeholder on the target.
            val cached = memoryCache[request.placeholderMemoryCacheKey]?.bitmap
            request.target?.result = null
            request.target?.onStart(cached?.toDrawable(request.context) ?: request.placeholder)
            eventListener.onStart(request)
            request.listener?.onStart(request)

            // Resolve the size.
            eventListener.resolveSizeStart(request)
            val size = request.sizeResolver.size()
            eventListener.resolveSizeEnd(request, size)

            // Execute the interceptor chain.
            val result = withContext(request.interceptorDispatcher) {
                RealInterceptorChain(request, interceptors, 0, request, size, cached, eventListener).proceed(request)
            }

            // Set the result on the target.
            request.target?.result = result
            when (result) {
                is SuccessResult -> onSuccess(result, request.target, eventListener)
                is ErrorResult -> onError(result, request.target, eventListener)
            }
            return result
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                onCancel(request, eventListener)
                throw throwable
            } else {
                // Create the default error result if there's an uncaught exception.
                val result = requestService.errorResult(request, throwable)
                onError(result, request.target, eventListener)
                return result
            }
        } finally {
            requestDelegate.complete()
        }
    }

    /** Called by [SystemCallbacks.onTrimMemory]. */
    internal fun onTrimMemory(level: Int) {
        memoryCache.trimMemory(level)
    }

    override fun newBuilder() = ImageLoader.Builder(this)

    private suspend inline fun onSuccess(
        result: SuccessResult,
        target: Target?,
        eventListener: EventListener
    ) {
        val request = result.request
        val dataSource = result.dataSource
        logger?.log(TAG, Log.INFO) { "${dataSource.emoji} Successful (${dataSource.name}) - ${request.data}" }
        transition(result, target, eventListener) { target?.onSuccess(result.drawable) }
        eventListener.onSuccess(request, result)
        request.listener?.onSuccess(request, result)
    }

    private suspend inline fun onError(
        result: ErrorResult,
        target: Target?,
        eventListener: EventListener
    ) {
        val request = result.request
        logger?.log(TAG, Log.INFO) { "${Emoji.SIREN} Failed - ${request.data} - ${result.throwable}" }
        transition(result, target, eventListener) { target?.onError(result.drawable) }
        eventListener.onError(request, result)
        request.listener?.onError(request, result)
    }

    private fun onCancel(request: ImageRequest, eventListener: EventListener) {
        logger?.log(TAG, Log.INFO) { "${Emoji.CONSTRUCTION} Cancelled - ${request.data}" }
        eventListener.onCancel(request)
        request.listener?.onCancel(request)
    }

    private suspend inline fun transition(
        result: ImageResult,
        target: Target?,
        eventListener: EventListener,
        setDrawable: () -> Unit
    ) {
        if (target !is TransitionTarget) {
            setDrawable()
            return
        }

        val transition = result.request.transitionFactory.create(target, result)
        if (transition is NoneTransition) {
            setDrawable()
            return
        }

        eventListener.transitionStart(result.request, transition)
        transition.transition()
        eventListener.transitionEnd(result.request, transition)
    }

    companion object {
        private const val TAG = "RealImageLoader"
    }
}
