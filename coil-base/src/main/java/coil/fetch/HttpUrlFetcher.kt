package coil.fetch

import android.net.Uri
import android.os.NetworkOnMainThreadException
import android.webkit.MimeTypeMap
import androidx.annotation.VisibleForTesting
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.network.HttpException
import coil.network.cacheFile
import coil.request.Options
import coil.util.await
import coil.util.closeQuietly
import coil.util.dispatcher
import coil.util.getMimeTypeFromUrl
import kotlinx.coroutines.MainCoroutineDispatcher
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.ResponseBody
import okio.blackholeSink
import kotlin.coroutines.coroutineContext

internal class HttpUrlFetcher(
    private val data: Any,
    private val options: Options,
    private val callFactory: Call.Factory
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        // Perform this conversion in a fetcher instead of a mapper so
        // 'toHttpUrl' can be executed on a background thread.
        val url = when (data) {
            is Uri -> data.toString().toHttpUrl()
            is HttpUrl -> data
            else -> throw error("Invalid data: $data.")
        }
        val request = Request.Builder().url(url).headers(options.headers)

        val networkRead = options.networkCachePolicy.readEnabled
        val diskRead = options.diskCachePolicy.readEnabled
        when {
            !networkRead && diskRead -> {
                request.cacheControl(CacheControl.FORCE_CACHE)
            }
            networkRead && !diskRead -> if (options.diskCachePolicy.writeEnabled) {
                request.cacheControl(CacheControl.FORCE_NETWORK)
            } else {
                request.cacheControl(CACHE_CONTROL_FORCE_NETWORK_NO_CACHE)
            }
            !networkRead && !diskRead -> {
                // This causes the request to fail with a 504 Unsatisfiable Request.
                request.cacheControl(CACHE_CONTROL_NO_NETWORK_NO_CACHE)
            }
        }

        val response = if (coroutineContext.dispatcher is MainCoroutineDispatcher) {
            if (networkRead) {
                // Prevent executing requests on the main thread that could block due to a networking operation.
                throw NetworkOnMainThreadException()
            } else {
                // Work around https://github.com/Kotlin/kotlinx.coroutines/issues/2448 by blocking the current context.
                callFactory.newCall(request.build()).execute()
            }
        } else {
            // Suspend and enqueue the request on one of OkHttp's dispatcher threads.
            callFactory.newCall(request.build()).await()
        }

        if (!response.isSuccessful) {
            response.body?.close()
            throw HttpException(response)
        }
        val body = checkNotNull(response.body) { "Null response body!" }

        val cacheFile = response.cacheFile
        val source = body.source()
        try {
            val imageSource = if (cacheFile != null && cacheFile.exists()) {
                // Read the file into the disk cache if it isn't already cached.
                if (response.cacheResponse == null) {
                    source.readAll(blackholeSink())
                }
                // Intentionally do not close the source here. This prevents the cache file from being
                // deleted while being read and it will be cleaned up at the end of the request.
                ImageSource(cacheFile, source)
            } else {
                // Read directly from the response source.
                ImageSource(source, options.context)
            }

            return SourceResult(
                source = imageSource,
                mimeType = getMimeType(url, body),
                dataSource = if (response.cacheResponse != null) DataSource.DISK else DataSource.NETWORK
            )
        } catch (throwable: Throwable) {
            source.closeQuietly()
            throw throwable
        }
    }

    /**
     * Parse the response's `content-type` header.
     *
     * "text/plain" is often used as a default/fallback MIME type.
     * Attempt to guess a better MIME type from the file extension.
     */
    @VisibleForTesting
    internal fun getMimeType(data: HttpUrl, body: ResponseBody): String? {
        val rawContentType = body.contentType()?.toString()
        if (rawContentType == null || rawContentType.startsWith(MIME_TYPE_TEXT_PLAIN)) {
            MimeTypeMap.getSingleton().getMimeTypeFromUrl(data.toString())?.let { return it }
        }
        return rawContentType?.substringBefore(';')
    }

    class Factory(private val callFactory: Call.Factory) : Fetcher.Factory<Any> {

        override fun create(data: Any, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!isApplicable(data)) return null
            return HttpUrlFetcher(data, options, callFactory)
        }

        private fun isApplicable(data: Any): Boolean {
            return (data is Uri && (data.scheme == "http" || data.scheme == "https")) || data is HttpUrl
        }
    }

    companion object {
        private const val MIME_TYPE_TEXT_PLAIN = "text/plain"

        private val CACHE_CONTROL_FORCE_NETWORK_NO_CACHE = CacheControl.Builder().noCache().noStore().build()
        private val CACHE_CONTROL_NO_NETWORK_NO_CACHE = CacheControl.Builder().noCache().onlyIfCached().build()
    }
}
