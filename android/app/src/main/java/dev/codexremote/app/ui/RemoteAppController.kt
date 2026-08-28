package dev.codexremote.app.ui

import dev.codexremote.app.protocol.CommandEnvelope
import dev.codexremote.app.protocol.PairingInvitation
import dev.codexremote.app.protocol.Snapshot
import dev.codexremote.app.protocol.StoredBridgeConnection
import dev.codexremote.app.protocol.ThreadHistory
import dev.codexremote.app.diagnostics.DiagnosticLogStore
import dev.codexremote.app.diagnostics.DiagnosticLogs

data class DeviceIdentity(
    val id: String,
    val name: String,
)

sealed interface LoadResult {
    data object Unpaired : LoadResult

    data class Ready(val state: RemoteAppState) : LoadResult

    data class Failed(val failure: PairingFailureMessage) : LoadResult {
        val message: String get() = failure.text
    }
}

enum class CommandDelivery {
    Sent,
    Queued,
    AuthenticationRequired,
    Rejected,
}

interface RemoteAppGateway {
    fun loadConnection(): StoredBridgeConnection?

    fun exchange(
        invitation: PairingInvitation,
        device: DeviceIdentity,
    ): StoredBridgeConnection

    fun saveConnection(connection: StoredBridgeConnection)

    fun loadSnapshot(connection: StoredBridgeConnection): Snapshot

    fun loadThreadHistory(connection: StoredBridgeConnection, threadId: String, cursor: String? = null): ThreadHistory

    fun send(
        connection: StoredBridgeConnection,
        command: CommandEnvelope,
    ): CommandDelivery

    fun startService()
}

class RemoteAppController(
    private val gateway: RemoteAppGateway,
    private val device: DeviceIdentity,
    private val newIdentifier: () -> String,
    private val now: () -> String,
    private val logs: DiagnosticLogStore = DiagnosticLogs.instance,
) {
    private var connection: StoredBridgeConnection? = null

    fun resume(): LoadResult = safelyLoad(PairingOperation.RESUME) {
        logs.info("connection", "resume started")
        val stored = gateway.loadConnection() ?: return LoadResult.Unpaired
        load(stored)
    }

    fun pair(rawInvitation: String): LoadResult = safelyLoad(PairingOperation.PAIR) {
        logs.info("pair", "invitation received length=${rawInvitation.length}")
        val invitation = PairingInvitation.parse(rawInvitation.trim())
        logs.info("pair", "invitation parsed baseUrl=${invitation.baseUrl}")
        val paired = gateway.exchange(invitation, device)
        gateway.saveConnection(paired)
        logs.info("pair", "credential saved; loading snapshot")
        load(paired)
    }

    fun startTask(
        projectId: String,
        prompt: String,
        modelId: String?,
        reasoningId: String?,
    ): CommandDelivery = send { factory ->
        factory.startTask(projectId, prompt, modelId, reasoningId)
    }

    fun sendTurn(threadId: String, prompt: String): CommandDelivery = send { factory ->
        factory.sendTurn(threadId, prompt)
    }

    fun steerTurn(threadId: String, turnId: String, prompt: String): CommandDelivery = send { factory ->
        factory.steerTurn(threadId, turnId, prompt)
    }

    fun interruptTurn(threadId: String, turnId: String): CommandDelivery = send { factory ->
        factory.interruptTurn(threadId, turnId)
    }

    fun loadThreadHistory(threadId: String, cursor: String? = null): ThreadHistory {
        val current = connection ?: throw IllegalStateException("not paired")
        return gateway.loadThreadHistory(current, threadId, cursor)
    }

    /** Refreshes the snapshot without toggling the foreground service or UI load state. */
    fun refreshSnapshot(): Snapshot {
        val current = connection ?: throw IllegalStateException("not paired")
        logs.debug("connection", "snapshot refresh started")
        return gateway.loadSnapshot(current).also {
            logs.debug("connection", "snapshot refresh succeeded threads=${it.threads.size}")
        }
    }

    private fun load(value: StoredBridgeConnection): LoadResult {
        val snapshot = gateway.loadSnapshot(value)
        connection = value
        gateway.startService()
        return LoadResult.Ready(RemoteAppState().withSnapshot(snapshot))
    }

    private fun send(create: (CommandFactory) -> CommandEnvelope): CommandDelivery {
        val current = connection ?: return CommandDelivery.AuthenticationRequired
        return try {
            gateway.send(
                current,
                create(CommandFactory(current.credential, newIdentifier, now)),
            )
        } catch (_: Exception) {
            CommandDelivery.Rejected
        }
    }

    private inline fun safelyLoad(
        operation: PairingOperation,
        action: () -> LoadResult,
    ): LoadResult = try {
        action()
    } catch (error: Exception) {
        logs.error("connection", "$operation failed", error)
        LoadResult.Failed(PairingFailureMessage.from(error, operation))
    }
}
