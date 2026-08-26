package dev.codexremote.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectedCommandFlusherTest {
    @Test
    fun flushesInBackgroundOnlyAfterBridgeIsConnected() {
        val tasks = mutableListOf<() -> Unit>()
        var flushCalls = 0
        val flusher = ConnectedCommandFlusher(
            executor = BackgroundExecutor(tasks::add),
            flush = { flushCalls += 1 },
        )

        flusher.onStatusChanged(ConnectionStatus.CONNECTING)
        flusher.onStatusChanged(ConnectionStatus.RETRYING)
        assertEquals(0, tasks.size)

        flusher.onStatusChanged(ConnectionStatus.CONNECTED)
        assertEquals(1, tasks.size)
        assertEquals(0, flushCalls)

        tasks.single().invoke()
        assertEquals(1, flushCalls)
    }

    @Test
    fun backgroundFlushFailureDoesNotEscape() {
        var task: (() -> Unit)? = null
        val flusher = ConnectedCommandFlusher(
            executor = BackgroundExecutor { task = it },
            flush = { error("offline") },
        )

        flusher.onStatusChanged(ConnectionStatus.CONNECTED)

        requireNotNull(task).invoke()
    }
}
