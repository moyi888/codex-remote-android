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
    )

    private val lock = Any()
    private var running = false
    private var networkAvailable = false
    private var generation = 0L
    private var retryIndex = 0
    private var connectingGeneration: Long? = null
    private var activeSession: ActiveSession? = null
    private var retryPending = false
    private var scheduledRetry: ScheduledRetry? = null

    fun start(networkAvailable: Boolean) {
        val shouldConnect = synchronized(lock) {
            if (running) return
            running = true
            this.networkAvailable = networkAvailable
            networkAvailable
        }
        if (shouldConnect) beginConnect() else publish(ConnectionStatus.WAITING_FOR_NETWORK)
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
            shouldConnect = activeSession == null && connectingGeneration == null
        }
        cancel(cancelled)
        if (shouldConnect) beginConnect()
    }

    fun onNetworkUnavailable() {
        val closing: ConnectionSession?
        val cancelled: ScheduledRetry?
        synchronized(lock) {
            if (!running || !networkAvailable) return
            networkAvailable = false
            generation += 1
            connectingGeneration = null
            retryPending = false
            cancelled = scheduledRetry
            scheduledRetry = null
            closing = activeSession?.value
            activeSession = null
        }
        cancel(cancelled)
        closeAndDispose(closing)
        publish(ConnectionStatus.WAITING_FOR_NETWORK)
    }

    fun stop() {
        val closing: ConnectionSession?
        val cancelled: ScheduledRetry?
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
            activeSession = null
        }
        cancel(cancelled)
        closeAndDispose(closing)
        publish(ConnectionStatus.STOPPED)
    }

    fun isRunning(): Boolean = synchronized(lock) { running }

    private fun beginConnect() {
        val connectionGeneration = synchronized(lock) {
            if (
                !running ||
                !networkAvailable ||
                activeSession != null ||
                connectingGeneration != null ||
                retryPending
            ) {
                return
            }
            (++generation).also { connectingGeneration = it }
        }
        publish(ConnectionStatus.CONNECTING)
        val created = try {
            sessionFactory.create(callbacks(connectionGeneration))
        } catch (_: Exception) {
            scheduleAfterConnectionFailure(connectionGeneration)
            return
        }
        val accepted = synchronized(lock) {
            if (
                running &&
                networkAvailable &&
                connectingGeneration == connectionGeneration &&
                generation == connectionGeneration
            ) {
                connectingGeneration = null
                activeSession = ActiveSession(connectionGeneration, created)
                true
            } else {
                false
            }
        }
        if (!accepted) {
            closeAndDispose(created)
            return
        }
        try {
            created.connect()
        } catch (_: Exception) {
            onSessionEnded(connectionGeneration)
        }
    }

    private fun callbacks(connectionGeneration: Long) = object : ConnectionSessionCallbacks {
        override fun onConnected() {
            val accepted = synchronized(lock) {
                val current = activeSession
                if (!running || current?.generation != connectionGeneration) return@synchronized false
                retryIndex = 0
                true
            }
            if (accepted) publish(ConnectionStatus.CONNECTED)
        }

        override fun onDisconnected() = onSessionEnded(connectionGeneration)

        override fun onFailure() = onSessionEnded(connectionGeneration)
    }

    private fun onSessionEnded(connectionGeneration: Long) {
        val closing: ConnectionSession?
        val retryPlan: RetryPlan?
        synchronized(lock) {
            val current = activeSession
            if (current?.generation != connectionGeneration) return
            activeSession = null
            closing = current.value
            retryPlan = if (running && networkAvailable) reserveRetryLocked() else null
        }
        closeAndDispose(closing)
        if (retryPlan != null) schedule(retryPlan)
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
        return RetryPlan(retryGeneration, delay)
    }

    private fun schedule(plan: RetryPlan) {
        publish(ConnectionStatus.RETRYING)
        val scheduled = try {
            scheduler.schedule(plan.delayMillis) { fireRetry(plan.generation) }
        } catch (_: Exception) {
            null
        }
        val keep = synchronized(lock) {
            if (
                scheduled != null &&
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

    private fun publish(status: ConnectionStatus) {
        try {
            statusListener.onStatusChanged(status)
        } catch (_: Exception) {
            // UI/notification reporting cannot destabilize connection recovery.
        }
    }

    private companion object {
        val RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
    }
}
