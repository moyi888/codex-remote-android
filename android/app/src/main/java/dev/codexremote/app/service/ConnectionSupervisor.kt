package dev.codexremote.app.service

internal enum class ConnectionStatus {
    STOPPED,
    WAITING_FOR_NETWORK,
    CONNECTING,
    CONNECTED,
    RETRYING,
}

internal interface ConnectionSession {
    fun connect()

    fun close()

    fun dispose()
}

internal interface ConnectionSessionCallbacks {
    fun onConnected()

    fun onDisconnected()

    fun onFailure()
}

internal fun interface ConnectionSessionFactory {
    fun create(callbacks: ConnectionSessionCallbacks): ConnectionSession
}

internal fun interface ScheduledRetry {
    fun cancel()
}

internal fun interface RetryScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): ScheduledRetry
}

internal fun interface ConnectionStatusListener {
    fun onStatusChanged(status: ConnectionStatus)
}

internal class ConnectionSupervisor(
    private val sessionFactory: ConnectionSessionFactory,
    private val scheduler: RetryScheduler,
    private val statusListener: ConnectionStatusListener,
) {
    private data class ActiveSession(
        val generation: Long,
        val value: ConnectionSession,
    )

    private data class RetryPlan(
        val generation: Long,
        val delayMillis: Long,
        val status: StatusUpdate,
    )

    private data class StatusUpdate(val version: Long, val status: ConnectionStatus)

    private val lock = Any()
    private val statusDeliveryLock = Any()
    private var running = false
    private var networkAvailable = false
    private var generation = 0L
    private var retryIndex = 0
    private var connectingGeneration: Long? = null
    private var activeSession: ActiveSession? = null
    private var retryPending = false
    private var scheduledRetry: ScheduledRetry? = null
    private var cleanupCount = 0
    private var reconnectAfterCleanup = false
    private var statusVersion = 0L
    private var currentStatus = ConnectionStatus.STOPPED

    fun start(networkAvailable: Boolean) {
        val startState = synchronized(lock) {
            if (running) return
            running = true
            this.networkAvailable = networkAvailable
            networkAvailable to if (networkAvailable) null else reserveStatusLocked(
                ConnectionStatus.WAITING_FOR_NETWORK,
            )
        }
        if (startState.first) beginConnect() else publish(requireNotNull(startState.second))
    }

    fun onNetworkAvailable() {
        val cancelled: ScheduledRetry?
        val shouldConnect: Boolean
        synchronized(lock) {
            if (!running || networkAvailable) return
            networkAvailable = true
            retryIndex = 0
            generation += 1
            retryPending = false
            cancelled = scheduledRetry
            scheduledRetry = null
            shouldConnect = activeSession == null && connectingGeneration == null && cleanupCount == 0
        }
        cancel(cancelled)
        if (shouldConnect) beginConnect()
    }

    fun onNetworkUnavailable() {
        val closing: ConnectionSession?
        val cancelled: ScheduledRetry?
        val status: StatusUpdate
        synchronized(lock) {
            if (!running || !networkAvailable) return
            networkAvailable = false
            generation += 1
            connectingGeneration = null
            retryPending = false
            cancelled = scheduledRetry
            scheduledRetry = null
            closing = activeSession?.value
            if (closing != null) cleanupCount += 1
            activeSession = null
            reconnectAfterCleanup = false
            status = reserveStatusLocked(ConnectionStatus.WAITING_FOR_NETWORK)
        }
        cancel(cancelled)
        closeAndDispose(closing)
        if (closing != null) finishCleanup()
        publish(status)
    }

    fun stop() {
        val closing: ConnectionSession?
        val cancelled: ScheduledRetry?
        val status: StatusUpdate
        synchronized(lock) {
            if (!running) return
            running = false
            networkAvailable = false
            generation += 1
            connectingGeneration = null
            retryPending = false
            cancelled = scheduledRetry
            scheduledRetry = null
            closing = activeSession?.value
            if (closing != null) cleanupCount += 1
            activeSession = null
            reconnectAfterCleanup = false
            status = reserveStatusLocked(ConnectionStatus.STOPPED)
        }
        cancel(cancelled)
        closeAndDispose(closing)
        if (closing != null) finishCleanup()
        publish(status)
    }

    fun isRunning(): Boolean = synchronized(lock) { running }

    private fun beginConnect() {
        val connectionGeneration = synchronized(lock) {
            if (
                !running ||
                !networkAvailable ||
                activeSession != null ||
                connectingGeneration != null ||
                retryPending ||
                cleanupCount > 0
            ) {
                return
            }
            val reservedGeneration = ++generation
            connectingGeneration = reservedGeneration
            reservedGeneration to reserveStatusLocked(ConnectionStatus.CONNECTING)
        }
        val generationValue = connectionGeneration.first
        publish(connectionGeneration.second)
        val created = try {
            sessionFactory.create(callbacks(generationValue))
        } catch (_: Exception) {
            scheduleAfterConnectionFailure(generationValue)
            return
        }
        val accepted = synchronized(lock) {
            if (
                running &&
                networkAvailable &&
                connectingGeneration == generationValue &&
                generation == generationValue
            ) {
                connectingGeneration = null
                activeSession = ActiveSession(generationValue, created)
                true
            } else {
                cleanupCount += 1
                false
            }
        }
        if (!accepted) {
            closeAndDispose(created)
            finishCleanup()
            return
        }
        try {
            created.connect()
        } catch (_: Exception) {
            onSessionEnded(generationValue)
        }
    }

    private fun callbacks(connectionGeneration: Long) = object : ConnectionSessionCallbacks {
        override fun onConnected() {
            val status = synchronized(lock) {
                val current = activeSession
                if (!running || current?.generation != connectionGeneration) return@synchronized null
                retryIndex = 0
                reserveStatusLocked(ConnectionStatus.CONNECTED)
            }
            if (status != null) publish(status)
        }

        override fun onDisconnected() = onSessionEnded(connectionGeneration)

        override fun onFailure() = onSessionEnded(connectionGeneration)
    }

    private fun onSessionEnded(connectionGeneration: Long) {
        val closing: ConnectionSession?
        synchronized(lock) {
            val current = activeSession
            if (current?.generation != connectionGeneration) return
            activeSession = null
            closing = current.value
            cleanupCount += 1
            reconnectAfterCleanup = running && networkAvailable
        }
        closeAndDispose(closing)
        finishCleanup()
    }

    private fun scheduleAfterConnectionFailure(connectionGeneration: Long) {
        val retryPlan = synchronized(lock) {
            if (connectingGeneration != connectionGeneration || generation != connectionGeneration) return
            connectingGeneration = null
            if (running && networkAvailable) reserveRetryLocked() else null
        }
        if (retryPlan != null) schedule(retryPlan)
    }

    private fun reserveRetryLocked(): RetryPlan {
        val delay = RETRY_DELAYS_MILLIS[retryIndex.coerceAtMost(RETRY_DELAYS_MILLIS.lastIndex)]
        if (retryIndex < RETRY_DELAYS_MILLIS.lastIndex) retryIndex += 1
        val retryGeneration = ++generation
        retryPending = true
        return RetryPlan(
            retryGeneration,
            delay,
            reserveStatusLocked(ConnectionStatus.RETRYING),
        )
    }

    private fun schedule(plan: RetryPlan) {
        publish(plan.status)
        val scheduled = try {
            scheduler.schedule(plan.delayMillis) { fireRetry(plan.generation) }
        } catch (_: Exception) {
            stopAfterSchedulerFailure(plan.generation)
            return
        }
        val keep = synchronized(lock) {
            if (
                running &&
                networkAvailable &&
                retryPending &&
                generation == plan.generation
            ) {
                scheduledRetry = scheduled
                true
            } else {
                false
            }
        }
        if (!keep) cancel(scheduled)
    }

    private fun fireRetry(retryGeneration: Long) {
        val shouldConnect = synchronized(lock) {
            if (
                !running ||
                !networkAvailable ||
                !retryPending ||
                generation != retryGeneration
            ) {
                return
            }
            retryPending = false
            scheduledRetry = null
            true
        }
        if (shouldConnect) beginConnect()
    }

    private fun finishCleanup() {
        val retryPlan: RetryPlan?
        val shouldConnect: Boolean
        synchronized(lock) {
            check(cleanupCount > 0) { "Connection cleanup count underflow" }
            cleanupCount -= 1
            if (
                cleanupCount == 0 &&
                running &&
                networkAvailable &&
                activeSession == null &&
                connectingGeneration == null &&
                !retryPending
            ) {
                if (reconnectAfterCleanup) {
                    reconnectAfterCleanup = false
                    retryPlan = reserveRetryLocked()
                    shouldConnect = false
                } else {
                    retryPlan = null
                    shouldConnect = true
                }
            } else {
                retryPlan = null
                shouldConnect = false
            }
        }
        if (retryPlan != null) schedule(retryPlan) else if (shouldConnect) beginConnect()
    }

    private fun stopAfterSchedulerFailure(retryGeneration: Long) {
        val status = synchronized(lock) {
            if (!retryPending || generation != retryGeneration) return
            retryPending = false
            running = false
            networkAvailable = false
            generation += 1
            reserveStatusLocked(ConnectionStatus.STOPPED)
        }
        publish(status)
    }

    private fun cancel(retry: ScheduledRetry?) {
        try {
            retry?.cancel()
        } catch (_: Exception) {
            // Cancellation is best-effort; generation checks reject a late task.
        }
    }

    private fun closeAndDispose(session: ConnectionSession?) {
        try {
            session?.close()
        } catch (_: Exception) {
            // Dispose still runs if graceful close fails.
        }
        try {
            session?.dispose()
        } catch (_: Exception) {
            // A broken session cannot keep the supervisor alive.
        }
    }

    private fun reserveStatusLocked(status: ConnectionStatus): StatusUpdate {
        currentStatus = status
        return StatusUpdate(++statusVersion, status)
    }

    private fun publish(update: StatusUpdate) = synchronized(statusDeliveryLock) {
        val current = synchronized(lock) {
            update.version == statusVersion && update.status == currentStatus
        }
        if (current) {
            try {
                statusListener.onStatusChanged(update.status)
            } catch (_: Exception) {
                // UI/notification reporting cannot destabilize connection recovery.
            }
        }
    }

    private companion object {
        val RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
    }
}
