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
    private val lock = Any()
    private var running = false
    private var networkAvailable = false
    private var generation = 0L
    private var retryIndex = 0
    private var session: ConnectionSession? = null
    private var scheduledRetry: ScheduledRetry? = null

    fun start(networkAvailable: Boolean) = synchronized(lock) {
        if (running) return@synchronized
        running = true
        this.networkAvailable = networkAvailable
        if (networkAvailable) {
            connectLocked()
        } else {
            publish(ConnectionStatus.WAITING_FOR_NETWORK)
        }
    }

    fun onNetworkAvailable() = synchronized(lock) {
        if (!running || networkAvailable) return@synchronized
        networkAvailable = true
        retryIndex = 0
        cancelRetryLocked()
        connectLocked()
    }

    fun onNetworkUnavailable() = synchronized(lock) {
        if (!running || !networkAvailable) return@synchronized
        networkAvailable = false
        cancelRetryLocked()
        closeSessionLocked()
        publish(ConnectionStatus.WAITING_FOR_NETWORK)
    }

    fun stop() = synchronized(lock) {
        if (!running) return@synchronized
        running = false
        networkAvailable = false
        cancelRetryLocked()
        closeSessionLocked()
        publish(ConnectionStatus.STOPPED)
    }

    fun isRunning(): Boolean = synchronized(lock) { running }

    private fun connectLocked() {
        if (!running || !networkAvailable || session != null || scheduledRetry != null) return
        val callbackGeneration = ++generation
        val created = try {
            sessionFactory.create(callbacks(callbackGeneration))
        } catch (_: Exception) {
            scheduleRetryLocked()
            return
        }
        session = created
        publish(ConnectionStatus.CONNECTING)
        try {
            created.connect()
        } catch (_: Exception) {
            handleDisconnectLocked(callbackGeneration)
        }
    }

    private fun callbacks(callbackGeneration: Long) = object : ConnectionSessionCallbacks {
        override fun onConnected() = synchronized(lock) {
            if (!isCurrent(callbackGeneration)) return@synchronized
            retryIndex = 0
            publish(ConnectionStatus.CONNECTED)
        }

        override fun onDisconnected() = synchronized(lock) {
            handleDisconnectLocked(callbackGeneration)
        }

        override fun onFailure() = synchronized(lock) {
            handleDisconnectLocked(callbackGeneration)
        }
    }

    private fun handleDisconnectLocked(callbackGeneration: Long) {
        if (!isCurrent(callbackGeneration)) return
        closeSessionLocked()
        if (running && networkAvailable) {
            scheduleRetryLocked()
        } else if (running) {
            publish(ConnectionStatus.WAITING_FOR_NETWORK)
        }
    }

    private fun scheduleRetryLocked() {
        if (!running || !networkAvailable || scheduledRetry != null) return
        val delay = RETRY_DELAYS_MILLIS[retryIndex.coerceAtMost(RETRY_DELAYS_MILLIS.lastIndex)]
        if (retryIndex < RETRY_DELAYS_MILLIS.lastIndex) retryIndex += 1
        val retryGeneration = ++generation
        publish(ConnectionStatus.RETRYING)
        scheduledRetry = scheduler.schedule(delay) {
            synchronized(lock) {
                if (!running || !networkAvailable || generation != retryGeneration) return@synchronized
                scheduledRetry = null
                connectLocked()
            }
        }
    }

    private fun cancelRetryLocked() {
        generation += 1
        scheduledRetry?.cancel()
        scheduledRetry = null
    }

    private fun closeSessionLocked() {
        generation += 1
        val closing = session
        session = null
        try {
            closing?.close()
        } catch (_: Exception) {
            // Cleanup remains best-effort; dispose still runs.
        }
        try {
            closing?.dispose()
        } catch (_: Exception) {
            // A broken session must not keep the supervisor alive.
        }
    }

    private fun isCurrent(callbackGeneration: Long): Boolean =
        running && session != null && generation == callbackGeneration

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
