package dev.codexremote.app.bridge

import dev.codexremote.app.protocol.Capabilities
import dev.codexremote.app.protocol.DeviceCredential
import dev.codexremote.app.protocol.EventEnvelope
import dev.codexremote.app.protocol.Snapshot
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BridgeEventStreamTest {
    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private val streams = CopyOnWriteArrayList<BridgeEventStream>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient()
    }

    @After
    fun tearDown() {
        streams.forEach(BridgeEventStream::dispose)
        httpClient.dispatcher.cancelAll()
        server.shutdown()
        httpClient.dispatcher.executorService.shutdownNow()
        httpClient.connectionPool.evictAll()
    }

    @Test
    fun connectUsesAuthenticatedEventUrlAndStoredCursor() {
        val opened = CountDownLatch(1)
        server.enqueue(webSocketResponse(onOpen = { opened.countDown() }))
        val listener = RecordingListener()
        val stream = newStream(cursorStore = InMemoryCursorStore(41), listener = listener)

        stream.connect()

        assertTrue(opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(listener.connected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        val request = server.takeRequest(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        requireNotNull(request)
        assertEquals("/v1/events?cursor=41", request.path)
        assertEquals("Device phone-1:credential-do-not-leak", request.getHeader("Authorization"))
        assertTrue(listener.states.contains(EventStreamState.CONNECTING))
        assertTrue(listener.states.contains(EventStreamState.CONNECTED))
        stream.close()
        assertTrue(listener.disconnected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun rejectsBaseUrlThatIsNotAStrictHttpOrigin() {
        listOf(
            "ftp://bridge.example",
            "https://user@bridge.example",
            "https://bridge.example/connect",
            "https://bridge.example?mode=events",
            "https://bridge.example#events",
        ).forEach { invalidBaseUrl ->
            val stream = BridgeEventStream(
                baseUrl = invalidBaseUrl,
                credential = DeviceCredential(1, "phone-1", "credential-do-not-leak"),
                cursorStore = InMemoryCursorStore(),
                snapshotLoader = RecordingSnapshotLoader(snapshot(0)),
                listener = RecordingListener(),
                okHttpClient = httpClient,
            ).also(streams::add)
            try {
                stream.connect()
                throw AssertionError("invalid base URL must be rejected")
            } catch (expected: IllegalArgumentException) {
                assertEquals("baseUrl must be an HTTP(S) origin", expected.message)
            }
        }
    }

    @Test
    fun staleClosingCallbacksDuringReconnectCannotDisconnectOrCloseNewConnection() {
        val factory = ControlledWebSocketFactory()
        val listener = RecordingListener(expectedConnections = 2)
        val stream = newControlledStream(factory = factory, listener = listener)

        stream.connect()
        val first = factory.connections.single()
        first.open()
        stream.close()
        factory.beforeReturn = { connectionIndex ->
            if (connectionIndex == 1) {
                first.closing()
                first.closed()
            }
        }

        stream.connect()
        val second = factory.connections[1]
        second.open()

        assertTrue(listener.connected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(EventStreamState.CONNECTED, listener.states.last())
        assertEquals(0, second.webSocket.closeCalls)
        assertEquals(0, second.webSocket.cancelCalls)
    }

    @Test
    fun staleMessagesCannotDeliverAdvanceCursorOrRefreshSnapshot() {
        val factory = ControlledWebSocketFactory()
        val cursorStore = InMemoryCursorStore()
        val snapshotLoader = RecordingSnapshotLoader(snapshot(9))
        val listener = RecordingListener(expectedConnections = 2)
        val stream = newControlledStream(
            factory = factory,
            cursorStore = cursorStore,
            snapshotLoader = snapshotLoader,
            listener = listener,
        )

        stream.connect()
        val first = factory.connections.single()
        first.open()
        stream.close()
        stream.connect()
        factory.connections[1].open()
        assertTrue(listener.connected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

        first.message(event(1, "thread.updated"))
        first.message(event(3, "thread.updated"))
        first.message(event(0, "snapshot.required"))
        stream.close()

        assertTrue(listener.events.isEmpty())
        assertTrue(listener.snapshots.isEmpty())
        assertTrue(listener.failures.isEmpty())
        assertEquals(0L, cursorStore.load())
        assertTrue(cursorStore.saved.isEmpty())
        assertEquals(0, snapshotLoader.calls)
        assertEquals(EventStreamState.DISCONNECTED, listener.states.last())
    }

    @Test
    fun snapshotListenerFailureDoesNotSaveSnapshotCursor() {
        val factory = ControlledWebSocketFactory()
        val cursorStore = InMemoryCursorStore(4)
        val listener = RecordingListener(onSnapshotBlock = { error("snapshot listener failed") })
        val stream = newControlledStream(
            factory = factory,
            cursorStore = cursorStore,
            snapshotLoader = RecordingSnapshotLoader(snapshot(9)),
            listener = listener,
        )
        stream.connect()
        factory.connections.single().open()

        factory.connections.single().message(event(4, "snapshot.required"))

        assertTrue(listener.failed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals("Snapshot listener failed", listener.failures.single().message)
        assertEquals(4L, cursorStore.load())
        assertTrue(cursorStore.saved.isEmpty())
    }

    @Test
    fun closeWaitsForStartedDeliveryAndNoOldSideEffectsOccurAfterItReturns() {
        val factory = ControlledWebSocketFactory()
        val cursorStore = InMemoryCursorStore()
        val eventEntered = CountDownLatch(1)
        val releaseEvent = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val closeFinished = CountDownLatch(1)
        val listener = RecordingListener(
            onEventBlock = {
                eventEntered.countDown()
                assertTrue(releaseEvent.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            },
        )
        val stream = newControlledStream(factory, cursorStore, listener = listener)
        val closer = Executors.newSingleThreadExecutor()
        try {
            stream.connect()
            factory.connections.single().open()
            factory.connections.single().message(event(1, "thread.updated"))
            assertTrue(eventEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val close = closer.submit {
                closeStarted.countDown()
                stream.close()
                stream.connect()
                closeFinished.countDown()
            }
            assertTrue(closeStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse(closeFinished.await(200, TimeUnit.MILLISECONDS))
            releaseEvent.countDown()
            close.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

            val cursorAfterClose = cursorStore.load()
            assertEquals(1L, cursorAfterClose)
            assertEquals(listOf(1L), cursorStore.saved)
            assertEquals(cursorAfterClose, cursorStore.load())
            assertEquals(2, factory.connections.size)
        } finally {
            releaseEvent.countDown()
            closer.shutdownNow()
        }
    }

    @Test
    fun defaultWebSocketClientSendsPeriodicPings() {
        assertEquals(20_000L, bridgeEventStreamClient().pingIntervalMillis)
    }

    @Test
    fun listenerCanCloseStreamWithoutDeadlockOrCursorAdvance() {
        val factory = ControlledWebSocketFactory()
        val cursorStore = InMemoryCursorStore()
        lateinit var stream: BridgeEventStream
        val listener = RecordingListener(onEventBlock = { stream.close() })
        stream = newControlledStream(factory, cursorStore, listener = listener)
        stream.connect()
        factory.connections.single().open()

        factory.connections.single().message(event(1, "thread.updated"))

        assertTrue(listener.disconnected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(cursorStore.saved.isEmpty())
        assertEquals(0L, cursorStore.load())
    }

    @Test
    fun rejectsInvalidEnvelopeMetadataWithoutDeliveryOrCursorAdvance() {
        listOf(
            """{"protocolVersion":2,"eventCursor":1,"type":"thread.updated","payload":{}}""",
            """{"protocolVersion":1,"eventCursor":-1,"type":"thread.updated","payload":{}}""",
            """{"protocolVersion":1,"eventCursor":1,"type":"   ","payload":{}}""",
        ).forEach { maliciousEvent ->
            val factory = ControlledWebSocketFactory()
            val cursorStore = InMemoryCursorStore()
            val listener = RecordingListener()
            val stream = newControlledStream(factory, cursorStore, listener = listener)
            stream.connect()
            factory.connections.single().open()

            factory.connections.single().message(maliciousEvent)

            assertTrue(listener.failed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals("Bridge event stream received an invalid event", listener.failures.single().message)
            assertTrue(listener.events.isEmpty())
            assertTrue(listener.snapshots.isEmpty())
            assertTrue(cursorStore.saved.isEmpty())
            assertEquals(0L, cursorStore.load())
        }
    }

    @Test
    fun concurrentDisposeCloseAndConnectUseStableDisposedResult() {
        val factory = ControlledWebSocketFactory()
        val stream = newControlledStream(factory, listener = RecordingListener())
        stream.connect()
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val executor = Executors.newFixedThreadPool(3)
        try {
            val operations = listOf(
                executor.submit {
                    start.await()
                    repeat(100) { stream.dispose() }
                },
                executor.submit {
                    start.await()
                    repeat(100) { stream.close() }
                },
                executor.submit {
                    start.await()
                    repeat(100) {
                        try {
                            stream.connect()
                        } catch (error: IllegalStateException) {
                            if (error.message != DISPOSED_MESSAGE) failures += error
                        } catch (error: Throwable) {
                            failures += error
                        }
                    }
                },
            )

            start.countDown()
            operations.forEach { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }

            assertTrue(failures.toList().joinToString().orEmpty(), failures.isEmpty())
            stream.dispose()
            stream.close()
            try {
                stream.connect()
                throw AssertionError("connect after dispose must fail")
            } catch (error: IllegalStateException) {
                assertEquals(DISPOSED_MESSAGE, error.message)
            }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun cursorSaveReentrancyCannotOverwriteNewGenerationCursor() {
        val factory = ControlledWebSocketFactory()
        lateinit var stream: BridgeEventStream
        val cursorStore = ReconnectingCursorStore(
            reconnect = {
                stream.close()
                stream.connect()
            },
        )
        val listener = RecordingListener(expectedEvents = 2)
        stream = newControlledStream(factory, cursorStore, listener = listener)
        stream.connect()
        factory.connections.single().open()

        factory.connections.single().message(event(1, "first"))
        assertTrue(cursorStore.reconnected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(2, factory.connections.size)
        factory.connections[1].open()
        factory.connections[1].message(event(51, "second"))

        assertTrue(listener.eventsArrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        stream.close()
        assertEquals(listOf("first", "second"), listener.events.map { it.type })
        assertEquals(listOf(1L, 51L), cursorStore.saved)
    }

    @Test
    fun snapshotLoaderReentrancyCannotDeliverOrSaveOldSnapshotOrCloseNewSocket() {
        val factory = ControlledWebSocketFactory()
        val cursorStore = InMemoryCursorStore()
        lateinit var stream: BridgeEventStream
        val reconnected = CountDownLatch(1)
        val snapshotLoader = SnapshotLoader {
            stream.close()
            stream.connect()
            reconnected.countDown()
            snapshot(9)
        }
        val listener = RecordingListener()
        stream = newControlledStream(factory, cursorStore, snapshotLoader, listener)
        stream.connect()
        factory.connections.single().open()

        factory.connections.single().message(event(2, "gap"))
        assertTrue(reconnected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        stream.connect()

        assertEquals(2, factory.connections.size)
        assertTrue(listener.snapshots.isEmpty())
        assertTrue(cursorStore.saved.isEmpty())
        assertEquals(0, factory.connections[1].webSocket.closeCalls)
        assertEquals(0, factory.connections[1].webSocket.cancelCalls)
    }

    @Test
    fun deliversNextEventBeforeSavingItsCursor() {
        server.enqueue(webSocketResponse(messages = listOf(event(1, "thread.updated"))))
        val cursorStore = InMemoryCursorStore()
        val delivered = CountDownLatch(1)
        val listener = RecordingListener(
            onEventBlock = { envelope ->
                assertEquals(0L, cursorStore.load())
                assertEquals("thread-1", envelope.payload["id"]?.jsonPrimitive?.content)
                delivered.countDown()
            },
        )
        val stream = newStream(cursorStore = cursorStore, listener = listener)

        stream.connect()

        assertTrue(delivered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(cursorStore.savedCursor.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(listOf(1L), cursorStore.saved)
        stream.close()
    }

    @Test
    fun ignoresDuplicateAndOldEvents() {
        server.enqueue(
            webSocketResponse(
                messages = listOf(
                    event(4, "old"),
                    event(5, "duplicate"),
                    event(6, "next"),
                ),
            ),
        )
        val cursorStore = InMemoryCursorStore(5)
        val listener = RecordingListener(expectedEvents = 1)
        val stream = newStream(cursorStore = cursorStore, listener = listener)

        stream.connect()

        assertTrue(listener.eventsArrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(cursorStore.savedCursor.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(listOf("next"), listener.events.map { it.type })
        assertEquals(listOf(6L), cursorStore.saved)
        stream.close()
    }

    @Test
    fun gapLoadsSnapshotSavesSnapshotCursorAndClosesWithoutDeliveringEvent() {
        server.enqueue(webSocketResponse(messages = listOf(event(3, "thread.updated"))))
        val cursorStore = InMemoryCursorStore(1)
        val snapshot = snapshot(7)
        val snapshotLoader = RecordingSnapshotLoader(snapshot)
        val listener = RecordingListener(expectedSnapshots = 1)
        val stream = newStream(cursorStore, snapshotLoader, listener)

        stream.connect()

        assertTrue(listener.snapshotsArrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(cursorStore.savedCursor.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(1, snapshotLoader.calls)
        assertEquals(listOf(snapshot), listener.snapshots)
        assertTrue(listener.events.isEmpty())
        assertEquals(listOf(7L), cursorStore.saved)
        assertTrue(listener.disconnected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun snapshotRequiredLoadsSnapshotWithoutSavingEventCursor() {
        server.enqueue(webSocketResponse(messages = listOf(event(5, "snapshot.required"))))
        val cursorStore = InMemoryCursorStore(5)
        val snapshot = snapshot(11)
        val listener = RecordingListener(expectedSnapshots = 1)
        val stream = newStream(cursorStore, RecordingSnapshotLoader(snapshot), listener)

        stream.connect()

        assertTrue(listener.snapshotsArrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertTrue(cursorStore.savedCursor.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(listOf(11L), cursorStore.saved)
        assertTrue(listener.events.isEmpty())
        assertTrue(listener.disconnected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun listenerFailureDoesNotAdvanceCursor() {
        server.enqueue(webSocketResponse(messages = listOf(event(1, "thread.updated"))))
        val cursorStore = InMemoryCursorStore()
        val listener = RecordingListener(onEventBlock = { error("listener failed") })
        val stream = newStream(cursorStore = cursorStore, listener = listener)

        stream.connect()

        assertTrue(listener.failed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(0L, cursorStore.load())
        assertTrue(cursorStore.saved.isEmpty())
        assertEquals("Event listener failed", listener.failures.single().message)
        assertTrue(listener.disconnected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun invalidJsonReportsSafeFailureWithoutLeakingCredentialOrBody() {
        val responseSecret = "response-do-not-leak"
        val credentialSecret = "credential-do-not-leak"
        server.enqueue(webSocketResponse(messages = listOf("{\"secret\":\"$responseSecret\"")))
        val listener = RecordingListener()
        val stream = newStream(
            credential = DeviceCredential(1, "phone-1", credentialSecret),
            listener = listener,
        )

        stream.connect()

        assertTrue(listener.failed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        val failure = listener.failures.single()
        val exposed = failure.toString() + failure.message.orEmpty() + failure.cause?.message.orEmpty()
        assertFalse(exposed.contains(credentialSecret))
        assertFalse(exposed.contains(responseSecret))
        assertEquals("Bridge event stream received an invalid event", failure.message)
        assertTrue(listener.disconnected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    @Test
    fun snapshotLoaderFailureKeepsOldCursorAndReportsSafeFailure() {
        server.enqueue(webSocketResponse(messages = listOf(event(2, "snapshot.required"))))
        val cursorStore = InMemoryCursorStore(2)
        val secret = "snapshot-secret-do-not-leak"
        val listener = RecordingListener()
        val stream = newStream(
            cursorStore = cursorStore,
            snapshotLoader = SnapshotLoader { error(secret) },
            listener = listener,
        )

        stream.connect()

        assertTrue(listener.failed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(2L, cursorStore.load())
        assertTrue(cursorStore.saved.isEmpty())
        val failure = listener.failures.single()
        assertEquals("Unable to refresh bridge snapshot", failure.message)
        assertFalse((failure.toString() + failure.cause?.message.orEmpty()).contains(secret))
        assertTrue(listener.disconnected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
    }

    private fun newStream(
        cursorStore: CursorStore = InMemoryCursorStore(),
        snapshotLoader: SnapshotLoader = RecordingSnapshotLoader(snapshot(0)),
        listener: EventStreamListener,
        credential: DeviceCredential = DeviceCredential(1, "phone-1", "credential-do-not-leak"),
    ): BridgeEventStream = BridgeEventStream(
        baseUrl = server.url("/").toString().removeSuffix("/"),
        credential = credential,
        cursorStore = cursorStore,
        snapshotLoader = snapshotLoader,
        listener = listener,
        okHttpClient = httpClient,
    ).also(streams::add)

    private fun newControlledStream(
        factory: WebSocket.Factory,
        cursorStore: CursorStore = InMemoryCursorStore(),
        snapshotLoader: SnapshotLoader = RecordingSnapshotLoader(snapshot(0)),
        listener: EventStreamListener,
    ): BridgeEventStream = BridgeEventStream(
        baseUrl = server.url("/").toString().removeSuffix("/"),
        credential = DeviceCredential(1, "phone-1", "credential-do-not-leak"),
        cursorStore = cursorStore,
        snapshotLoader = snapshotLoader,
        listener = listener,
        webSocketFactory = factory,
    ).also(streams::add)

    private fun webSocketResponse(
        messages: List<String> = emptyList(),
        onOpen: () -> Unit = {},
    ): MockResponse = MockResponse().withWebSocketUpgrade(
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                onOpen()
                messages.forEach { message -> webSocket.send(message) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        },
    )

    private fun event(cursor: Long, type: String): String =
        """{"protocolVersion":1,"eventCursor":$cursor,"type":"$type","payload":{"id":"thread-1"}}"""

    private fun snapshot(cursor: Long): Snapshot = Snapshot(
        protocolVersion = 1,
        eventCursor = cursor,
        capabilities = Capabilities(true, true, true, true, true),
        projects = emptyList(),
        models = emptyList(),
        threads = emptyList(),
    )

    private class InMemoryCursorStore(initialCursor: Long = 0) : CursorStore {
        @Volatile
        private var cursor = initialCursor
        val saved = CopyOnWriteArrayList<Long>()
        val savedCursor = CountDownLatch(1)

        override fun load(): Long = cursor

        override fun save(cursor: Long) {
            require(cursor >= 0)
            this.cursor = cursor
            saved += cursor
            savedCursor.countDown()
        }

        override fun clear() {
            cursor = 0
        }
    }

    private class ReconnectingCursorStore(
        private val reconnect: () -> Unit,
    ) : CursorStore {
        private var loadCalls = 0
        private var didReconnect = false
        val saved = CopyOnWriteArrayList<Long>()
        val reconnected = CountDownLatch(1)

        override fun load(): Long {
            loadCalls += 1
            return if (loadCalls == 1) 0 else 50
        }

        override fun save(cursor: Long) {
            saved += cursor
            if (!didReconnect) {
                didReconnect = true
                reconnect()
                reconnected.countDown()
            }
        }

        override fun clear() = Unit
    }

    private class RecordingSnapshotLoader(private val snapshot: Snapshot) : SnapshotLoader {
        @Volatile
        var calls = 0

        override fun load(): Snapshot {
            calls += 1
            return snapshot
        }
    }

    private class RecordingListener(
        expectedConnections: Int = 1,
        expectedEvents: Int = 0,
        expectedSnapshots: Int = 0,
        private val onEventBlock: (EventEnvelope<JsonObject>) -> Unit = {},
        private val onSnapshotBlock: (Snapshot) -> Unit = {},
    ) : EventStreamListener {
        val states = CopyOnWriteArrayList<EventStreamState>()
        val events = CopyOnWriteArrayList<EventEnvelope<JsonObject>>()
        val snapshots = CopyOnWriteArrayList<Snapshot>()
        val failures = CopyOnWriteArrayList<Throwable>()
        val eventsArrived = CountDownLatch(expectedEvents)
        val snapshotsArrived = CountDownLatch(expectedSnapshots)
        val failed = CountDownLatch(1)
        val connected = CountDownLatch(expectedConnections)
        val disconnected = CountDownLatch(1)

        override fun onStateChanged(state: EventStreamState) {
            states += state
            if (state == EventStreamState.CONNECTED) connected.countDown()
            if (state == EventStreamState.DISCONNECTED) disconnected.countDown()
        }

        override fun onEvent(envelope: EventEnvelope<JsonObject>) {
            onEventBlock(envelope)
            events += envelope
            eventsArrived.countDown()
        }

        override fun onSnapshot(snapshot: Snapshot) {
            onSnapshotBlock(snapshot)
            snapshots += snapshot
            snapshotsArrived.countDown()
        }

        override fun onFailure(error: Throwable) {
            failures += error
            failed.countDown()
        }
    }

    private class ControlledWebSocketFactory : WebSocket.Factory {
        val connections = mutableListOf<ControlledConnection>()
        var beforeReturn: ((connectionIndex: Int) -> Unit)? = null

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            val connection = ControlledConnection(request, listener)
            connections += connection
            beforeReturn?.invoke(connections.lastIndex)
            return connection.webSocket
        }
    }

    private class ControlledConnection(
        request: Request,
        private val listener: WebSocketListener,
    ) {
        val webSocket = ControlledWebSocket(request)

        fun open() {
            listener.onOpen(
                webSocket,
                Response.Builder()
                    .request(webSocket.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(101)
                    .message("Switching Protocols")
                    .build(),
            )
        }

        fun message(text: String) {
            listener.onMessage(webSocket, text)
        }

        fun closing() {
            listener.onClosing(webSocket, 1000, "old connection closing")
        }

        fun closed() {
            listener.onClosed(webSocket, 1000, "old connection closed")
        }
    }

    private class ControlledWebSocket(private val originalRequest: Request) : WebSocket {
        var closeCalls = 0
        var cancelCalls = 0

        override fun request(): Request = originalRequest

        override fun queueSize(): Long = 0

        override fun send(text: String): Boolean = true

        override fun send(bytes: ByteString): Boolean = true

        override fun close(code: Int, reason: String?): Boolean {
            closeCalls += 1
            return true
        }

        override fun cancel() {
            cancelCalls += 1
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 3L
        const val DISPOSED_MESSAGE = "Bridge event stream has been disposed"
    }
}
