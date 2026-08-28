package dev.codexremote.app.ui

import dev.codexremote.app.protocol.DeviceCredential
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class CommandFactoryTest {
    private val identifiers = ArrayDeque(listOf("request-1", "idempotency-1"))
    private val factory = CommandFactory(
        credential = DeviceCredential(
            protocolVersion = 1,
            deviceId = "phone-1",
            credential = "do-not-leak",
        ),
        newIdentifier = { identifiers.removeFirst() },
        now = { "2026-08-26T10:00:00Z" },
    )

    @Test
    fun createsTaskStartForSelectedProjectModelAndReasoning() {
        val command = factory.startTask(
            projectId = "project-1",
            prompt = "  修复构建  ",
            modelId = "gpt-5.6-sol",
            reasoningId = "high",
        )

        assertEquals(1, command.protocolVersion)
        assertEquals("request-1", command.requestId)
        assertEquals("phone-1", command.deviceId)
        assertEquals("idempotency-1", command.idempotencyKey)
        assertEquals("task.start", command.type)
        assertEquals(JsonPrimitive("project-1"), command.payload["projectId"])
        assertEquals(JsonPrimitive("修复构建"), command.payload["prompt"])
        assertEquals(JsonPrimitive("gpt-5.6-sol"), command.payload["model"])
        assertEquals(JsonPrimitive("high"), command.payload["reasoning"])
        assertEquals("2026-08-26T10:00:00Z", command.sentAt)
    }

    @Test
    fun omitsUnselectedOptionalTaskFields() {
        val command = factory.startTask("project-1", "检查状态", null, null)

        assertFalse(command.payload.containsKey("model"))
        assertFalse(command.payload.containsKey("reasoning"))
    }

    @Test
    fun createsThreadSendForSelectedThread() {
        val command = factory.sendTurn("thread-1", "  继续执行  ")

        assertEquals("thread.send", command.type)
        assertEquals(JsonPrimitive("thread-1"), command.payload["threadId"])
        assertEquals(JsonPrimitive("继续执行"), command.payload["prompt"])
    }

    @Test
    fun createsTurnSteerForActiveTurn() {
        val command = factory.steerTurn("thread-1", "turn-7", "继续执行")

        assertEquals("turn.steer", command.type)
        assertEquals(JsonPrimitive("thread-1"), command.payload["threadId"])
        assertEquals(JsonPrimitive("turn-7"), command.payload["turnId"])
        assertEquals(JsonPrimitive("继续执行"), command.payload["prompt"])
    }

    @Test
    fun createsTurnInterruptForActiveTurn() {
        val command = factory.interruptTurn("thread-1", "turn-7")

        assertEquals("turn.interrupt", command.type)
        assertEquals(JsonPrimitive("thread-1"), command.payload["threadId"])
        assertEquals(JsonPrimitive("turn-7"), command.payload["turnId"])
    }

    @Test
    fun rejectsBlankPrompts() {
        assertRejected { factory.startTask("project-1", " \n ", null, null) }
        assertRejected { factory.sendTurn("thread-1", "\t") }
    }

    @Test
    fun rejectsMissingTargetsAndReasoningWithoutModel() {
        assertRejected { factory.startTask(" ", "执行", null, null) }
        assertRejected { factory.startTask("project-1", "执行", null, "high") }
        assertRejected { factory.sendTurn("", "继续") }
        assertRejected { factory.steerTurn("thread-1", "", "继续") }
        assertRejected { factory.steerTurn("thread-1", "turn-1", " ") }
        assertRejected { factory.interruptTurn("", "turn-1") }
        assertRejected { factory.interruptTurn("thread-1", "") }
    }

    private fun assertRejected(action: () -> Unit) {
        try {
            action()
            fail("invalid command must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
