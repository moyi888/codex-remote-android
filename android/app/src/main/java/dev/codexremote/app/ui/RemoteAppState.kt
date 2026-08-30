package dev.codexremote.app.ui

import dev.codexremote.app.protocol.ModelOption
import dev.codexremote.app.protocol.Snapshot
import dev.codexremote.app.protocol.ThreadHistory
import java.time.Instant

data class NewTaskState(
    val projectId: String? = null,
    val modelId: String? = null,
    val reasoningId: String? = null,
)

data class RemoteAppState(
    val snapshot: Snapshot? = null,
    val newTask: NewTaskState = NewTaskState(),
    val histories: Map<String, ThreadHistory> = emptyMap(),
    val pendingUserMessages: Map<String, List<dev.codexremote.app.protocol.ConversationEntry>> = emptyMap(),
) {
    fun withSnapshot(value: Snapshot): RemoteAppState {
        val orderedSnapshot = value.copy(
            threads = value.threads.sortedWith(
                compareByDescending<dev.codexremote.app.protocol.ThreadSummary> {
                    runCatching { Instant.parse(it.updatedAt) }.getOrNull() ?: Instant.MIN
                }.thenBy { it.id },
            ),
        )
        val projectId = newTask.projectId.takeIf { selected ->
            orderedSnapshot.projects.any { it.id == selected }
        } ?: orderedSnapshot.projects.firstOrNull()?.id
        val modelId = newTask.modelId.takeIf { selected ->
            orderedSnapshot.models.any { it.id == selected }
        } ?: orderedSnapshot.models.firstOrNull()?.id
        val model = orderedSnapshot.models.firstOrNull { it.id == modelId }
        val reasoningId = compatibleReasoning(model, newTask.reasoningId)
        return copy(
            snapshot = orderedSnapshot,
            newTask = NewTaskState(projectId, modelId, reasoningId),
        )
    }

    fun withHistory(threadId: String, history: ThreadHistory, append: Boolean = false): RemoteAppState {
        val previous = histories[threadId]
        val merged = if (append && previous != null) {
            val entriesById = LinkedHashMap<String, dev.codexremote.app.protocol.ConversationEntry>()
            (history.entries + previous.entries).forEach { entry -> entriesById.putIfAbsent(entry.id, entry) }
            history.copy(entries = entriesById.values.toList())
        } else history
        val pending = pendingUserMessages[threadId].orEmpty()
        val confirmedTexts = merged.entries.filter { it.kind == "用户" }.map { it.text }.toSet()
        val remainingPending = pending.filterNot { it.text in confirmedTexts }
        val knownIds = merged.entries.map { it.id }.toSet()
        val visible = merged.copy(entries = merged.entries + remainingPending.filter { it.id !in knownIds })
        return copy(
            histories = histories + (threadId to visible),
            pendingUserMessages = pendingUserMessages + (threadId to remainingPending),
        )
    }

    fun withPendingUserMessage(threadId: String, entry: dev.codexremote.app.protocol.ConversationEntry): RemoteAppState {
        val pending = pendingUserMessages[threadId].orEmpty()
        val existing = histories[threadId] ?: ThreadHistory()
        if (existing.entries.any { it.id == entry.id || (it.kind == "用户" && it.text == entry.text) }) return this
        val nextPending = (pending + entry).distinctBy { it.id }
        return copy(
            histories = histories + (threadId to existing.copy(entries = existing.entries + entry)),
            pendingUserMessages = pendingUserMessages + (threadId to nextPending),
        )
    }

    fun selectProject(projectId: String): RemoteAppState {
        require(snapshot?.projects?.any { it.id == projectId } == true) {
            "project is not available"
        }
        return copy(newTask = newTask.copy(projectId = projectId))
    }

    fun selectModel(modelId: String): RemoteAppState {
        val model = snapshot?.models?.firstOrNull { it.id == modelId }
            ?: throw IllegalArgumentException("model is not available")
        return copy(
            newTask = newTask.copy(
                modelId = modelId,
                reasoningId = compatibleReasoning(model, newTask.reasoningId),
            ),
        )
    }

    fun selectReasoning(reasoningId: String?): RemoteAppState {
        val model = selectedModel()
            ?: throw IllegalArgumentException("select a model before reasoning")
        require(reasoningId == null || model.reasoningOptions.any { it.id == reasoningId }) {
            "reasoning is not available for the selected model"
        }
        return copy(newTask = newTask.copy(reasoningId = reasoningId))
    }

    private fun selectedModel(): ModelOption? = snapshot?.models?.firstOrNull {
        it.id == newTask.modelId
    }

    private fun compatibleReasoning(model: ModelOption?, reasoningId: String?): String? =
        reasoningId?.takeIf { selected ->
            model?.reasoningOptions?.any { it.id == selected } == true
        }
}

internal class ActiveThreadTracker {
    private var threadId: String? = null

    fun open(value: String) { threadId = value }

    fun close() { threadId = null }

    fun current(): String? = threadId
}
