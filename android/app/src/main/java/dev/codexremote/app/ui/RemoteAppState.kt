package dev.codexremote.app.ui

import dev.codexremote.app.protocol.ModelOption
import dev.codexremote.app.protocol.Snapshot

data class NewTaskState(
    val projectId: String? = null,
    val modelId: String? = null,
    val reasoningId: String? = null,
)

data class RemoteAppState(
    val snapshot: Snapshot? = null,
    val newTask: NewTaskState = NewTaskState(),
) {
    fun withSnapshot(value: Snapshot): RemoteAppState {
        val projectId = newTask.projectId.takeIf { selected ->
            value.projects.any { it.id == selected }
        } ?: value.projects.firstOrNull()?.id
        val modelId = newTask.modelId.takeIf { selected ->
            value.models.any { it.id == selected }
        } ?: value.models.firstOrNull()?.id
        val model = value.models.firstOrNull { it.id == modelId }
        val reasoningId = compatibleReasoning(model, newTask.reasoningId)
        return copy(
            snapshot = value,
            newTask = NewTaskState(projectId, modelId, reasoningId),
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
