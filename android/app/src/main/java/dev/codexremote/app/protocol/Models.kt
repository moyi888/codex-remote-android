package dev.codexremote.app.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
data class StoredBridgeConnection(
    val baseUrl: String,
    val credential: DeviceCredential,
) {
    override fun toString(): String =
        "StoredBridgeConnection(baseUrl=$baseUrl, credential=<redacted>)"
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
    val eventCursor: Long,
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
    val activeTurnId: String? = null,
    val attention: Attention? = null,
)

data class ConversationEntry(
    val id: String,
    val kind: String,
    val text: String,
    val status: String? = null,
)

data class ThreadHistory(
    val entries: List<ConversationEntry> = emptyList(),
    val nextCursor: String? = null,
)

/** Parses app-server history without coupling the client to every future item type. */
object ThreadHistoryParser {
    fun fromReadResponse(root: JsonObject, threadId: String): ThreadHistory {
        val thread = root["thread"]?.jsonObjectOrNull() ?: root
        val turns = thread["turns"]?.jsonArrayOrNull() ?: root["turns"]?.jsonArrayOrNull() ?: return ThreadHistory()
        return parseTurns(turns, threadId, root["nextCursor"]?.stringOrNull())
    }

    fun fromTurnsResponse(root: JsonObject, threadId: String): ThreadHistory {
        val container = root["turns"] ?: root["data"] ?: root["items"]
        val turns = container?.jsonArrayOrNull()
            ?: container?.jsonObjectOrNull()?.let { nested ->
                nested["turns"]?.jsonArrayOrNull()
                    ?: nested["data"]?.jsonArrayOrNull()
                    ?: nested["items"]?.jsonArrayOrNull()
            }
        val nextCursor = root["nextCursor"]?.stringOrNull()
            ?: container?.jsonObjectOrNull()?.get("nextCursor")?.stringOrNull()
        return turns?.let { parseTurns(it, threadId, nextCursor) }
            ?: ThreadHistory(nextCursor = nextCursor)
    }

    private fun parseTurns(turns: List<JsonElement>, threadId: String, nextCursor: String?): ThreadHistory {
        val entries = turns.asReversed().flatMapIndexed { turnIndex, element ->
            val turn = element.jsonObjectOrNull() ?: return@flatMapIndexed emptyList()
            val pageKey = nextCursor?.takeIf { it.isNotBlank() } ?: "initial"
            val turnId = turn["id"]?.stringOrNull() ?: "$threadId-turn-$pageKey-$turnIndex"
            val items = turn["items"]?.jsonArrayOrNull().orEmpty()
            if (items.isEmpty()) {
                listOfNotNull(
                    turn["input"]?.let { entry(turnId, "用户", it) },
                    turn["status"]?.let { entry("$turnId-status", "状态", it) },
                )
            } else {
                items.mapIndexedNotNull { index, item -> itemEntry(item, "$turnId-item-$index") }
            }
        }
        return ThreadHistory(entries.distinctBy { it.id }, nextCursor)
    }

    private fun itemEntry(item: JsonElement, fallbackId: String): ConversationEntry? {
        val objectValue = item.jsonObjectOrNull() ?: return null
        val type = objectValue["type"]?.stringOrNull() ?: "item"
        val label = when {
            type.contains("user", ignoreCase = true) -> "用户"
            type.contains("agent", ignoreCase = true) || type.contains("message", ignoreCase = true) -> "Codex"
            else -> "工具 · $type"
        }
        val text = objectValue["text"]?.stringOrNull()
            ?: objectValue["content"]?.flattenText()
            ?: objectValue["command"]?.stringOrNull()
            ?: objectValue["name"]?.stringOrNull()
            ?: objectValue["status"]?.stringOrNull()
            ?: return null
        return ConversationEntry(objectValue["id"]?.stringOrNull() ?: fallbackId, label, text, objectValue["status"]?.stringOrNull())
    }

    private fun entry(id: String, kind: String, value: JsonElement): ConversationEntry? {
        val text = value.flattenText() ?: return null
        return ConversationEntry(id, kind, text)
    }

    private fun JsonElement.flattenText(): String? = when (this) {
        is JsonPrimitive -> content.takeIf { it.isNotBlank() }
        else -> when (val objectValue = jsonObjectOrNull()) {
            null -> jsonArrayOrNull()?.mapNotNull { it.flattenText() }?.joinToString("\n")?.takeIf { it.isNotBlank() }
            else -> listOf("text", "value", "content", "message", "input").asSequence()
                .mapNotNull { objectValue[it]?.flattenText() }
                .joinToString("\n")
                .takeIf { it.isNotBlank() }
        }
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
    private fun JsonElement.jsonArrayOrNull() = this as? kotlinx.serialization.json.JsonArray
    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.content
}

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
