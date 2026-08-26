package dev.codexremote.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.codexremote.app.ui.AndroidRemoteAppGateway
import dev.codexremote.app.ui.CommandDelivery
import dev.codexremote.app.ui.DeviceIdentity
import dev.codexremote.app.ui.LoadResult
import dev.codexremote.app.ui.NotificationPermissionPolicy
import dev.codexremote.app.ui.RemoteApp
import dev.codexremote.app.ui.RemoteAppController
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "remote-app-worker").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var controller: RemoteAppController
    private var loadResult by mutableStateOf<LoadResult>(LoadResult.Unpaired)
    private var busy by mutableStateOf(false)
    private var deliveryMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val now = { Instant.now().toString() }
        controller = RemoteAppController(
            gateway = AndroidRemoteAppGateway(this, clock = now),
            device = loadDeviceIdentity(),
            newIdentifier = { UUID.randomUUID().toString() },
            now = now,
        )
        requestNotificationPermissionIfNeeded()
        setContent {
            MaterialTheme {
                RemoteApp(
                    loadResult = loadResult,
                    busy = busy,
                    deliveryMessage = deliveryMessage,
                    onPair = ::pair,
                    onRefresh = ::resumeConnection,
                    onStartTask = { projectId, prompt, modelId, reasoningId ->
                        executeCommand {
                            controller.startTask(projectId, prompt, modelId, reasoningId)
                        }
                    },
                    onSendTurn = { threadId, prompt ->
                        executeCommand { controller.sendTurn(threadId, prompt) }
                    },
                )
            }
        }
        pairingLink(intent)?.let(::pair) ?: resumeConnection()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pairingLink(intent)?.let(::pair)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun pair(invitation: String) = executeLoad { controller.pair(invitation) }

    private fun resumeConnection() = executeLoad(controller::resume)

    private fun executeLoad(action: () -> LoadResult) {
        if (busy) return
        busy = true
        deliveryMessage = null
        worker.execute {
            val result = action()
            postToActiveActivity {
                loadResult = result
                busy = false
            }
        }
    }

    private fun executeCommand(action: () -> CommandDelivery) {
        if (busy) return
        busy = true
        deliveryMessage = null
        worker.execute {
            val result = action()
            postToActiveActivity {
                deliveryMessage = result.userMessage()
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
        const val DEVICE_PREFERENCES = "device_identity"
        const val DEVICE_ID_KEY = "device_id"
    }
}
