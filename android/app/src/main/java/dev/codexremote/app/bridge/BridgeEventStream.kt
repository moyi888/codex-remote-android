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

class BridgeEventStream internal constructor(
    private val baseUrl: String,
    private val credential: DeviceCredential,
    private val cursorStore: CursorStore,
    private val snapshotLoader: SnapshotLoader,
    private val listener: EventStreamListener,
    private val webSocketFactory: WebSocket.Factory,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    constructor(
        baseUrl: String,
        credential: DeviceCredential,
        cursorStore: CursorStore,
        snapshotLoader: SnapshotLoader,
        listener: EventStreamListener,
        okHttpClient: OkHttpClient = OkHttpClient(),
        json: Json = Json { ignoreUnknownKeys = true },
    ) : this(
        baseUrl = baseUrl,
        credential = credential,
        cursorStore = cursorStore,
        snapshotLoader = snapshotLoader,
        listener = listener,
        webSocketFactory = okHttpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build(),
        json = json,
    )

    private val lock = Any()

    private var webSocket: WebSocket? = null
    private var state = EventStreamState.DISCONNECTED
    private var currentCursor = 0L
    private var activeGeneration = 0L

    fun connect() {
        val request: Request
        val generation: Long
        synchronized(lock) {
            if (webSocket != null || state != EventStreamState.DISCONNECTED) return
            currentCursor = cursorStore.load().also {
                require(it >= 0) { "Event cursor must be non-negative" }
            }
            activeGeneration += 1
            generation = activeGeneration
            request = Request.Builder()
                .url(eventEndpoint(baseUrl, currentCursor))
                .header(
                    "Authorization",
                    "Device ${credential.deviceId}:${credential.credential}",
                )
                .build()
            state = EventStreamState.CONNECTING
        }
        notifyState(generation, EventStreamState.CONNECTING)
        val socket = webSocketFactory.newWebSocket(request, SocketListener(generation))
        val stale = synchronized(lock) {
            if (generation == activeGeneration && state != EventStreamState.DISCONNECTED) {
                webSocket = socket
                false
            } else {
                true
            }
        }
        if (stale) socket.cancel()
    }

    fun close() {
        val socket: WebSocket?
        val shouldNotify: Boolean
        synchronized(lock) {
            activeGeneration += 1
            socket = webSocket
            webSocket = null
            shouldNotify = state != EventStreamState.DISCONNECTED
            state = EventStreamState.DISCONNECTED
        }
        socket?.close(NORMAL_CLOSURE, CLIENT_CLOSED_REASON)
        if (shouldNotify) notifyState(EventStreamState.DISCONNECTED)
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

    private fun handleMessage(generation: Long, webSocket: WebSocket, text: String) {
        if (!isActive(generation)) return
        val envelope = try {
            json.decodeFromString<EventEnvelope<JsonObject>>(text)
        } catch (_: SerializationException) {
            failAndClose(generation, webSocket, "Bridge event stream received an invalid event")
            return
        } catch (_: IllegalArgumentException) {
            failAndClose(generation, webSocket, "Bridge event stream received an invalid event")
            return
        }

        if (envelope.type == SNAPSHOT_REQUIRED_TYPE) {
            refreshSnapshotAndClose(generation, webSocket)
            return
        }

        val cursor = synchronized(lock) {
            if (generation != activeGeneration) return
            currentCursor
        }
        if (envelope.eventCursor <= cursor) return
        if (envelope.eventCursor != cursor + 1) {
            refreshSnapshotAndClose(generation, webSocket)
            return
        }

        try {
            listener.onEvent(envelope)
        } catch (_: Exception) {
            failAndClose(generation, webSocket, "Event listener failed")
            return
        }
        val saveFailed = synchronized(lock) {
            if (generation != activeGeneration) return
            try {
                cursorStore.save(envelope.eventCursor)
                currentCursor = envelope.eventCursor
                false
            } catch (_: Exception) {
                true
            }
        }
        if (saveFailed) {
            failAndClose(generation, webSocket, "Unable to persist event cursor")
            return
        }
    }

    private fun refreshSnapshotAndClose(generation: Long, webSocket: WebSocket) {
        if (!isActive(generation)) return
        val snapshot = try {
            snapshotLoader.load()
        } catch (_: Exception) {
            failAndClose(generation, webSocket, "Unable to refresh bridge snapshot")
            return
        }
        if (snapshot.eventCursor < 0) {
            failAndClose(generation, webSocket, "Unable to refresh bridge snapshot")
            return
        }
        val saveFailed = synchronized(lock) {
            if (generation != activeGeneration) return
            try {
                cursorStore.save(snapshot.eventCursor)
                currentCursor = snapshot.eventCursor
                false
            } catch (_: Exception) {
                true
            }
        }
        if (saveFailed) {
            failAndClose(generation, webSocket, "Unable to persist snapshot cursor")
            return
        }
        if (!isActive(generation)) return
        try {
            listener.onSnapshot(snapshot)
        } catch (_: Exception) {
            failAndClose(generation, webSocket, "Snapshot listener failed")
            return
        }
        closeCurrentSocket(generation, webSocket)
    }

    private fun failAndClose(generation: Long, webSocket: WebSocket?, safeMessage: String) {
        if (!isActive(generation)) return
        try {
            listener.onFailure(BridgeEventStreamException(safeMessage))
        } catch (_: Exception) {
            // A failing failure callback must not expose or destabilize the stream.
        }
        closeCurrentSocket(generation, webSocket)
    }

    private fun closeCurrentSocket(generation: Long, expected: WebSocket? = null) {
        val socket: WebSocket?
        val shouldNotify: Boolean
        synchronized(lock) {
            if (generation != activeGeneration) return
            activeGeneration += 1
            socket = webSocket ?: expected
            webSocket = null
            shouldNotify = state != EventStreamState.DISCONNECTED
            state = EventStreamState.DISCONNECTED
        }
        socket?.close(NORMAL_CLOSURE, CLIENT_CLOSED_REASON)
        if (shouldNotify) notifyState(EventStreamState.DISCONNECTED)
    }

    private fun isActive(generation: Long): Boolean = synchronized(lock) {
        generation == activeGeneration
    }

    private fun notifyState(generation: Long, newState: EventStreamState) {
        if (!isActive(generation)) return
        notifyState(newState)
    }

    private fun notifyState(newState: EventStreamState) {
        try {
            listener.onStateChanged(newState)
        } catch (_: Exception) {
            // State reporting is best-effort and must not escape an OkHttp callback.
        }
    }

    private inner class SocketListener(private val generation: Long) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val shouldNotify = synchronized(lock) {
                if (generation != activeGeneration || state != EventStreamState.CONNECTING) {
                    false
                } else {
                    this@BridgeEventStream.webSocket = webSocket
                    state = EventStreamState.CONNECTED
                    true
                }
            }
            if (shouldNotify) notifyState(generation, EventStreamState.CONNECTED)
            else webSocket.cancel()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isActive(generation)) return
            handleMessage(generation, webSocket, text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (!isActive(generation)) return
            webSocket.close(code, null)
            closeCurrentSocket(generation, webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isActive(generation)) return
            closeCurrentSocket(generation, webSocket)
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            if (!isActive(generation)) return
            failAndClose(generation, webSocket, "Bridge event stream connection failed")
        }
    }

    private companion object {
        const val SNAPSHOT_REQUIRED_TYPE = "snapshot.required"
        const val NORMAL_CLOSURE = 1000
        const val CLIENT_CLOSED_REASON = "event stream closed"
        const val INVALID_BASE_URL_MESSAGE = "baseUrl must be an HTTP(S) origin"
    }
}
