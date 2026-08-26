package dev.codexremote.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

internal class RemoteNotificationController(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CONNECTION_CHANNEL_ID,
                    "后台连接",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "保持与家中 Codex Bridge 的连接"
                    setShowBadge(false)
                },
                NotificationChannel(
                    ATTENTION_CHANNEL_ID,
                    "需要电脑操作",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "第三方登录、OAuth 或验证码需要通过电脑完成"
                },
            ),
        )
    }

    fun foreground(status: ConnectionStatus): Notification = Notification.Builder(
        context,
        CONNECTION_CHANNEL_ID,
    )
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("Codex Remote")
        .setContentText(statusText(status))
        .setContentIntent(launchIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(Notification.CATEGORY_SERVICE)
        .build()

    fun updateForeground(status: ConnectionStatus) {
        try {
            manager.notify(FOREGROUND_NOTIFICATION_ID, foreground(status))
        } catch (_: SecurityException) {
            // Android 13+ can hide notifications when the user denies permission.
        }
    }

    fun showAttention(notice: AttentionNotice) {
        val notification = Notification.Builder(context, ATTENTION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(notice.title)
            .setContentText(notice.body)
            .setStyle(Notification.BigTextStyle().bigText(notice.body))
            .setContentIntent(launchIntent())
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ERROR)
            .build()
        try {
            manager.notify(ATTENTION_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // The foreground service remains connected even if notification permission is denied.
        }
    }

    private fun launchIntent(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun statusText(status: ConnectionStatus): String = when (status) {
        ConnectionStatus.STOPPED -> "后台连接已停止"
        ConnectionStatus.WAITING_FOR_NETWORK -> "等待网络连接"
        ConnectionStatus.CONNECTING -> "正在连接家中电脑"
        ConnectionStatus.CONNECTED -> "已连接家中电脑"
        ConnectionStatus.RETRYING -> "连接中断，正在自动重试"
    }

    companion object {
        const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val ATTENTION_NOTIFICATION_ID = 1002
        private const val CONNECTION_CHANNEL_ID = "bridge_connection"
        private const val ATTENTION_CHANNEL_ID = "browser_attention"
    }
}
