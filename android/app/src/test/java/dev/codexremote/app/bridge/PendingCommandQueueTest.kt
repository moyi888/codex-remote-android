package dev.codexremote.app.bridge

import dev.codexremote.app.protocol.CommandEnvelope
import dev.codexremote.app.security.KeyValueStorage
import dev.codexremote.app.security.SecretBox
import java.util.Base64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PendingCommandQueueTest {
    @Test
    fun enqueueEncryptsCommandsAndPreservesFifoOrder() {
        val storage = InMemoryStorage()
        val queue = PendingCommandQueue(storage, XorSecretBox())
        val first = command("first", "phone-1", "sensitive first prompt")
        val second = command("second", "phone-1", "sensitive second prompt")

        assertTrue(queue.enqueue(first))
        assertTrue(queue.enqueue(second))

        val persisted = requireNotNull(storage.value)
        assertFalse(persisted.contains("sensitive first prompt"))
        assertFalse(persisted.contains("sensitive second prompt"))
        assertEquals(first, queue.peek()?.command)
        assertEquals(listOf(first, second), queue.list().map { it.command })
    }

    @Test
    fun enqueueDeduplicatesByDeviceAndIdempotencyKey() {
        val queue = PendingCommandQueue(InMemoryStorage(), XorSecretBox())

        assertTrue(queue.enqueue(command("same-key", "phone-1", "first")))
        assertFalse(queue.enqueue(command("same-key", "phone-1", "duplicate")))
        assertTrue(queue.enqueue(command("same-key", "phone-2", "other device")))

        assertEquals(2, queue.list().size)
        assertEquals("first", queue.peek()?.command?.payload?.get("prompt")?.toString()?.trim('"'))
    }

    @Test
    fun enqueueRejectsCommandBeyondCapacityWithoutChangingQueue() {
        val queue = PendingCommandQueue(InMemoryStorage(), XorSecretBox())
        repeat(100) { index ->
            assertTrue(queue.enqueue(command("key-$index", "phone-1", "prompt-$index")))
        }

        try {
            queue.enqueue(command("overflow", "phone-1", "secret overflow prompt"))
            fail("queue must reject commands beyond its capacity")
        } catch (error: IllegalStateException) {
            assertEquals("Pending command queue is full", error.message)
            assertNull(error.cause)
            assertFalse(error.toString().contains("secret overflow prompt"))
        }

        assertEquals(100, queue.list().size)
    }

    @Test
    fun duplicateAtCapacityIsRejectedAsDuplicateRatherThanOverflow() {
        val queue = PendingCommandQueue(InMemoryStorage(), XorSecretBox())
        repeat(100) { index ->
            queue.enqueue(command("key-$index", "phone-1", "prompt-$index"))
        }

        assertFalse(queue.enqueue(command("key-0", "phone-1", "replacement")))
        assertEquals(100, queue.list().size)
    }

    @Test
    fun persistenceFailureUsesFixedSafeExceptionWithoutCause() {
        val queue = PendingCommandQueue(FailingPutStorage(), XorSecretBox())

        try {
            queue.enqueue(command("first", "phone-1", "prompt-do-not-leak"))
            fail("persistence failure must throw")
        } catch (error: IllegalStateException) {
            assertEquals("Unable to securely save pending commands", error.message)
            assertNull(error.cause)
            assertFalse(error.toString().contains("prompt-do-not-leak"))
            assertFalse(error.toString().contains("storage-secret"))
        }
    }

    @Test
    fun markAttemptUpdatesOnlyMatchingCommandAndRemoveCompletedAdvancesFifo() {
        val queue = PendingCommandQueue(InMemoryStorage(), XorSecretBox())
        queue.enqueue(command("first", "phone-1", "one"))
        queue.enqueue(command("second", "phone-1", "two"))

        queue.markAttempt("first", "2026-08-25T12:01:00Z")

        assertEquals(1, queue.peek()?.attempts)
        assertEquals("2026-08-25T12:01:00Z", queue.peek()?.lastAttemptAt)

        queue.removeCompleted("first")

        assertEquals("second", queue.peek()?.command?.idempotencyKey)
        assertEquals(0, queue.peek()?.attempts)
        assertNull(queue.peek()?.lastAttemptAt)
    }

    @Test
    fun listReturnsSnapshotThatCannotMutateStoredQueue() {
        val queue = PendingCommandQueue(InMemoryStorage(), XorSecretBox())
        queue.enqueue(command("first", "phone-1", "one"))
        val exposed = queue.list()

        @Suppress("UNCHECKED_CAST")
        (exposed as? MutableList<QueuedCommand>)?.clear()

        assertEquals(1, queue.list().size)
    }

    @Test
    fun corruptedCiphertextIsClearedAndReturnsEmptyQueue() {
        val storage = InMemoryStorage("not-valid-ciphertext")
        val queue = PendingCommandQueue(storage, XorSecretBox())

        assertEquals(emptyList<QueuedCommand>(), queue.list())
        assertNull(storage.value)
    }

    @Test
    fun corruptedJsonIsClearedAndReturnsEmptyQueue() {
        val box = XorSecretBox()
        val storage = InMemoryStorage(box.seal("not-json".encodeToByteArray()))
        val queue = PendingCommandQueue(storage, box)

        assertNull(queue.peek())
        assertNull(storage.value)
    }

    @Test
    fun queuedCommandToStringDoesNotExposePayload() {
        val secret = "prompt-do-not-leak"
        val queued = QueuedCommand(
            command = command("first", "phone-1", secret),
            attempts = 2,
            lastAttemptAt = "2026-08-25T12:01:00Z",
        )

        val rendered = queued.toString()

        assertFalse(rendered.contains(secret))
        assertFalse(rendered.contains("payload"))
        assertTrue(rendered.contains("attempts=2"))
    }

    private fun command(key: String, deviceId: String, prompt: String) = CommandEnvelope(
        protocolVersion = 1,
        requestId = "request-$key-$deviceId",
        deviceId = deviceId,
        idempotencyKey = key,
        type = "task.start",
        payload = buildJsonObject { put("prompt", prompt) },
        sentAt = "2026-08-25T12:00:00Z",
    )

    private class InMemoryStorage(initialValue: String? = null) : KeyValueStorage {
        var value: String? = initialValue

        override fun get(): String? = value

        override fun put(value: String) {
            this.value = value
        }

        override fun remove() {
            value = null
        }
    }

    private class FailingPutStorage : KeyValueStorage {
        override fun get(): String? = null

        override fun put(value: String) {
            error("storage-secret")
        }

        override fun remove() = Unit
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
