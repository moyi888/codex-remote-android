package dev.codexremote.app.bridge

import dev.codexremote.app.protocol.CommandEnvelope
import dev.codexremote.app.protocol.CommandResponse
import dev.codexremote.app.protocol.StoredBridgeConnection
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

sealed interface CommandOutboxResult {
    val command: QueuedCommand

    data class Sent(
        override val command: QueuedCommand,
        val response: CommandResponse,
    ) : CommandOutboxResult

    data class Queued(
        override val command: QueuedCommand,
        val error: Exception,
    ) : CommandOutboxResult

    data class AuthenticationRequired(
        override val command: QueuedCommand,
        val statusCode: Int,
    ) : CommandOutboxResult

    data class Rejected(
        override val command: QueuedCommand,
        val statusCode: Int,
    ) : CommandOutboxResult
}

data class FlushResult(val outcomes: List<CommandOutboxResult>)

class CommandOutbox(
    private val queue: PendingCommandQueue,
    private val httpClient: BridgeHttpClient,
    private val connection: StoredBridgeConnection,
    private val clock: () -> String,
) {
    fun sendOrQueue(command: CommandEnvelope): CommandOutboxResult = withDeviceLock(command.deviceId) {
        queue.enqueue(command)
        val outcomes = drain(command.deviceId)
        outcomes.firstOrNull { it.command.matches(command) }
            ?: blockedResult(command, outcomes.last())
    }

    fun flush(): FlushResult = withDeviceLock(connection.credential.deviceId) {
        FlushResult(drain(connection.credential.deviceId))
    }

    private fun drain(deviceId: String): List<CommandOutboxResult> {
        val outcomes = mutableListOf<CommandOutboxResult>()
        while (true) {
            val queued = queue.list().firstOrNull { it.command.deviceId == deviceId }
                ?: break
            queue.markAttempt(
                queued.command.deviceId,
                queued.command.idempotencyKey,
                clock(),
            )
            val attempted = current(queued)
            val outcome = try {
                val response = send(attempted.command)
                queue.removeCompleted(
                    attempted.command.deviceId,
                    attempted.command.idempotencyKey,
                )
                CommandOutboxResult.Sent(attempted, response)
            } catch (error: BridgeApiException) {
                classifyHttpFailure(attempted, error)
            } catch (error: IOException) {
                CommandOutboxResult.Queued(attempted, error)
            }
            outcomes += outcome
            when (outcome) {
                is CommandOutboxResult.Sent -> Unit
                is CommandOutboxResult.Rejected -> {
                    queue.removeCompleted(
                        attempted.command.deviceId,
                        attempted.command.idempotencyKey,
                    )
                }
                is CommandOutboxResult.Queued,
                is CommandOutboxResult.AuthenticationRequired,
                -> break
            }
        }
        return outcomes.toList()
    }

    private fun classifyHttpFailure(
        attempted: QueuedCommand,
        error: BridgeApiException,
    ): CommandOutboxResult = when {
        error.statusCode == 401 || error.statusCode == 403 ->
            CommandOutboxResult.AuthenticationRequired(attempted, error.statusCode)
        error.statusCode in TERMINAL_CLIENT_ERRORS ->
            CommandOutboxResult.Rejected(attempted, error.statusCode)
        else -> CommandOutboxResult.Queued(attempted, error)
    }

    private fun blockedResult(
        command: CommandEnvelope,
        blockingOutcome: CommandOutboxResult,
    ): CommandOutboxResult {
        val queued = queue.list().first { it.matches(command) }
        return when (blockingOutcome) {
            is CommandOutboxResult.AuthenticationRequired ->
                CommandOutboxResult.AuthenticationRequired(queued, blockingOutcome.statusCode)
            is CommandOutboxResult.Queued -> CommandOutboxResult.Queued(queued, blockingOutcome.error)
            is CommandOutboxResult.Sent,
            is CommandOutboxResult.Rejected,
            -> error("Command drain ended before reaching the newly queued command")
        }
    }

    private fun send(command: CommandEnvelope): CommandResponse = httpClient.sendCommand(
        connection.baseUrl,
        connection.credential,
        command,
    )

    private fun current(queued: QueuedCommand): QueuedCommand = queue.list().firstOrNull {
        it.command.deviceId == queued.command.deviceId &&
            it.command.idempotencyKey == queued.command.idempotencyKey
    } ?: queued

    private fun QueuedCommand.matches(command: CommandEnvelope): Boolean =
        this.command.deviceId == command.deviceId &&
            this.command.idempotencyKey == command.idempotencyKey

    private inline fun <T> withDeviceLock(deviceId: String, action: () -> T): T =
        synchronized(DEVICE_LOCKS.computeIfAbsent(deviceId) { Any() }, action)

    private companion object {
        val TERMINAL_CLIENT_ERRORS = (400..499).toSet() - setOf(401, 403, 408, 409, 425, 429)
        val DEVICE_LOCKS = ConcurrentHashMap<String, Any>()
    }
}
