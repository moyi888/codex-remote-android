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

class CompoundCommandIdentityTest {
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
                credential = DeviceCredential(1, DEVICE_2, "credential-2"),
            ),
            clock = { ATTEMPTED_AT },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun markAttemptUsesDeviceAndIdempotencyKey() {
        enqueueSameKeyForBothDevices()

        queue.markAttempt(DEVICE_2, SHARED_KEY, ATTEMPTED_AT)

        val commands = queue.list().associateBy { it.command.deviceId }
        assertEquals(0, commands.getValue(DEVICE_1).attempts)
        assertEquals(null, commands.getValue(DEVICE_1).lastAttemptAt)
        assertEquals(1, commands.getValue(DEVICE_2).attempts)
        assertEquals(ATTEMPTED_AT, commands.getValue(DEVICE_2).lastAttemptAt)
    }

    @Test
    fun removeCompletedUsesDeviceAndIdempotencyKey() {
        enqueueSameKeyForBothDevices()

        queue.removeCompleted(DEVICE_2, SHARED_KEY)

        assertEquals(listOf(DEVICE_1), queue.list().map { it.command.deviceId })
    }

    @Test
    fun sendOrQueueSuccessForSecondDeviceLeavesFirstDeviceCommand() {
        queue.enqueue(command(DEVICE_1))
        server.enqueue(jsonResponse())

        val result = outbox.sendOrQueue(command(DEVICE_2))

        assertTrue(result is SendOrQueueResult.Attempted)
        assertTrue((result as SendOrQueueResult.Attempted).outcome is CommandOutboxResult.Sent)
        assertEquals(listOf(DEVICE_1), queue.list().map { it.command.deviceId })
        assertEquals(DEVICE_2, requestDevice(server.takeRequest().body.readUtf8()))
    }

    @Test
    fun sendOrQueueFailureForSecondDeviceMarksOnlySecondDeviceCommand() {
        queue.enqueue(command(DEVICE_1))
        server.enqueue(MockResponse().setResponseCode(503))

        val result = outbox.sendOrQueue(command(DEVICE_2))

        assertTrue(result is SendOrQueueResult.Attempted)
        assertTrue((result as SendOrQueueResult.Attempted).outcome is CommandOutboxResult.Queued)
        val commands = queue.list().associateBy { it.command.deviceId }
        assertEquals(0, commands.getValue(DEVICE_1).attempts)
        assertEquals(1, commands.getValue(DEVICE_2).attempts)
        assertEquals(DEVICE_2, result.command.command.deviceId)
    }

    @Test
    fun flushSuccessForSecondDeviceDoesNotSendOrRemoveFirstDeviceCommand() {
        queue.enqueue(command(DEVICE_2))
        queue.enqueue(command(DEVICE_1))
        server.enqueue(jsonResponse())

        val results = outbox.flush().outcomes

        assertEquals(1, results.size)
        assertTrue(results.single() is CommandOutboxResult.Sent)
        assertEquals(1, server.requestCount)
        assertEquals(DEVICE_2, requestDevice(server.takeRequest().body.readUtf8()))
        assertEquals(listOf(DEVICE_1), queue.list().map { it.command.deviceId })
        assertEquals(0, queue.peek()?.attempts)
    }

    @Test
    fun flushFailureForSecondDeviceDoesNotMarkFirstDeviceCommand() {
        queue.enqueue(command(DEVICE_2))
        queue.enqueue(command(DEVICE_1))
        server.enqueue(MockResponse().setResponseCode(503))

        val results = outbox.flush().outcomes

        assertEquals(1, results.size)
        assertTrue(results.single() is CommandOutboxResult.Queued)
        val commands = queue.list().associateBy { it.command.deviceId }
        assertEquals(1, commands.getValue(DEVICE_2).attempts)
        assertEquals(0, commands.getValue(DEVICE_1).attempts)
        assertEquals(1, server.requestCount)
    }

    private fun enqueueSameKeyForBothDevices() {
        queue.enqueue(command(DEVICE_1))
        queue.enqueue(command(DEVICE_2))
    }

    private fun command(deviceId: String) = CommandEnvelope(
        protocolVersion = 1,
        requestId = "request-$deviceId",
        deviceId = deviceId,
        idempotencyKey = SHARED_KEY,
        type = "task.start",
        payload = buildJsonObject { put("prompt", "sensitive-$deviceId") },
        sentAt = "2026-08-25T12:00:00Z",
    )

    private fun requestDevice(body: String): String = Json.parseToJsonElement(body)
        .jsonObject["deviceId"]!!
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
        const val DEVICE_1 = "phone-1"
        const val DEVICE_2 = "phone-2"
        const val SHARED_KEY = "shared-key"
        const val ATTEMPTED_AT = "2026-08-25T12:01:00Z"
    }
}
