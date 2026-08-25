package dev.codexremote.app.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertNotNull
import org.junit.Test

class ModelsTest {
    @Test
    fun storedConnectionToStringDoesNotExposeCredential() {
        val secret = "credential-do-not-leak"

        val rendered = StoredBridgeConnection(
            baseUrl = "https://bridge.example",
            credential = DeviceCredential(1, "phone-1", secret),
        ).toString()

        assertFalse(rendered.contains(secret))
    }

    @Test
    fun decodesThreadSnapshotFixture() {
        val raw = """
            {
              "protocolVersion": 1,
              "eventCursor": 42,
              "type": "thread.snapshot",
              "payload": {
                "id": "thread-1",
                "title": "检查构建状态",
                "projectId": "project-1",
                "projectName": "示例项目",
                "source": "desktop",
                "state": "running",
                "updatedAt": "2026-08-25T12:00:00Z",
                "attention": {
                  "category": "browser_authorization",
                  "site": "github.com",
                  "confidence": 0.9,
                  "detectedAt": "2026-08-25T12:01:00Z"
                }
              }
            }
        """.trimIndent()

        val envelope = Json.decodeFromString<EventEnvelope<ThreadSummary>>(raw)

        assertEquals(1, envelope.protocolVersion)
        assertEquals(42L, envelope.eventCursor)
        assertEquals("thread-1", envelope.payload.id)
        assertEquals(ThreadState.RUNNING, envelope.payload.state)
        assertNotNull(envelope.payload.attention)
        assertEquals("github.com", envelope.payload.attention?.site)
    }

    @Test
    fun rejectsPrimitiveCommandPayload() {
        val raw = """
            {
              "protocolVersion": 1,
              "requestId": "request-1",
              "deviceId": "phone-1",
              "idempotencyKey": "start-1",
              "type": "task.start",
              "payload": "not-an-object",
              "sentAt": "2026-08-25T12:00:00Z"
            }
        """.trimIndent()

        try {
            Json.decodeFromString<CommandEnvelope>(raw)
            fail("primitive command payload must be rejected")
        } catch (_: SerializationException) {
            // Expected: the command wire contract only accepts JSON objects.
        }
    }
}
