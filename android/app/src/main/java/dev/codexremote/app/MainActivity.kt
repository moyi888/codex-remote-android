package dev.codexremote.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.codexremote.app.ui.AndroidRemoteAppGateway
import dev.codexremote.app.ui.CommandDelivery
import dev.codexremote.app.ui.CameraPermission
import dev.codexremote.app.ui.DeviceIdentity
import dev.codexremote.app.ui.LoadResult
import dev.codexremote.app.ui.NotificationPermissionPolicy
import dev.codexremote.app.ui.PendingPairingInvitation
import dev.codexremote.app.ui.RemoteApp
import dev.codexremote.app.ui.RemoteAppController
import dev.codexremote.app.ui.RemoteAppState
import dev.codexremote.app.protocol.ConversationEntry
import dev.codexremote.app.diagnostics.DiagnosticLogs
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "remote-app-worker").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (loadResult is LoadResult.Ready && !busy && !refreshing) refreshSnapshot()
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }
    private lateinit var controller: RemoteAppController
    private var loadResult by mutableStateOf<LoadResult>(LoadResult.Unpaired)
    private var appState by mutableStateOf(RemoteAppState())
    private var busy by mutableStateOf(false)
    private var refreshing by mutableStateOf(false)
    private var deliveryMessage by mutableStateOf<String?>(null)
    private var activeThreadId: String? = null
    private var cameraPermission by mutableStateOf(CameraPermission.REQUESTABLE)
    private val logs = DiagnosticLogs.instance
    private val pendingPairingInvitation = PendingPairingInvitation()
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermission = if (granted) CameraPermission.GRANTED else currentCameraPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val now = { Instant.now().toString() }
        controller = RemoteAppController(
            gateway = AndroidRemoteAppGateway(this, logs = logs, clock = now),
            device = loadDeviceIdentity(),
            newIdentifier = { UUID.randomUUID().toString() },
            now = now,
        )
        cameraPermission = currentCameraPermission()
        requestNotificationPermissionIfNeeded()
        setContent {
            MaterialTheme {
                RemoteApp(
                    loadResult = when (val result = loadResult) {
                        is LoadResult.Ready -> LoadResult.Ready(appState)
                        else -> result
                    },
                    busy = busy,
                    deliveryMessage = deliveryMessage,
                    onPair = ::pair,
                    cameraPermission = cameraPermission,
                    onScannedInvitation = ::pair,
                    onRequestCamera = ::requestCameraPermission,
                    onOpenAppSettings = ::openAppSettings,
                    onOpenTailscale = ::openTailscale,
                    onRefresh = ::resumeConnection,
                    onOpenThread = ::loadThread,
                    onCloseThread = ::closeThread,
                    onLoadMoreThread = ::loadMoreThread,
                    onStartTask = { projectId, prompt, modelId, reasoningId ->
                        executeCommand {
                            controller.startTask(projectId, prompt, modelId, reasoningId)
                        }
                    },
                    onSendTurn = { threadId, prompt ->
                        executeCommand({ controller.sendTurn(threadId, prompt) }) {
                            appState = appState.withPendingUserMessage(
                                threadId,
                                ConversationEntry(UUID.randomUUID().toString(), "用户", prompt),
                            )
                        }
                    },
                    logs = logs,
                    onSteerTurn = { threadId, turnId, prompt ->
                        executeCommand({ controller.steerTurn(threadId, turnId, prompt) }) {
                            appState = appState.withPendingUserMessage(
                                threadId,
                                ConversationEntry(UUID.randomUUID().toString(), "用户", prompt),
                            )
                        }
                    },
                    onInterruptTurn = { threadId, turnId ->
                        executeCommand { controller.interruptTurn(threadId, turnId) }
                    },
                )
            }
        }
        pairingLink(intent)?.let(::pair) ?: resumeConnection()
        mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pairingLink(intent)?.let(::pair)
    }

    override fun onResume() {
        super.onResume()
        cameraPermission = currentCameraPermission()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(pollRunnable)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun pair(invitation: String) {
        logs.info("scanner", "invitation callback received length=${invitation.length}")
        val ready = pendingPairingInvitation.offer(invitation, busy) ?: return
        logs.info("pair", "invitation accepted for processing")
        executeLoad { controller.pair(ready) }
    }

    private fun resumeConnection() = executeLoad(controller::resume)

    private fun refreshSnapshot() {
        if (busy || refreshing || loadResult !is LoadResult.Ready) return
        refreshing = true
        val threadId = activeThreadId
        worker.execute {
            try {
                val snapshot = controller.refreshSnapshot()
                val history = threadId?.let {
                    runCatching { controller.loadThreadHistory(it) }
                        .onFailure { error -> logs.error("history", "background refresh failed thread=${it.take(8)}", error) }
                        .getOrNull()
                }
                postToActiveActivity {
                    appState = appState.withSnapshot(snapshot)
                    if (threadId != null && threadId == activeThreadId && history != null) {
                        appState = appState.withHistory(threadId, history)
                    }
                    refreshing = false
                }
            } catch (error: Exception) {
                logs.error("connection", "background snapshot refresh failed", error)
                postToActiveActivity { refreshing = false }
            }
        }
    }

    private fun executeLoad(action: () -> LoadResult) {
        if (busy) return
        logs.debug("connection", "background load scheduled")
        busy = true
        deliveryMessage = null
        worker.execute {
            val result = action()
            logs.info("connection", "background load finished result=${result::class.simpleName}")
            postToActiveActivity {
                loadResult = result
                if (result is LoadResult.Ready) {
                    appState = result.state.copy(
                        histories = appState.histories,
                        pendingUserMessages = appState.pendingUserMessages,
                    )
                }
                busy = false
                pendingPairingInvitation.takeAfterLoad()?.let(::pair)
            }
        }
    }

    private fun loadThread(threadId: String) {
        activeThreadId = threadId
        executeHistory(threadId, null, append = false)
    }

    private fun closeThread() {
        activeThreadId = null
    }

    private fun loadMoreThread(threadId: String, cursor: String) {
        executeHistory(threadId, cursor, append = true)
    }

    private fun executeHistory(threadId: String, cursor: String?, append: Boolean) {
        if (busy) return
        busy = true
        deliveryMessage = null
        worker.execute {
            try {
                val history = controller.loadThreadHistory(threadId, cursor)
                postToActiveActivity {
                    appState = appState.withHistory(threadId, history, append)
                    busy = false
                }
            } catch (error: Exception) {
                logs.error("history", "load failed thread=${threadId.take(8)}", error)
                postToActiveActivity {
                    deliveryMessage = "无法读取任务历史：${error.message ?: "请求失败"}"
                    busy = false
                }
            }
        }
    }

    private fun executeCommand(action: () -> CommandDelivery, onSent: (() -> Unit)? = null) {
        if (busy) return
        busy = true
        deliveryMessage = null
        worker.execute {
            val result = action()
            postToActiveActivity {
                deliveryMessage = result.userMessage()
                if (result == CommandDelivery.Sent) onSent?.invoke()
                if (result == CommandDelivery.AuthenticationRequired) {
                    loadResult = LoadResult.Unpaired
                }
                busy = false
            }
        }
    }

    private fun postToActiveActivity(action: () -> Unit) {
        mainHandler.post {
            if (!isFinishing && !isDestroyed) action()
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        val granted = if (Build.VERSION.SDK_INT >= 33) {
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (NotificationPermissionPolicy.shouldRequest(Build.VERSION.SDK_INT, granted)) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun currentCameraPermission(): CameraPermission {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            return CameraPermission.GRANTED
        }
        val requested = getSharedPreferences(CAMERA_PREFERENCES, MODE_PRIVATE)
            .getBoolean(CAMERA_REQUESTED_KEY, false)
        return when {
            !requested -> CameraPermission.REQUESTABLE
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ->
                CameraPermission.REQUESTABLE
            else -> CameraPermission.DENIED
        }
    }

    private fun requestCameraPermission() {
        check(
            getSharedPreferences(CAMERA_PREFERENCES, MODE_PRIVATE)
                .edit()
                .putBoolean(CAMERA_REQUESTED_KEY, true)
                .commit(),
        ) { "Unable to persist camera permission request" }
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun openTailscale() {
        val launchIntent = packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TAILSCALE_ANDROID_URL)))
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    private fun loadDeviceIdentity(): DeviceIdentity {
        val preferences = getSharedPreferences(DEVICE_PREFERENCES, MODE_PRIVATE)
        val deviceId = preferences.getString(DEVICE_ID_KEY, null) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString(DEVICE_ID_KEY, it).commit()) {
                "Unable to persist device identity"
            }
        }
        val model = Build.MODEL.trim().ifEmpty { "Android 手机" }
        return DeviceIdentity(deviceId, model)
    }

    private fun pairingLink(intent: Intent): String? {
        if (intent.action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        return data.toString().takeIf {
            data.scheme == "codex-remote" && data.host == "pair"
        }
    }

    private fun CommandDelivery.userMessage(): String = when (this) {
        CommandDelivery.Sent -> "已发送到家中 Codex。"
        CommandDelivery.Queued -> "网络暂不可用，命令已加密排队。"
        CommandDelivery.AuthenticationRequired -> "设备凭据已失效，请重新配对。"
        CommandDelivery.Rejected -> "Bridge 拒绝了命令，请检查输入后重试。"
    }

    private companion object {
        const val POLL_INTERVAL_MS = 10_000L
        const val DEVICE_PREFERENCES = "device_identity"
        const val DEVICE_ID_KEY = "device_id"
        const val CAMERA_PREFERENCES = "camera_permission"
        const val CAMERA_REQUESTED_KEY = "requested"
        const val TAILSCALE_ANDROID_URL = "https://tailscale.com/download/android"
        const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
    }

}
