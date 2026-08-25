package dev.codexremote.app.bridge

import dev.codexremote.app.protocol.DeviceCredential
import dev.codexremote.app.protocol.EventEnvelope
import dev.codexremote.app.protocol.Snapshot
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

enum class EventStreamState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
}

fun interface SnapshotLoader {
    fun load(): Snapshot
}

interface EventStreamListener {
    fun onStateChanged(state: EventStreamState)

    fun onEvent(envelope: EventEnvelope<JsonObject>)

    fun onSnapshot(snapshot: Snapshot)

    fun onFailure(error: Throwable)
}

class BridgeEventStreamException(message: String) : RuntimeException(message) {
    override fun toString(): String = "BridgeEventStreamException(message=$message)"
}

class BridgeEventStream(
    private val baseUrl: String,
    private val credential: DeviceCredential,
    private val cursorStore: CursorStore,
    private val snapshotLoader: SnapshotLoader,
    private val listener: EventStreamListener,
    okHttpClient: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val httpClient = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    private val lock = Any()

    private var webSocket: WebSocket? = null
    private var state = EventStreamState.DISCONNECTED
    private var currentCursor = 0L

    fun connect() {
        val request: Request
        synchronized(lock) {
            if (webSocket != null || state != EventStreamState.DISCONNECTED) return
            currentCursor = cursorStore.load().also {
                require(it >= 0) { "Event cursor must be non-negative" }
            }
            request = Request.Builder()
                .url(eventEndpoint(baseUrl, currentCursor))
                .header(
                    "Authorization",
                    "Device ${credential.deviceId}:${credential.credential}",
                )
                .build()
            state = EventStreamState.CONNECTING
        }
        notifyState(EventStreamState.CONNECTING)
        val socket = httpClient.newWebSocket(request, SocketListener())
        synchronized(lock) {
            if (state == EventStreamState.DISCONNECTED) {
                socket.close(NORMAL_CLOSURE, CLIENT_CLOSED_REASON)
            } else {
                webSocket = socket
            }
        }
    }

    fun close() {
        closeCurrentSocket()
    }

    private fun eventEndpoint(baseUrl: String, cursor: Long): String {
        val origin = try {
            baseUrl.toHttpUrl()
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException(INVALID_BASE_URL_MESSAGE)
        }
        require(
            origin.scheme == "http" || origin.scheme == "https",
        ) { INVALID_BASE_URL_MESSAGE }
        require(
            origin.username.isEmpty() &&
                origin.password.isEmpty() &&
                origin.encodedPath == "/" &&
                origin.query == null &&
                origin.fragment == null,
        ) { INVALID_BASE_URL_MESSAGE }
        val httpEndpoint = origin.newBuilder()
            .addPathSegments("v1/events")
            .addQueryParameter("cursor", cursor.toString())
            .build()
        return if (origin.scheme == "https") {
            "wss:${httpEndpoint.toString().substringAfter(':')}"
        } else {
            "ws:${httpEndpoint.toString().substringAfter(':')}"
        }
    }

    private fun handleMessage(webSocket: WebSocket, text: String) {
        val envelope = try {
            json.decodeFromString<EventEnvelope<JsonObject>>(text)
        } catch (_: SerializationException) {
            failAndClose(webSocket, "Bridge event stream received an invalid event")
            return
        } catch (_: IllegalArgumentException) {
            failAndClose(webSocket, "Bridge event stream received an invalid event")
            return
        }

        if (envelope.type == SNAPSHOT_REQUIRED_TYPE) {
            refreshSnapshotAndClose(webSocket)
            return
        }

        val cursor = synchronized(lock) { currentCursor }
        if (envelope.eventCursor <= cursor) return
        if (envelope.eventCursor != cursor + 1) {
            refreshSnapshotAndClose(webSocket)
            return
        }

        try {
            listener.onEvent(envelope)
        } catch (_: Exception) {
            failAndClose(webSocket, "Event listener failed")
            return
        }
        try {
            cursorStore.save(envelope.eventCursor)
        } catch (_: Exception) {
            failAndClose(webSocket, "Unable to persist event cursor")
            return
        }
        synchronized(lock) {
            currentCursor = envelope.eventCursor
        }
    }

    private fun refreshSnapshotAndClose(webSocket: WebSocket) {
        val snapshot = try {
            snapshotLoader.load()
        } catch (_: Exception) {
            failAndClose(webSocket, "Unable to refresh bridge snapshot")
            return
        }
        if (snapshot.eventCursor < 0) {
            failAndClose(webSocket, "Unable to refresh bridge snapshot")
            return
        }
        try {
            cursorStore.save(snapshot.eventCursor)
        } catch (_: Exception) {
            failAndClose(webSocket, "Unable to persist snapshot cursor")
            return
        }
        synchronized(lock) {
            currentCursor = snapshot.eventCursor
        }
        try {
            listener.onSnapshot(snapshot)
        } catch (_: Exception) {
            failAndClose(webSocket, "Snapshot listener failed")
            return
        }
        closeCurrentSocket(webSocket)
    }

    private fun failAndClose(webSocket: WebSocket?, safeMessage: String) {
        try {
            listener.onFailure(BridgeEventStreamException(safeMessage))
        } catch (_: Exception) {
            // A failing failure callback must not expose or destabilize the stream.
        }
        closeCurrentSocket(webSocket)
    }

    private fun closeCurrentSocket(expected: WebSocket? = null) {
        val socket: WebSocket?
        val shouldNotify: Boolean
        synchronized(lock) {
            if (expected != null && webSocket != null && webSocket !== expected) return
            socket = webSocket ?: expected
            webSocket = null
            shouldNotify = state != EventStreamState.DISCONNECTED
            state = EventStreamState.DISCONNECTED
        }
        socket?.close(NORMAL_CLOSURE, CLIENT_CLOSED_REASON)
        if (shouldNotify) notifyState(EventStreamState.DISCONNECTED)
    }

    private fun notifyState(newState: EventStreamState) {
        try {
            listener.onStateChanged(newState)
        } catch (_: Exception) {
            // State reporting is best-effort and must not escape an OkHttp callback.
        }
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val shouldNotify = synchronized(lock) {
                if (state != EventStreamState.CONNECTING) {
                    false
                } else {
                    this@BridgeEventStream.webSocket = webSocket
                    state = EventStreamState.CONNECTED
                    true
                }
            }
            if (shouldNotify) notifyState(EventStreamState.CONNECTED)
            else webSocket.close(NORMAL_CLOSURE, CLIENT_CLOSED_REASON)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(webSocket, text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            closeCurrentSocket(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closeCurrentSocket(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            val active = synchronized(lock) {
                state != EventStreamState.DISCONNECTED &&
                    (this@BridgeEventStream.webSocket == null ||
                        this@BridgeEventStream.webSocket === webSocket)
            }
            if (active) failAndClose(webSocket, "Bridge event stream connection failed")
        }
    }

    private companion object {
        const val SNAPSHOT_REQUIRED_TYPE = "snapshot.required"
        const val NORMAL_CLOSURE = 1000
        const val CLIENT_CLOSED_REASON = "event stream closed"
        const val INVALID_BASE_URL_MESSAGE = "baseUrl must be an HTTP(S) origin"
    }
}
