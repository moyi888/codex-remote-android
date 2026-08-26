package dev.codexremote.app.bridge

import dev.codexremote.app.protocol.CommandEnvelope
import dev.codexremote.app.protocol.DeviceCredential
import dev.codexremote.app.protocol.StoredBridgeConnection
import dev.codexremote.app.security.KeyValueStorage
import dev.codexremote.app.security.SecretBox
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OrderedCommandDrainingTest {
    private lateinit var server: MockWebServer
    private lateinit var queue: PendingCommandQueue

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        queue = PendingCommandQueue(InMemoryStorage(), XorSecretBox())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sendOrQueueDoesNotSendTargetWhenOlderCommandIsRetryable() {
        queue.enqueue(command("A"))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(jsonResponse())

        val result = outbox().sendOrQueue(command("B"))

        assertTrue(result is SendOrQueueResult.Blocked)
        assertTrue((result as SendOrQueueResult.Blocked).blocker is CommandOutboxResult.Queued)
        assertEquals("A", result.blocker.command.command.idempotencyKey)
        assertEquals("B", result.command.command.idempotencyKey)
        assertEquals(0, result.command.attempts)
        assertEquals(1, server.requestCount)
        assertEquals("A", requestKey(server.takeRequest().body.readUtf8()))
        assertEquals(listOf("A", "B"), queue.list().map { it.command.idempotencyKey })
        assertEquals(listOf(1, 0), queue.list().map { it.attempts })
    }

    @Test
    fun terminalClientFailureIsRemovedAndDrainContinuesInFifoOrder() {
        queue.enqueue(command("A"))
        queue.enqueue(command("C"))
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(jsonResponse())

        val outcomes = outbox().flush().outcomes

        assertEquals(2, outcomes.size)
        assertTrue(outcomes[0] is CommandOutboxResult.Rejected)
        assertEquals(400, (outcomes[0] as CommandOutboxResult.Rejected).statusCode)
        assertEquals(1, outcomes[0].command.attempts)
        assertTrue(outcomes[1] is CommandOutboxResult.Sent)
        assertEquals(1, outcomes[1].command.attempts)
        assertTrue(queue.list().isEmpty())
        assertEquals(listOf("A", "C"), takeRequestKeys(2))
    }

    @Test
    fun sendOrQueueReturnsPriorTerminalOutcomeBeforeTargetOutcome() {
        queue.enqueue(command("A"))
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(jsonResponse())

        val result = outbox().sendOrQueue(command("B"))

        assertTrue(result is SendOrQueueResult.Attempted)
        result as SendOrQueueResult.Attempted
        assertTrue(result.outcome is CommandOutboxResult.Sent)
        assertEquals(listOf("A", "B"), result.drainOutcomes.map { it.command.command.idempotencyKey })
        assertTrue(result.drainOutcomes[0] is CommandOutboxResult.Rejected)
        assertTrue(queue.list().isEmpty())
    }

    @Test
    fun authenticationFailureIsRetainedAndBlocksLaterCommands() {
        queue.enqueue(command("A"))
        queue.enqueue(command("B"))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(jsonResponse())

        val outcomes = outbox().flush().outcomes

        assertEquals(1, outcomes.size)
        assertTrue(outcomes.single() is CommandOutboxResult.AuthenticationRequired)
        assertEquals(1, outcomes.single().command.attempts)
        assertEquals(1, server.requestCount)
        assertEquals(listOf("A", "B"), queue.list().map { it.command.idempotencyKey })
        assertEquals(listOf(1, 0), queue.list().map { it.attempts })
    }

    @Test
    fun redirectResponseIsTerminalAndDoesNotPoisonQueue() {
        queue.enqueue(command("A"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/elsewhere"))

        val outcome = outbox().flush().outcomes.single()

        assertTrue(outcome is CommandOutboxResult.Rejected)
        assertEquals(302, (outcome as CommandOutboxResult.Rejected).statusCode)
        assertTrue(queue.list().isEmpty())
    }

    @Test
    fun invalidSuccessfulResponseIsTerminalAndDoesNotPoisonQueue() {
        queue.enqueue(command("A"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))

        val outcome = outbox().flush().outcomes.single()

        assertTrue(outcome is CommandOutboxResult.Rejected)
        assertEquals(200, (outcome as CommandOutboxResult.Rejected).statusCode)
        assertTrue(queue.list().isEmpty())
    }

    @Test
    fun concurrentFlushesForSameDeviceAreSingleFlightAndDoNotDuplicateCommands() {
        queue.enqueue(command("A"))
        queue.enqueue(command("B"))
        val firstRequestEntered = CountDownLatch(1)
        val releaseFirstRequest = CountDownLatch(1)
        val laterRequestEntered = CountDownLatch(1)
        val requestIndex = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (requestIndex.incrementAndGet() == 1) {
                    firstRequestEntered.countDown()
                    check(releaseFirstRequest.await(2, TimeUnit.SECONDS)) {
                        "test did not release first HTTP request"
                    }
                } else {
                    laterRequestEntered.countDown()
                }
                return jsonResponse()
            }
        }
        val executor = Executors.newFixedThreadPool(2)

        try {
            val firstFlush = executor.submit<FlushResult> { outbox().flush() }
            assertTrue(firstRequestEntered.await(1, TimeUnit.SECONDS))
            val secondFlushStarted = CountDownLatch(1)
            val secondAttempted = CountDownLatch(1)
            val secondFlush = executor.submit<FlushResult> {
                secondFlushStarted.countDown()
                outbox(clock = {
                    secondAttempted.countDown()
                    ATTEMPTED_AT
                }).flush()
            }
            assertTrue(secondFlushStarted.await(1, TimeUnit.SECONDS))

            assertFalse(laterRequestEntered.await(200, TimeUnit.MILLISECONDS))
            assertFalse(secondAttempted.await(200, TimeUnit.MILLISECONDS))
            assertEquals(1, server.requestCount)

            releaseFirstRequest.countDown()
            firstFlush.get(2, TimeUnit.SECONDS)
            secondFlush.get(2, TimeUnit.SECONDS)
            assertEquals(listOf("A", "B"), takeRequestKeys(2))
            assertEquals(2, server.requestCount)
            assertTrue(queue.list().isEmpty())
        } finally {
            releaseFirstRequest.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun retryableFailureRecordsSameAttemptInOutcomeAndQueue() {
        queue.enqueue(command("A"))
        server.enqueue(MockResponse().setResponseCode(429))

        val outcome = outbox().flush().outcomes.single()

        assertTrue(outcome is CommandOutboxResult.Queued)
        assertEquals(1, outcome.command.attempts)
        assertEquals(ATTEMPTED_AT, outcome.command.lastAttemptAt)
        assertEquals(outcome.command, queue.peek())
    }

    @Test
    fun unexpectedRuntimeFailureLeavesRecordedAttemptAndPropagates() {
        queue.enqueue(command("A"))
        val invalidConnection = connection().copy(baseUrl = "not-a-url")

        try {
            outbox(invalidConnection).flush()
            fail("unexpected runtime failure must propagate")
        } catch (_: IllegalArgumentException) {
            assertEquals(1, queue.peek()?.attempts)
            assertEquals(ATTEMPTED_AT, queue.peek()?.lastAttemptAt)
        }
    }

    @Test
    fun sendOrQueueRejectsCommandForDifferentCredentialDeviceBeforeEnqueue() {
        val mismatched = command("A").copy(deviceId = "phone-2")

        try {
            outbox().sendOrQueue(mismatched)
            fail("mismatched command device must be rejected")
        } catch (_: IllegalArgumentException) {
            assertTrue(queue.list().isEmpty())
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun differentCredentialDevicesDoNotShareSingleFlightLock() {
        val otherServer = MockWebServer()
        otherServer.start()
        val firstRequestEntered = CountDownLatch(1)
        val releaseFirstRequest = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                firstRequestEntered.countDown()
                check(releaseFirstRequest.await(2, TimeUnit.SECONDS))
                return jsonResponse()
            }
        }
        otherServer.enqueue(jsonResponse())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<SendOrQueueResult> { outbox().sendOrQueue(command("A")) }
            assertTrue(firstRequestEntered.await(1, TimeUnit.SECONDS))
            val otherQueue = PendingCommandQueue(InMemoryStorage(), XorSecretBox())
            val otherConnection = StoredBridgeConnection(
                baseUrl = otherServer.url("/").toString().removeSuffix("/"),
                credential = DeviceCredential(1, "phone-2", "credential-2"),
            )
            val second = executor.submit<SendOrQueueResult> {
                CommandOutbox(otherQueue, BridgeHttpClient(), otherConnection) { ATTEMPTED_AT }
                    .sendOrQueue(command("B").copy(deviceId = "phone-2"))
            }

            assertTrue(second.get(1, TimeUnit.SECONDS) is SendOrQueueResult.Attempted)
            assertEquals(1, otherServer.requestCount)
            releaseFirstRequest.countDown()
            assertTrue(first.get(2, TimeUnit.SECONDS) is SendOrQueueResult.Attempted)
        } finally {
            releaseFirstRequest.countDown()
            executor.shutdownNow()
            otherServer.shutdown()
        }
    }

    private fun outbox(
        connection: StoredBridgeConnection = connection(),
        clock: () -> String = { ATTEMPTED_AT },
    ) = CommandOutbox(
        queue = queue,
        httpClient = BridgeHttpClient(),
        connection = connection,
        clock = clock,
    )

    private fun connection() = StoredBridgeConnection(
        baseUrl = server.url("/").toString().removeSuffix("/"),
        credential = DeviceCredential(1, DEVICE_ID, "credential-1"),
    )

    private fun command(key: String) = CommandEnvelope(
        protocolVersion = 1,
        requestId = "request-$key",
        deviceId = DEVICE_ID,
        idempotencyKey = key,
        type = "task.start",
        payload = buildJsonObject { put("prompt", "sensitive-$key") },
        sentAt = "2026-08-25T12:00:00Z",
    )

    private fun takeRequestKeys(count: Int): List<String> = List(count) {
        val request = server.takeRequest(1, TimeUnit.SECONDS)
            ?: throw AssertionError("expected HTTP request ${it + 1} of $count")
        requestKey(request.body.readUtf8())
    }

    private fun requestKey(body: String): String = Json.parseToJsonElement(body)
        .jsonObject["idempotencyKey"]!!
        .jsonPrimitive
        .content

    private fun jsonResponse() = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"status":"completed"}""")

    private class InMemoryStorage : KeyValueStorage {
        private var value: String? = null

        override fun get(): String? = value

        override fun put(value: String) {
            this.value = value
        }

        override fun remove() {
            value = null
        }
    }

    private class XorSecretBox : SecretBox {
        override fun seal(plaintext: ByteArray): String = Base64.getEncoder().encodeToString(
            plaintext.map { (it.toInt() xor MASK).toByte() }.toByteArray(),
        )

        override fun open(ciphertext: String): ByteArray = Base64.getDecoder().decode(ciphertext)
            .map { (it.toInt() xor MASK).toByte() }
            .toByteArray()

        private companion object {
            const val MASK = 0x5a
        }
    }

    private companion object {
        const val DEVICE_ID = "phone-1"
        const val ATTEMPTED_AT = "2026-08-25T12:01:00Z"
    }
}
