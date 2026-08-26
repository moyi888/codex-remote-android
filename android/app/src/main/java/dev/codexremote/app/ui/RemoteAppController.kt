package dev.codexremote.app.ui

import dev.codexremote.app.protocol.CommandEnvelope
import dev.codexremote.app.protocol.PairingInvitation
import dev.codexremote.app.protocol.Snapshot
import dev.codexremote.app.protocol.StoredBridgeConnection

data class DeviceIdentity(
    val id: String,
    val name: String,
)

sealed interface LoadResult {
    data object Unpaired : LoadResult

    data class Ready(val state: RemoteAppState) : LoadResult

    data class Failed(val message: String) : LoadResult
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
) {
    private var connection: StoredBridgeConnection? = null

    fun resume(): LoadResult = safelyLoad {
        val stored = gateway.loadConnection() ?: return LoadResult.Unpaired
        load(stored)
    }

    fun pair(rawInvitation: String): LoadResult = safelyLoad {
        val invitation = PairingInvitation.parse(rawInvitation.trim())
        val paired = gateway.exchange(invitation, device)
        gateway.saveConnection(paired)
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

    private inline fun safelyLoad(action: () -> LoadResult): LoadResult = try {
        action()
    } catch (_: Exception) {
        LoadResult.Failed("无法连接到 Codex Bridge，请检查配对信息和 Tailscale 网络。")
    }
}
