package dev.codexremote.app.ui

object NotificationPermissionPolicy {
    fun shouldRequest(sdkInt: Int, granted: Boolean): Boolean = sdkInt >= 33 && !granted
}
