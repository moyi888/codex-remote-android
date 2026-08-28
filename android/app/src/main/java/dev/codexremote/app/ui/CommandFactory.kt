package dev.codexremote.app.ui

import dev.codexremote.app.protocol.CommandEnvelope
import dev.codexremote.app.protocol.DeviceCredential
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CommandFactory(
    private val credential: DeviceCredential,
    private val newIdentifier: () -> String,
    private val now: () -> String,
) {
    fun startTask(
        projectId: String,
        prompt: String,
        modelId: String?,
        reasoningId: String?,
    ): CommandEnvelope {
        val normalizedProjectId = projectId.trim()
        val normalizedPrompt = prompt.trim()
        val normalizedModelId = modelId?.trim()?.takeIf(String::isNotEmpty)
        val normalizedReasoningId = reasoningId?.trim()?.takeIf(String::isNotEmpty)
        require(normalizedProjectId.isNotEmpty()) { "project is required" }
        require(normalizedPrompt.isNotEmpty()) { "prompt is required" }
        require(normalizedReasoningId == null || normalizedModelId != null) {
            "reasoning requires a model"
        }
        return command(
            type = "task.start",
            payload = buildJsonObject {
                put("projectId", normalizedProjectId)
                put("prompt", normalizedPrompt)
                normalizedModelId?.let { put("model", it) }
                normalizedReasoningId?.let { put("reasoning", it) }
            },
        )
    }

    fun sendTurn(threadId: String, prompt: String): CommandEnvelope {
        val normalizedThreadId = threadId.trim()
        val normalizedPrompt = prompt.trim()
        require(normalizedThreadId.isNotEmpty()) { "thread is required" }
        require(normalizedPrompt.isNotEmpty()) { "prompt is required" }
        return command(
            type = "thread.send",
            payload = buildJsonObject {
                put("threadId", normalizedThreadId)
                put("prompt", normalizedPrompt)
            },
        )
    }

    fun steerTurn(threadId: String, turnId: String, prompt: String): CommandEnvelope {
        val normalizedThreadId = threadId.trim()
        val normalizedTurnId = turnId.trim()
        val normalizedPrompt = prompt.trim()
        require(normalizedThreadId.isNotEmpty()) { "thread is required" }
        require(normalizedTurnId.isNotEmpty()) { "turn is required" }
        require(normalizedPrompt.isNotEmpty()) { "prompt is required" }
        return command(
            type = "turn.steer",
            payload = buildJsonObject {
                put("threadId", normalizedThreadId)
                put("turnId", normalizedTurnId)
                put("prompt", normalizedPrompt)
            },
        )
    }

    fun interruptTurn(threadId: String, turnId: String): CommandEnvelope {
        val normalizedThreadId = threadId.trim()
        val normalizedTurnId = turnId.trim()
        require(normalizedThreadId.isNotEmpty()) { "thread is required" }
        require(normalizedTurnId.isNotEmpty()) { "turn is required" }
        return command(
            type = "turn.interrupt",
            payload = buildJsonObject {
                put("threadId", normalizedThreadId)
                put("turnId", normalizedTurnId)
            },
        )
    }

    private fun command(
        type: String,
        payload: kotlinx.serialization.json.JsonObject,
    ) = CommandEnvelope(
        protocolVersion = credential.protocolVersion,
        requestId = newIdentifier(),
        deviceId = credential.deviceId,
        idempotencyKey = newIdentifier(),
        type = type,
        payload = payload,
        sentAt = now(),
    )
}
