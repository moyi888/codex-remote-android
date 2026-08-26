package dev.codexremote.app.bridge

import dev.codexremote.app.protocol.CommandEnvelope
import dev.codexremote.app.protocol.DeviceCredential
import dev.codexremote.app.protocol.StoredBridgeConnection
import dev.codexremote.app.security.KeyValueStorage
import dev.codexremote.app.security.SecretBox
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CommandOutboxTest {
    private lateinit var server: MockWebServer
    private lateinit var queue: PendingCommandQueue
    private lateinit var outbox: CommandOutbox

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        queue = PendingCommandQueue(InMemoryStorage(), XorSecretBox())
        outbox = CommandOutbox(
            queue = queue,
            httpClient = BridgeHttpClient(),
            connection = StoredBridgeConnection(
                baseUrl = server.url("/").toString().removeSuffix("/"),
                credential = DeviceCredential(1, "phone-1", "credential-1"),
            ),
            clock = { "2026-08-25T12:01:00Z" },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sendOrQueueRemovesCommandAfterSuccessfulSend() {
        server.enqueue(jsonResponse("completed"))
        val command = command("first")

        val result = outbox.sendOrQueue(command)

        assertTrue(result is SendOrQueueResult.Attempted)
        assertTrue((result as SendOrQueueResult.Attempted).outcome is CommandOutboxResult.Sent)
        assertEquals(1, result.command.attempts)
        assertTrue(queue.list().isEmpty())
        assertEquals("first", requestKey(server.takeRequest().body.readUtf8()))
    }

    @Test
    fun sendOrQueueRemovesTerminalClientFailureAndReturnsRejected() {
        server.enqueue(MockResponse().setResponseCode(400))
        val command = command("first")

        val result = outbox.sendOrQueue(command)

        assertTrue(result is SendOrQueueResult.Attempted)
        assertTrue((result as SendOrQueueResult.Attempted).outcome is CommandOutboxResult.Rejected)
        assertEquals(400, (result.outcome as CommandOutboxResult.Rejected).statusCode)
        assertEquals(1, result.command.attempts)
        assertTrue(queue.list().isEmpty())
    }

    @Test
    fun flushSendsInFifoOrderAndStopsAtFirstFailure() {
        queue.enqueue(command("first"))
        queue.enqueue(command("second"))
        queue.enqueue(command("third"))
        server.enqueue(jsonResponse("completed"))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(jsonResponse("completed"))

        val results = outbox.flush().outcomes

        assertEquals(2, results.size)
        assertTrue(results[0] is CommandOutboxResult.Sent)
        assertTrue(results[1] is CommandOutboxResult.Queued)
        assertEquals(1, results[0].command.attempts)
        assertEquals(1, results[1].command.attempts)
        assertEquals("first", requestKey(server.takeRequest().body.readUtf8()))
        assertEquals("second", requestKey(server.takeRequest().body.readUtf8()))
        assertEquals(2, server.requestCount)
        assertEquals(listOf("second", "third"), queue.list().map { it.command.idempotencyKey })
        assertEquals(1, queue.list()[0].attempts)
        assertEquals(0, queue.list()[1].attempts)
    }

    private fun command(key: String) = CommandEnvelope(
        protocolVersion = 1,
        requestId = "request-$key",
        deviceId = "phone-1",
        idempotencyKey = key,
        type = "task.start",
        payload = buildJsonObject { put("prompt", "sensitive-$key") },
        sentAt = "2026-08-25T12:00:00Z",
    )

    private fun requestKey(body: String): String = Json.parseToJsonElement(body)
        .jsonObject["idempotencyKey"]!!
        .jsonPrimitive
        .content

    private fun jsonResponse(status: String) = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"status":"$status"}""")

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
}
