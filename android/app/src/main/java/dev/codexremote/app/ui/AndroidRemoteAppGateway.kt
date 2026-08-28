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
import dev.codexremote.app.protocol.ThreadHistory
import dev.codexremote.app.protocol.ThreadHistoryParser
import dev.codexremote.app.security.CredentialVault
import dev.codexremote.app.service.CodexRemoteService
import dev.codexremote.app.diagnostics.DiagnosticLogStore
import dev.codexremote.app.diagnostics.DiagnosticLogs

class AndroidRemoteAppGateway(
    context: Context,
    private val logs: DiagnosticLogStore = DiagnosticLogs.instance,
    private val httpClient: BridgeHttpClient = BridgeHttpClient(logs = logs),
    private val vault: CredentialVault = CredentialVault.create(context),
    private val queue: PendingCommandQueue = PendingCommandQueue.create(context),
    private val clock: () -> String,
) : RemoteAppGateway {
    private val applicationContext = context.applicationContext

    override fun loadConnection(): StoredBridgeConnection? = vault.load()

    override fun exchange(
        invitation: PairingInvitation,
        device: DeviceIdentity,
    ): StoredBridgeConnection {
        logs.info("pair", "exchange started device=${device.id.take(8)}")
        return try {
            StoredBridgeConnection(
                baseUrl = invitation.baseUrl,
                credential = httpClient.exchange(invitation, device.id, device.name),
            ).also { logs.info("pair", "exchange succeeded") }
        } catch (error: Exception) {
            logs.error("pair", "exchange failed", error)
            throw error
        }
    }

    override fun saveConnection(connection: StoredBridgeConnection) = vault.save(connection)

    override fun loadSnapshot(connection: StoredBridgeConnection): Snapshot {
        logs.info("snapshot", "load started")
        return try {
            httpClient.snapshot(connection.baseUrl, connection.credential).also {
                logs.info("snapshot", "load succeeded threads=${it.threads.size} projects=${it.projects.size}")
            }
        } catch (error: Exception) {
            logs.error("snapshot", "load failed", error)
            throw error
        }
    }

    override fun loadThreadHistory(
        connection: StoredBridgeConnection,
        threadId: String,
        cursor: String?,
    ): ThreadHistory {
        logs.info("history", "load started thread=${threadId.take(8)} cursor=${cursor != null}")
        return if (cursor == null) {
            ThreadHistoryParser.fromReadResponse(
                httpClient.threadRead(connection.baseUrl, connection.credential, threadId),
                threadId,
            )
        } else {
            ThreadHistoryParser.fromTurnsResponse(
                httpClient.threadTurns(connection.baseUrl, connection.credential, threadId, cursor),
                threadId,
            )
        }
    }

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
