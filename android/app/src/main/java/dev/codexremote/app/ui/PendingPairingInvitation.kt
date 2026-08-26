package dev.codexremote.app.ui

class PendingPairingInvitation {
    private var pending: String? = null

    fun offer(invitation: String, busy: Boolean): String? {
        if (!busy) {
            pending = null
            return invitation
        }
        pending = invitation
        return null
    }

    fun takeAfterLoad(): String? = pending.also { pending = null }
}
