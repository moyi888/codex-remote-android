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

internal enum class EventStreamState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
}

/** Runs on the stream event thread; must not wait for another thread calling the stream. */
internal fun interface SnapshotLoader {
    fun load(): Snapshot
}

/** Callbacks run serially on the stream event thread; same-thread close/connect calls are supported. */
internal interface EventStreamListener {
    fun onStateChanged(state: EventStreamState)

    fun onEvent(envelope: EventEnvelope<JsonObject>)

    fun onSnapshot(snapshot: Snapshot)

    fun onFailure(error: Throwable)
}

internal class BridgeEventStreamException(message: String) : RuntimeException(message) {
    override fun toString(): String = "BridgeEventStreamException(message=$message)"
}

internal class BridgeEventStream internal constructor(
    private val baseUrl: String,
    private val credential: DeviceCredential,
    private val cursorStore: CursorStore,
    private val snapshotLoader: SnapshotLoader,
    private val listener: EventStreamListener,
    private val webSocketFactory: WebSocket.Factory,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val eventLoop: EventLoop = SingleThreadEventLoop(),
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

    private var disposed = false

    private var activeGeneration = 0L
    private var currentSocket: WebSocket? = null
    private var state = EventStreamState.DISCONNECTED
    private var currentCursor = 0L

    fun connect() {
        check(eventLoop.runSyncIfOpen(::connectOnLoop)) { DISPOSED_MESSAGE }
    }

    fun close() {
        eventLoop.runSyncIfOpen {
            if (!disposed) closeOnLoop()
        }
    }

    fun dispose() {
        eventLoop.runSyncIfOpen {
            if (!disposed) {
                disposed = true
                closeOnLoop()
            }
        }
        eventLoop.shutdown()
    }

    private fun connectOnLoop() {
        check(!disposed) { DISPOSED_MESSAGE }
        if (currentSocket != null || state != EventStreamState.DISCONNECTED) return

        activeGeneration += 1
        val generation = activeGeneration
        val loadedCursor = try {
            cursorStore.load()
        } catch (_: Exception) {
            if (!isActive(generation)) return
            throw IllegalStateException("Unable to load event cursor")
        }
        if (!isActive(generation)) return
        require(loadedCursor >= 0) { "Event cursor must be non-negative" }
        currentCursor = loadedCursor
        val request = Request.Builder()
            .url(eventEndpoint(baseUrl, currentCursor))
            .header(
                "Authorization",
                "Device ${credential.deviceId}:${credential.credential}",
            )
            .build()
        state = EventStreamState.CONNECTING
        notifyState(EventStreamState.CONNECTING)
        if (!isActive(generation)) return

        val socket = try {
            webSocketFactory.newWebSocket(request, SocketListener(generation))
        } catch (_: Exception) {
            failAndTerminate(generation, null, "Bridge event stream connection failed")
            return
        }
        if (isActive(generation) && state != EventStreamState.DISCONNECTED) {
            currentSocket = socket
        } else {
            socket.cancel()
        }
    }

    private fun closeOnLoop() {
        activeGeneration += 1
        val socket = currentSocket
        currentSocket = null
        val shouldNotify = state != EventStreamState.DISCONNECTED
        state = EventStreamState.DISCONNECTED
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
            failAndTerminate(generation, webSocket, INVALID_EVENT_MESSAGE)
            return
        } catch (_: IllegalArgumentException) {
            failAndTerminate(generation, webSocket, INVALID_EVENT_MESSAGE)
            return
        }
        if (
            envelope.protocolVersion != PROTOCOL_VERSION ||
            envelope.eventCursor < 0 ||
            envelope.type.isBlank()
        ) {
            failAndTerminate(generation, webSocket, INVALID_EVENT_MESSAGE)
            return
        }

        if (envelope.type == SNAPSHOT_REQUIRED_TYPE) {
            refreshSnapshotAndTerminate(generation, webSocket)
            return
        }
        if (envelope.eventCursor <= currentCursor) return
        if (envelope.eventCursor != currentCursor + 1) {
            refreshSnapshotAndTerminate(generation, webSocket)
            return
        }

        try {
            listener.onEvent(envelope)
        } catch (_: Exception) {
            failAndTerminate(generation, webSocket, "Event listener failed")
            return
        }
        if (!isActive(generation)) return
        try {
            cursorStore.save(envelope.eventCursor)
        } catch (_: Exception) {
            failAndTerminate(generation, webSocket, "Unable to persist event cursor")
            return
        }
        if (!isActive(generation)) return
        currentCursor = envelope.eventCursor
    }

    private fun refreshSnapshotAndTerminate(generation: Long, webSocket: WebSocket) {
        if (!isActive(generation)) return
        val snapshot = try {
            snapshotLoader.load()
        } catch (_: Exception) {
            failAndTerminate(generation, webSocket, "Unable to refresh bridge snapshot")
            return
        }
        if (!isActive(generation)) return
        if (snapshot.protocolVersion != PROTOCOL_VERSION || snapshot.eventCursor < 0) {
            failAndTerminate(generation, webSocket, "Unable to refresh bridge snapshot")
            return
        }
        try {
            listener.onSnapshot(snapshot)
        } catch (_: Exception) {
            failAndTerminate(generation, webSocket, "Snapshot listener failed")
            return
        }
        if (!isActive(generation)) return
        try {
            cursorStore.save(snapshot.eventCursor)
        } catch (_: Exception) {
            failAndTerminate(generation, webSocket, "Unable to persist snapshot cursor")
            return
        }
        if (!isActive(generation)) return
        currentCursor = snapshot.eventCursor
        terminate(generation, webSocket)
    }

    private fun failAndTerminate(generation: Long, webSocket: WebSocket?, safeMessage: String) {
        if (!isActive(generation)) return
        try {
            listener.onFailure(BridgeEventStreamException(safeMessage))
        } catch (_: Exception) {
            // A failing failure callback must not expose or destabilize the stream.
        }
        terminate(generation, webSocket)
    }

    private fun terminate(generation: Long, expected: WebSocket? = null) {
        if (!isActive(generation)) return
        activeGeneration += 1
        val socket = currentSocket ?: expected
        currentSocket = null
        val shouldNotify = state != EventStreamState.DISCONNECTED
        state = EventStreamState.DISCONNECTED
        socket?.close(NORMAL_CLOSURE, CLIENT_CLOSED_REASON)
        if (shouldNotify) notifyState(EventStreamState.DISCONNECTED)
    }

    private fun isActive(generation: Long): Boolean = generation == activeGeneration

    private fun notifyState(newState: EventStreamState) {
        try {
            listener.onStateChanged(newState)
        } catch (_: Exception) {
            // State reporting is best-effort and must not escape the event loop.
        }
    }

    private inner class SocketListener(private val generation: Long) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            eventLoop.dispatch {
                if (!isActive(generation)) {
                    webSocket.cancel()
                    return@dispatch
                }
                currentSocket = webSocket
                state = EventStreamState.CONNECTED
                notifyState(EventStreamState.CONNECTED)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            eventLoop.dispatch {
                if (isActive(generation)) handleMessage(generation, webSocket, text)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            eventLoop.dispatch {
                if (!isActive(generation)) return@dispatch
                webSocket.close(code, null)
                terminate(generation, webSocket)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            eventLoop.dispatch {
                if (isActive(generation)) terminate(generation, webSocket)
            }
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            eventLoop.dispatch {
                if (isActive(generation)) {
                    failAndTerminate(generation, webSocket, "Bridge event stream connection failed")
                }
            }
        }
    }

    private companion object {
        const val PROTOCOL_VERSION = 1
        const val DISPOSED_MESSAGE = "Bridge event stream has been disposed"
        const val SNAPSHOT_REQUIRED_TYPE = "snapshot.required"
        const val INVALID_EVENT_MESSAGE = "Bridge event stream received an invalid event"
        const val NORMAL_CLOSURE = 1000
        const val CLIENT_CLOSED_REASON = "event stream closed"
        const val INVALID_BASE_URL_MESSAGE = "baseUrl must be an HTTP(S) origin"
    }
}
