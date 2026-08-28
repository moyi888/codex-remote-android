package dev.codexremote.app.ui

import dev.codexremote.app.protocol.ThreadSource
import dev.codexremote.app.protocol.ThreadState
import dev.codexremote.app.protocol.ThreadSummary
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThreadListKeyTest {
    @Test
    fun duplicateThreadIdsStillProduceDistinctListKeys() {
        val first = threadListItemKey(0, thread("same"))
        val second = threadListItemKey(1, thread("same"))

        assertNotEquals(first, second)
    }

    private fun thread(id: String) = ThreadSummary(
        id = id,
        title = "title",
        projectId = "project",
        projectName = "project",
        source = ThreadSource.APP_SERVER,
        state = ThreadState.IDLE,
        updatedAt = "2026-08-28T00:00:00Z",
    )
}
