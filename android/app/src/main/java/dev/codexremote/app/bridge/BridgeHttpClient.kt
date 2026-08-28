package dev.codexremote.app.bridge

import dev.codexremote.app.protocol.CommandEnvelope
import dev.codexremote.app.protocol.CommandResponse
import dev.codexremote.app.protocol.DeviceCredential
import dev.codexremote.app.protocol.PairExchangeRequest
import dev.codexremote.app.protocol.PairingInvitation
import dev.codexremote.app.protocol.Snapshot
import dev.codexremote.app.diagnostics.DiagnosticLogStore
import dev.codexremote.app.diagnostics.DiagnosticLogs
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

internal fun bridgeHttpOkHttpClient(okHttpClient: OkHttpClient = OkHttpClient()): OkHttpClient =
    okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

class BridgeApiException(
    val statusCode: Int,
    val safeMessage: String,
) : RuntimeException(safeMessage) {
    override fun toString(): String =
        "BridgeApiException(statusCode=$statusCode, safeMessage=$safeMessage)"
}

class BridgeHttpClient(
    okHttpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val logs: DiagnosticLogStore = DiagnosticLogs.instance,
) {
    private val httpClient = bridgeHttpOkHttpClient(okHttpClient).newBuilder()
        .addNetworkInterceptor(PairingNoRetryInterceptor)
        .build()

    fun exchange(
        invitation: PairingInvitation,
        deviceId: String,
        deviceName: String,
    ): DeviceCredential {
        val request = Request.Builder()
            .url(endpoint(invitation.baseUrl, "v1/pair/exchange"))
            .post(jsonBody(PairExchangeRequest(invitation.token, deviceId, deviceName)))
            .build()
        return execute(request)
    }

    fun snapshot(baseUrl: String, credential: DeviceCredential): Snapshot {
        val request = authenticatedRequest(baseUrl, "v1/snapshot", credential)
            .get()
            .build()
        return execute(request)
    }

    fun threadRead(baseUrl: String, credential: DeviceCredential, threadId: String): JsonObject {
        val request = authenticatedRequest(baseUrl, "v1/threads/${java.net.URLEncoder.encode(threadId, "UTF-8")}", credential)
            .get()
            .build()
        return execute(request)
    }

    fun threadTurns(
        baseUrl: String,
        credential: DeviceCredential,
        threadId: String,
        cursor: String? = null,
        limit: Int = 50,
    ): JsonObject {
        val encodedId = java.net.URLEncoder.encode(threadId, "UTF-8")
        val url = endpoint(baseUrl, "v1/threads/$encodedId/turns").newBuilder()
            .addQueryParameter("limit", limit.toString())
            .apply { cursor?.takeIf { it.isNotBlank() }?.let { addQueryParameter("cursor", it) } }
            .build()
        val request = authenticatedRequest(url, credential).get().build()
        return execute(request)
    }

    fun sendCommand(
        baseUrl: String,
        credential: DeviceCredential,
        command: CommandEnvelope,
    ): CommandResponse {
        val request = authenticatedRequest(baseUrl, "v1/commands", credential)
            .post(jsonBody(command))
            .build()
        return execute(request)
    }

    private fun authenticatedRequest(
        baseUrl: String,
        path: String,
        credential: DeviceCredential,
    ): Request.Builder = Request.Builder()
        .url(endpoint(baseUrl, path))
        .header("Authorization", "Device ${credential.deviceId}:${credential.credential}")

    private fun authenticatedRequest(url: HttpUrl, credential: DeviceCredential): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Device ${credential.deviceId}:${credential.credential}")

    private fun endpoint(baseUrl: String, path: String): HttpUrl {
        val origin = try {
            baseUrl.toHttpUrl()
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("baseUrl must be an HTTP(S) origin")
        }
        require(
            origin.scheme == "http" || origin.scheme == "https",
        ) { "baseUrl must be an HTTP(S) origin" }
        require(
            origin.username.isEmpty() &&
                origin.password.isEmpty() &&
                origin.encodedPath == "/" &&
                origin.query == null &&
                origin.fragment == null,
        ) { "baseUrl must be an HTTP(S) origin" }
        return origin.newBuilder().addPathSegments(path).build()
    }

    private inline fun <reified T> jsonBody(value: T) =
        json.encodeToString(value).toRequestBody(JSON_MEDIA_TYPE)

    private inline fun <reified T> execute(request: Request): T {
        val startedAt = System.nanoTime()
        logs.info("http", "request ${request.method} ${request.url.encodedPath}")
        return try {
            httpClient.newCall(request).execute().use { response ->
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                logs.info("http", "response ${request.method} ${request.url.encodedPath} HTTP ${response.code} ${elapsedMs}ms")
                if (!response.isSuccessful) {
                    throw BridgeApiException(
                        statusCode = response.code,
                        safeMessage = "Bridge API request failed with HTTP ${response.code}",
                    )
                }
                try {
                    json.decodeFromString(response.body.string())
                } catch (_: SerializationException) {
                    throw BridgeApiException(
                        statusCode = response.code,
                        safeMessage = "Bridge API returned an invalid response",
                    )
                }
            }
        } catch (error: Exception) {
            logs.error("http", "request failed ${request.method} ${request.url.encodedPath}", error)
            throw error
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private object PairingNoRetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (request.url.encodedPath != PAIRING_PATH || response.code != 503) {
            return response
        }
        return response.newBuilder()
            .header("Retry-After", "1")
            .build()
    }

    private const val PAIRING_PATH = "/v1/pair/exchange"
}
