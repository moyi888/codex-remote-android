package dev.codexremote.app.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class PairExchangeRequest(
    val token: String,
    val deviceId: String,
    val deviceName: String,
) {
    override fun toString(): String =
        "PairExchangeRequest(token=<redacted>, deviceId=$deviceId, deviceName=$deviceName)"
}

@Serializable
data class DeviceCredential(
    val protocolVersion: Int,
    val deviceId: String,
    val credential: String,
) {
    override fun toString(): String =
        "DeviceCredential(protocolVersion=$protocolVersion, deviceId=$deviceId, credential=<redacted>)"
}

@Serializable
data class Capabilities(
    val readThreads: Boolean,
    val startTask: Boolean,
    val sendTurn: Boolean,
    val steer: Boolean,
    val stopTurn: Boolean,
)

@Serializable
data class Snapshot(
    val protocolVersion: Int,
    val capabilities: Capabilities,
    val projects: List<ProjectOption>,
    val models: List<ModelOption>,
    val threads: List<ThreadSummary>,
)

@Serializable
data class CommandResponse(
    val status: String,
    val result: JsonElement? = null,
)

@Serializable
enum class ThreadSource {
    @SerialName("desktop")
    DESKTOP,

    @SerialName("app_server")
    APP_SERVER,
}

@Serializable
enum class ThreadState {
    @SerialName("idle")
    IDLE,

    @SerialName("running")
    RUNNING,

    @SerialName("completed")
    COMPLETED,

    @SerialName("failed")
    FAILED,

    @SerialName("disconnected")
    DISCONNECTED,
}

@Serializable
data class Attention(
    val category: String,
    val site: String = "",
    val confidence: Double,
    val detectedAt: String,
)

@Serializable
data class ThreadSummary(
    val id: String,
    val title: String,
    val projectId: String,
    val projectName: String,
    val source: ThreadSource,
    val state: ThreadState,
    val updatedAt: String,
    val attention: Attention? = null,
)

@Serializable
data class ProjectOption(
    val id: String,
    val displayName: String,
)

@Serializable
data class ReasoningOption(
    val id: String,
    val displayName: String,
)

@Serializable
data class ModelOption(
    val id: String,
    val displayName: String,
    val reasoningOptions: List<ReasoningOption>,
)

@Serializable
data class EventEnvelope<T>(
    val protocolVersion: Int,
    val eventCursor: Long,
    val type: String,
    val payload: T,
)

@Serializable
data class CommandEnvelope(
    val protocolVersion: Int,
    val requestId: String,
    val deviceId: String,
    val idempotencyKey: String,
    val type: String,
    val payload: JsonObject,
    val sentAt: String,
)
