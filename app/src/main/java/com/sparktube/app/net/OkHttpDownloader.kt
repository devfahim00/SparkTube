package com.sparktube.app.net

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * OkHttp based [Downloader] implementation used by NewPipeExtractor.
 */
object OkHttpDownloader : Downloader() {

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .addHeader("User-Agent", USER_AGENT)

        request.headers().forEach { (name, values) ->
            values.forEach { value -> builder.addHeader(name, value) }
        }

        val body = request.dataToSend()
        when (request.httpMethod().uppercase(Locale.ROOT)) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "DELETE" -> builder.delete()
            "POST" -> builder.post(
                (body ?: ByteArray(0)).toRequestBody("application/json".toMediaTypeOrNull())
            )
            "PUT" -> builder.put(
                (body ?: ByteArray(0)).toRequestBody("application/json".toMediaTypeOrNull()
            ))
            else -> throw IOException("Unsupported HTTP method: ${request.httpMethod()}")
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }
            val finalUrl = response.request.url.toString()
            val text = response.body?.string() ?: ""
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                // Comment avatars: see CommentAvatarFix.
                CommentAvatarFix.apply(finalUrl, text),
                finalUrl
            )
        }
    }
}
