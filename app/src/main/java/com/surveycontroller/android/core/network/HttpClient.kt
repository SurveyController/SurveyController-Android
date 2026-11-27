package com.surveycontroller.android.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.FormBody
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

data class HttpResponse(
    val statusCode: Int,
    val body: String,
    val finalUrl: String,
)

sealed class SurveySubmitTransportError(message: String, cause: IOException? = null) : IOException(message, cause) {
    class ProxyTunnel(message: String, cause: IOException? = null) : SurveySubmitTransportError(message, cause)
    class ResultUnknown(message: String, cause: IOException) : SurveySubmitTransportError(message, cause)
}

/**
 * OkHttp 封装，提供与 Python httpx 对齐的 GET / POST(form) 能力，
 * 并支持按请求指定代理。对应 software/network/http。
 */
open class HttpClient(
    private val baseClient: OkHttpClient = defaultClient(),
) {
    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun clientFor(proxyAddress: String?, timeoutSeconds: Long): OkHttpClient {
        val proxy = parseProxy(proxyAddress)
        if (proxy == null && timeoutSeconds <= 0) return baseClient
        return baseClient.newBuilder()
            .apply {
                if (proxy != null) proxy(proxy)
                if (timeoutSeconds > 0) {
                    readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
                }
            }
            .build()
    }

    open suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 0,
        proxyAddress: String? = null,
    ): HttpResponse = execute(
        Request.Builder().url(url).headers(headers.toHeaders()).get().build(),
        proxyAddress,
        timeoutSeconds,
    )

    /** POST application/x-www-form-urlencoded，query 参数附加在 URL 上。 */
    suspend fun postForm(
        url: String,
        queryParams: Map<String, String> = emptyMap(),
        formFields: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 0,
        proxyAddress: String? = null,
    ): HttpResponse {
        val httpUrl: HttpUrl = url.toHttpUrl().newBuilder().apply {
            queryParams.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val form = FormBody.Builder(Charsets.UTF_8).apply {
            formFields.forEach { (k, v) -> add(k, v) }
        }.build()
        return execute(
            Request.Builder().url(httpUrl).headers(headers.toHeaders()).post(form).build(),
            proxyAddress,
            timeoutSeconds,
            surveySubmission = true,
        )
    }

    /** POST 任意 body（JSON 等），供腾讯 / credamo 使用。 */
    open suspend fun postBody(
        url: String,
        body: String,
        contentType: String = "application/json; charset=UTF-8",
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 0,
        proxyAddress: String? = null,
        surveySubmission: Boolean = false,
    ): HttpResponse = execute(
        Request.Builder()
            .url(url)
            .headers(headers.toHeaders())
            .post(body.toRequestBody(contentType.toMediaTypeOrNull()))
            .build(),
        proxyAddress,
        timeoutSeconds,
        surveySubmission,
    )

    private suspend fun execute(
        request: Request,
        proxyAddress: String?,
        timeoutSeconds: Long,
        surveySubmission: Boolean = false,
    ): HttpResponse = withContext(Dispatchers.IO) {
        var bodyStarted = false
        val client = clientFor(proxyAddress, timeoutSeconds)
        val requestClient = if (surveySubmission) {
            client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .eventListener(object : EventListener() {
                    override fun requestBodyStart(call: Call) {
                        bodyStarted = true
                    }
                })
                .build()
        } else {
            client
        }
        try {
            requestClient.newCall(request).execute().use { response ->
                HttpResponse(
                    statusCode = response.code,
                    body = response.body?.string().orEmpty(),
                    finalUrl = response.request.url.toString(),
                )
            }
        } catch (error: IOException) {
            if (surveySubmission && bodyStarted) {
                throw SurveySubmitTransportError.ResultUnknown(error.message.orEmpty(), error)
            }
            if (surveySubmission && !proxyAddress.isNullOrBlank() && error !is SSLException) {
                throw SurveySubmitTransportError.ProxyTunnel(error.message.orEmpty(), error)
            }
            throw error
        }.also {
            if (surveySubmission && it.statusCode == 407) {
                throw SurveySubmitTransportError.ProxyTunnel("代理要求身份验证")
            }
        }
    }

    /** 解析代理地址，支持 host:port / http://host:port / host:port:user:pass。 */
    private fun parseProxy(proxyAddress: String?): Proxy? {
        val text = proxyAddress?.trim().orEmpty()
        if (text.isEmpty()) return null
        val cleaned = text.substringAfter("://")
        val parts = cleaned.split(":")
        if (parts.size < 2) return null
        val host = parts[0]
        val port = parts[1].toIntOrNull() ?: return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
    }
}
