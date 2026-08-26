package dev.codexremote.app.service

internal fun interface BackgroundExecutor {
    fun execute(task: () -> Unit)
}

internal class ConnectedCommandFlusher(
    private val executor: BackgroundExecutor,
    private val flush: () -> Unit,
) {
    fun onStatusChanged(status: ConnectionStatus) {
        if (status != ConnectionStatus.CONNECTED) return
        try {
            executor.execute {
                try {
                    flush()
                } catch (_: Exception) {
                    // The encrypted queue remains intact for the next reconnect.
                }
            }
        } catch (_: Exception) {
            // Service shutdown can reject a late flush task.
        }
    }
}
