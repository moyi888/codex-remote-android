package dev.codexremote.app.ui

import dev.codexremote.app.protocol.ThreadState
import dev.codexremote.app.protocol.ThreadSummary

internal fun shouldRefreshThreadHistory(
    previous: ThreadSummary?,
    current: ThreadSummary?,
    hasHistory: Boolean,
): Boolean {
    if (current == null) return false
    if (!hasHistory) return true
    if (current.state == ThreadState.RUNNING) return true
    if (previous == null) return true
    return previous.updatedAt != current.updatedAt || previous.state != current.state
}
