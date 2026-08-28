package dev.codexremote.app.ui

import dev.codexremote.app.protocol.Capabilities
import dev.codexremote.app.protocol.ModelOption
import dev.codexremote.app.protocol.ProjectOption
import dev.codexremote.app.protocol.ReasoningOption
import dev.codexremote.app.protocol.Snapshot
import dev.codexremote.app.protocol.ConversationEntry
import dev.codexremote.app.protocol.ThreadHistory
import dev.codexremote.app.protocol.ThreadSource
import dev.codexremote.app.protocol.ThreadState
import dev.codexremote.app.protocol.ThreadSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteAppStateTest {
    @Test
    fun appendsPagedHistoryWithoutDroppingExistingEntries() {
        val initial = RemoteAppState().withHistory(
            "thread-1",
            ThreadHistory(listOf(ConversationEntry("1", "用户", "开始")), "next"),
        )
        val updated = initial.withHistory(
            "thread-1",
            ThreadHistory(listOf(ConversationEntry("2", "Codex", "完成")), null),
            append = true,
        )

        assertEquals(listOf("开始", "完成"), updated.histories["thread-1"]?.entries?.map { it.text })
        assertNull(updated.histories["thread-1"]?.nextCursor)
    }

    @Test
    fun appendingPagedHistoryRemovesDuplicateEntryIds() {
        val initial = RemoteAppState().withHistory(
            "thread-1",
            ThreadHistory(listOf(ConversationEntry("same", "Codex", "旧内容")), "next"),
        )

        val updated = initial.withHistory(
            "thread-1",
            ThreadHistory(
                listOf(
                    ConversationEntry("same", "Codex", "更新内容"),
                    ConversationEntry("new", "用户", "新消息"),
                ),
                null,
            ),
            append = true,
        )

        assertEquals(listOf("更新内容", "新消息"), updated.histories["thread-1"]?.entries?.map { it.text })
    }

    @Test
    fun snapshotOrdersThreadsByLatestUpdatedAt() {
        val state = RemoteAppState().withSnapshot(
            snapshot().copy(
                threads = listOf(
                    thread("old", "2026-08-29T09:00:00Z"),
                    thread("new", "2026-08-29T10:00:00Z"),
                ),
            ),
        )

        assertEquals(listOf("new", "old"), state.snapshot?.threads?.map { it.id })
    }
    @Test
    fun snapshotSelectsFirstAvailableProjectAndModel() {
        val state = RemoteAppState().withSnapshot(snapshot())

        assertEquals("project-1", state.newTask.projectId)
        assertEquals("model-a", state.newTask.modelId)
        assertNull(state.newTask.reasoningId)
    }

    @Test
    fun changingModelClearsIncompatibleReasoning() {
        val state = RemoteAppState()
            .withSnapshot(snapshot())
            .selectModel("model-a")
            .selectReasoning("high")
            .selectModel("model-b")

        assertEquals("model-b", state.newTask.modelId)
        assertNull(state.newTask.reasoningId)
    }

    @Test
    fun refreshingSnapshotRetainsOnlyServerBackedSelections() {
        val initial = RemoteAppState()
            .withSnapshot(snapshot())
            .selectProject("project-2")
            .selectModel("model-a")
            .selectReasoning("high")

        val refreshed = initial.withSnapshot(
            snapshot(
                projects = listOf(ProjectOption("project-1", "项目一")),
                models = listOf(
                    ModelOption(
                        "model-a",
                        "模型 A",
                        listOf(ReasoningOption("medium", "中")),
                    ),
                ),
            ),
        )

        assertEquals("project-1", refreshed.newTask.projectId)
        assertEquals("model-a", refreshed.newTask.modelId)
        assertNull(refreshed.newTask.reasoningId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsReasoningNotOfferedBySelectedModel() {
        RemoteAppState().withSnapshot(snapshot()).selectReasoning("ultra")
    }

    private fun snapshot(
        projects: List<ProjectOption> = listOf(
            ProjectOption("project-1", "项目一"),
            ProjectOption("project-2", "项目二"),
        ),
        models: List<ModelOption> = listOf(
            ModelOption(
                "model-a",
                "模型 A",
                listOf(ReasoningOption("medium", "中"), ReasoningOption("high", "高")),
            ),
            ModelOption(
                "model-b",
                "模型 B",
                listOf(ReasoningOption("low", "低")),
            ),
        ),
    ) = Snapshot(
        protocolVersion = 1,
        eventCursor = 10,
        capabilities = Capabilities(true, true, true, false, false),
        projects = projects,
        models = models,
        threads = emptyList(),
    )

    private fun thread(id: String, updatedAt: String) = ThreadSummary(
        id = id,
        title = id,
        projectId = "project-1",
        projectName = "项目一",
        source = ThreadSource.DESKTOP,
        state = ThreadState.IDLE,
        updatedAt = updatedAt,
    )
}
