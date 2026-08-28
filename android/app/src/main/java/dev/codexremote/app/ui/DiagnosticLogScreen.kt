package dev.codexremote.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.codexremote.app.diagnostics.DiagnosticLogEntry
import dev.codexremote.app.diagnostics.DiagnosticLogLevel
import dev.codexremote.app.diagnostics.DiagnosticLogStore

@Composable
fun DiagnosticLogScreen(logs: DiagnosticLogStore, onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(logs.snapshot()) }
    DisposableEffect(logs) {
        val subscription = logs.subscribe { entries = it }
        onDispose { subscription.close() }
    }
    val exported = remember(entries) { logs.export() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("诊断日志", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = logs::clear) { Text("清空") }
        }
        Text("日志仅保存在本次运行内，内容已自动脱敏。", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Codex Remote 诊断日志", exported))
            }, enabled = entries.isNotEmpty()) { Text("复制") }
            Button(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, exported)
                }, "分享诊断日志"))
            }, enabled = entries.isNotEmpty()) { Text("分享") }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (entries.isEmpty()) {
            Text("暂无日志。请返回后重新扫码，再打开此页面。")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(entries) { entry -> LogRow(entry) }
            }
        }
    }
}

@Composable
private fun LogRow(entry: DiagnosticLogEntry) {
    val color = when (entry.level) {
        DiagnosticLogLevel.ERROR -> MaterialTheme.colorScheme.error
        DiagnosticLogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text("${entry.level.name} [${entry.stage}] ${entry.message}", color = color,
        style = MaterialTheme.typography.bodySmall)
}
