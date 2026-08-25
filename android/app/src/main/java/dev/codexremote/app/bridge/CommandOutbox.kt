package dev.codexremote.app.bridge

import dev.codexremote.app.protocol.CommandEnvelope
import dev.codexremote.app.protocol.CommandResponse
import dev.codexremote.app.protocol.StoredBridgeConnection
import java.io.IOException

sealed interface CommandOutboxResult {
    data class Sent(
        val command: QueuedCommand,
        val response: CommandResponse,
    ) : CommandOutboxResult

    data class Queued(
        val command: QueuedCommand,
        val error: Exception,
    ) : CommandOutboxResult
}

class CommandOutbox(
    private val queue: PendingCommandQueue,
    private val httpClient: BridgeHttpClient,
    private val connection: StoredBridgeConnection,
    private val clock: () -> String,
) {
    fun sendOrQueue(command: CommandEnvelope): CommandOutboxResult {
        queue.enqueue(command)
        val queued = queue.list().first {
            it.command.deviceId == command.deviceId &&
                it.command.idempotencyKey == command.idempotencyKey
        }
        return try {
            val response = send(queued.command)
            queue.removeCompleted(queued.command.idempotencyKey)
            CommandOutboxResult.Sent(queued, response)
        } catch (error: BridgeApiException) {
            retainAfterFailure(queued, error)
        } catch (error: IOException) {
            retainAfterFailure(queued, error)
        }
    }

    fun flush(): List<CommandOutboxResult> {
        val results = mutableListOf<CommandOutboxResult>()
        for (queued in queue.list()) {
            queue.markAttempt(queued.command.idempotencyKey, clock())
            val attempted = current(queued)
            val result = try {
                val response = send(attempted.command)
                queue.removeCompleted(attempted.command.idempotencyKey)
                CommandOutboxResult.Sent(attempted, response)
            } catch (error: BridgeApiException) {
                CommandOutboxResult.Queued(attempted, error)
            } catch (error: IOException) {
                CommandOutboxResult.Queued(attempted, error)
            }
            results += result
            if (result is CommandOutboxResult.Queued) {
                break
            }
        }
        return results.toList()
    }

    private fun send(command: CommandEnvelope): CommandResponse = httpClient.sendCommand(
        connection.baseUrl,
        connection.credential,
        command,
    )

    private fun retainAfterFailure(
        queued: QueuedCommand,
        error: Exception,
    ): CommandOutboxResult.Queued {
        queue.markAttempt(queued.command.idempotencyKey, clock())
        return CommandOutboxResult.Queued(current(queued), error)
    }

    private fun current(queued: QueuedCommand): QueuedCommand = queue.list().firstOrNull {
        it.command.deviceId == queued.command.deviceId &&
            it.command.idempotencyKey == queued.command.idempotencyKey
    } ?: queued
}
