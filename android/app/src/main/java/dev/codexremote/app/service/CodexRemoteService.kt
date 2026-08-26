package dev.codexremote.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import dev.codexremote.app.bridge.BridgeEventStream
import dev.codexremote.app.bridge.BridgeHttpClient
import dev.codexremote.app.bridge.CursorStore
import dev.codexremote.app.bridge.EventStreamListener
import dev.codexremote.app.bridge.EventStreamState
import dev.codexremote.app.bridge.SharedPreferencesCursorStore
import dev.codexremote.app.bridge.SnapshotLoader
import dev.codexremote.app.protocol.EventEnvelope
import dev.codexremote.app.protocol.Snapshot
import dev.codexremote.app.protocol.StoredBridgeConnection
import dev.codexremote.app.security.CredentialVault
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject

class CodexRemoteService : Service() {
    private lateinit var notifications: RemoteNotificationController
    private lateinit var connectivityManager: ConnectivityManager
    private val retryExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor {
        runnable -> Thread(runnable, "bridge-retry").apply { isDaemon = true }
    }
    private var supervisor: ConnectionSupervisor? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        notifications = RemoteNotificationController(this)
        notifications.createChannels()
        startForeground(
            RemoteNotificationController.FOREGROUND_NOTIFICATION_ID,
            notifications.foreground(ConnectionStatus.WAITING_FOR_NETWORK),
        )

        val connection = CredentialVault.create(this).load()
        if (connection == null) {
            stopSelf()
            return
        }
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        supervisor = ConnectionSupervisor(
            sessionFactory = ConnectionSessionFactory { callbacks ->
                createSession(connection, callbacks)
            },
            scheduler = ExecutorRetryScheduler(retryExecutor),
            statusListener = ConnectionStatusListener(notifications::updateForeground),
        )
        supervisor?.start(networkAvailable = false)
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        networkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
                // Callback may already be unregistered by the platform.
            }
        }
        networkCallback = null
        supervisor?.stop()
        supervisor = null
        retryExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun registerNetworkCallback() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            private val availableNetworks = mutableSetOf<Network>()

            override fun onAvailable(network: Network) {
                val firstNetwork = synchronized(availableNetworks) {
                    availableNetworks.add(network)
                    availableNetworks.size == 1
                }
                if (firstNetwork) supervisor?.onNetworkAvailable()
            }

            override fun onLost(network: Network) {
                val noNetworks = synchronized(availableNetworks) {
                    availableNetworks.remove(network)
                    availableNetworks.isEmpty()
                }
                if (noNetworks) supervisor?.onNetworkUnavailable()
            }
        }
        networkCallback = callback
        connectivityManager.registerDefaultNetworkCallback(callback)
    }

    private fun createSession(
        connection: StoredBridgeConnection,
        callbacks: ConnectionSessionCallbacks,
    ): ConnectionSession {
        val httpClient = BridgeHttpClient()
        val cursorStore: CursorStore = SharedPreferencesCursorStore(this)
        val stream = BridgeEventStream(
            baseUrl = connection.baseUrl,
            credential = connection.credential,
            cursorStore = cursorStore,
            snapshotLoader = SnapshotLoader {
                httpClient.snapshot(connection.baseUrl, connection.credential)
            },
            listener = object : EventStreamListener {
                override fun onStateChanged(state: EventStreamState) {
                    when (state) {
                        EventStreamState.CONNECTED -> callbacks.onConnected()
                        EventStreamState.DISCONNECTED -> callbacks.onDisconnected()
                        EventStreamState.CONNECTING -> Unit
                    }
                }

                override fun onEvent(envelope: EventEnvelope<JsonObject>) {
                    AttentionPolicy.fromEvent(envelope)?.let(notifications::showAttention)
                }

                override fun onSnapshot(snapshot: Snapshot) {
                    snapshot.threads.asSequence()
                        .mapNotNull { it.attention }
                        .mapNotNull(AttentionPolicy::fromAttention)
                        .forEach(notifications::showAttention)
                }

                override fun onFailure(error: Throwable) {
                    callbacks.onFailure()
                }
            },
        )
        return object : ConnectionSession {
            override fun connect() = stream.connect()

            override fun close() = stream.close()

            override fun dispose() = stream.dispose()
        }
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, CodexRemoteService::class.java))
        }
    }
}

private class ExecutorRetryScheduler(
    private val executor: ScheduledExecutorService,
) : RetryScheduler {
    override fun schedule(delayMillis: Long, task: () -> Unit): ScheduledRetry {
        val future = executor.schedule({ task() }, delayMillis, TimeUnit.MILLISECONDS)
        return ScheduledRetry { future.cancel(false) }
    }
}
