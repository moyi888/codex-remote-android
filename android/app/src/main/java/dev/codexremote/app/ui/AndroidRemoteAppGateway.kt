package dev.codexremote.app.ui

import android.content.Context
import dev.codexremote.app.bridge.BridgeHttpClient
import dev.codexremote.app.bridge.CommandOutbox
import dev.codexremote.app.bridge.CommandOutboxResult
import dev.codexremote.app.bridge.PendingCommandQueue
import dev.codexremote.app.bridge.SendOrQueueResult
import dev.codexremote.app.protocol.CommandEnvelope
import dev.codexremote.app.protocol.PairingInvitation
import dev.codexremote.app.protocol.Snapshot
import dev.codexremote.app.protocol.StoredBridgeConnection
import dev.codexremote.app.security.CredentialVault
import dev.codexremote.app.service.CodexRemoteService

class AndroidRemoteAppGateway(
    context: Context,
    private val httpClient: BridgeHttpClient = BridgeHttpClient(),
    private val vault: CredentialVault = CredentialVault.create(context),
    private val queue: PendingCommandQueue = PendingCommandQueue.create(context),
    private val clock: () -> String,
) : RemoteAppGateway {
    private val applicationContext = context.applicationContext

    override fun loadConnection(): StoredBridgeConnection? = vault.load()

    override fun exchange(
        invitation: PairingInvitation,
        device: DeviceIdentity,
    ): StoredBridgeConnection = StoredBridgeConnection(
        baseUrl = invitation.baseUrl,
        credential = httpClient.exchange(invitation, device.id, device.name),
    )

    override fun saveConnection(connection: StoredBridgeConnection) = vault.save(connection)

    override fun loadSnapshot(connection: StoredBridgeConnection): Snapshot = httpClient.snapshot(
        connection.baseUrl,
        connection.credential,
    )

    override fun send(
        connection: StoredBridgeConnection,
        command: CommandEnvelope,
    ): CommandDelivery {
        val result = CommandOutbox(queue, httpClient, connection, clock).sendOrQueue(command)
        val target = when (result) {
            is SendOrQueueResult.Attempted -> result.outcome
            is SendOrQueueResult.Blocked -> result.blocker
        }
        return when (target) {
            is CommandOutboxResult.Sent -> CommandDelivery.Sent
            is CommandOutboxResult.Queued -> CommandDelivery.Queued
            is CommandOutboxResult.AuthenticationRequired -> CommandDelivery.AuthenticationRequired
            is CommandOutboxResult.Rejected -> CommandDelivery.Rejected
        }
    }

    override fun startService() = CodexRemoteService.start(applicationContext)
}
