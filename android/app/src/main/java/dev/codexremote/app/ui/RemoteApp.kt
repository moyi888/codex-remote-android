package dev.codexremote.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.codexremote.app.protocol.ThreadState
import dev.codexremote.app.protocol.ThreadSummary
import dev.codexremote.app.diagnostics.DiagnosticLogStore

@Composable
fun RemoteApp(
    loadResult: LoadResult,
    busy: Boolean,
    deliveryMessage: String?,
    onPair: (String) -> Unit,
    cameraPermission: CameraPermission,
    onScannedInvitation: (String) -> Unit,
    onRequestCamera: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenTailscale: () -> Unit,
    onRefresh: () -> Unit,
    onStartTask: (String, String, String?, String?) -> Unit,
    onSendTurn: (String, String) -> Unit,
    logs: DiagnosticLogStore,
    onSteerTurn: (String, String, String) -> Unit = { _, _, _ -> },
    onInterruptTurn: (String, String) -> Unit = { _, _ -> },
) {
    var showLogs by remember { mutableStateOf(false) }
    if (showLogs) {
        DiagnosticLogScreen(logs, onBack = { showLogs = false })
        return
    }
    when (loadResult) {
        LoadResult.Unpaired -> PairingScreen(
            busy, null, cameraPermission, onScannedInvitation, onRequestCamera,
            onOpenAppSettings, onPair, onOpenTailscale, onOpenLogs = { showLogs = true },
        )
        is LoadResult.Failed -> PairingScreen(
            busy, loadResult.message, cameraPermission,
            onScannedInvitation, onRequestCamera, onOpenAppSettings, onPair, onOpenTailscale,
            onOpenLogs = { showLogs = true },
        )
        is LoadResult.Ready -> HomeScreen(
            state = loadResult.state,
            busy = busy,
            deliveryMessage = deliveryMessage,
            onRefresh = onRefresh,
            onStartTask = onStartTask,
            onSendTurn = onSendTurn,
            onOpenLogs = { showLogs = true },
            onSteerTurn = onSteerTurn,
            onInterruptTurn = onInterruptTurn,
        )
    }
}

private enum class HomePage { THREADS, NEW_TASK, THREAD_DETAIL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    state: RemoteAppState,
    busy: Boolean,
    deliveryMessage: String?,
    onRefresh: () -> Unit,
    onStartTask: (String, String, String?, String?) -> Unit,
    onSendTurn: (String, String) -> Unit,
    onOpenLogs: () -> Unit,
    onSteerTurn: (String, String, String) -> Unit,
    onInterruptTurn: (String, String) -> Unit,
) {
    var page by remember(state.snapshot?.eventCursor) { mutableStateOf(HomePage.THREADS) }
    var selectedThread by remember(state.snapshot?.eventCursor) {
        mutableStateOf<ThreadSummary?>(null)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (page) {
                            HomePage.THREADS -> "Codex 任务"
                            HomePage.NEW_TASK -> "新建任务"
                            HomePage.THREAD_DETAIL -> "任务详情"
                        },
                    )
                },
                navigationIcon = {
                    if (page != HomePage.THREADS) {
                        TextButton(onClick = { page = HomePage.THREADS }) { Text("返回") }
                    }
                },
                actions = {
                    if (page == HomePage.THREADS) {
                        TextButton(onClick = onRefresh, enabled = !busy) { Text("刷新") }
                        TextButton(
                            onClick = { page = HomePage.NEW_TASK },
                            enabled = state.snapshot?.capabilities?.startTask == true,
                        ) { Text("新任务") }
                        TextButton(onClick = onOpenLogs) { Text("日志") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            deliveryMessage?.let {
                Text(
                    it,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                HorizontalDivider()
            }
            when (page) {
                HomePage.THREADS -> ThreadList(
                    threads = state.snapshot?.threads.orEmpty(),
                    onSelect = {
                        selectedThread = it
                        page = HomePage.THREAD_DETAIL
                    },
                )
                HomePage.NEW_TASK -> NewTaskScreen(state, busy, onStartTask)
                HomePage.THREAD_DETAIL -> selectedThread?.let { thread ->
                    ThreadDetailScreen(
                        thread = thread,
                        canSend = state.snapshot?.capabilities?.sendTurn == true,
                        canSteer = state.snapshot?.capabilities?.steer == true,
                        canInterrupt = state.snapshot?.capabilities?.stopTurn == true,
                        busy = busy,
                        onSend = onSendTurn,
                        onSteer = onSteerTurn,
                        onInterrupt = onInterruptTurn,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadList(
    threads: List<ThreadSummary>,
    onSelect: (ThreadSummary) -> Unit,
) {
    if (threads.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("还没有可显示的任务。", style = MaterialTheme.typography.titleMedium)
            Text("可以刷新，或从右上角新建任务。")
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(threads, key = ::threadListItemKey) { _, thread ->
            Card(Modifier.fillMaxWidth().clickable { onSelect(thread) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(thread.title, fontWeight = FontWeight.SemiBold)
                    Text("${thread.projectName} · ${thread.state.label()}")
                    Text(thread.updatedAt, style = MaterialTheme.typography.bodySmall)
                    thread.attention?.let(ThreadAttentionMessage::from)?.let { AttentionNotice(it) }
                }
            }
        }
    }
}

internal fun threadListItemKey(index: Int, thread: ThreadSummary): String =
    "${thread.id}#$index"

@Composable
private fun ThreadDetailScreen(
    thread: ThreadSummary,
    canSend: Boolean,
    canSteer: Boolean,
    canInterrupt: Boolean,
    busy: Boolean,
    onSend: (String, String) -> Unit,
    onSteer: (String, String, String) -> Unit,
    onInterrupt: (String, String) -> Unit,
) {
    var prompt by remember(thread.id) { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val activeTurn = thread.activeTurnId
        val canSteerCurrent = activeTurn != null && thread.state == ThreadState.RUNNING && canSteer
        Text(thread.title, style = MaterialTheme.typography.headlineSmall)
        Text("项目：${thread.projectName}")
        Text("状态：${thread.state.label()}")
        Text("更新时间：${thread.updatedAt}")
        thread.attention?.let(ThreadAttentionMessage::from)?.let { AttentionNotice(it) }
        HorizontalDivider()
        Text("当前 Bridge 协议暂不提供历史消息；这里显示任务摘要，并可继续下发新一轮。")
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("继续对话") },
            minLines = 4,
            enabled = !busy && (canSend || canSteerCurrent),
        )
        Button(
            onClick = { onSend(thread.id, prompt) },
            enabled = !busy && canSend && prompt.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "发送中…" else "发送新一轮") }
        if (canSteerCurrent) {
            Button(
                onClick = { onSteer(thread.id, activeTurn!!, prompt) },
                enabled = !busy && prompt.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "发送中…" else "追加到当前执行") }
        }
        if (activeTurn != null && thread.state == ThreadState.RUNNING && canInterrupt) {
            Button(
                onClick = { onInterrupt(thread.id, activeTurn) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "处理中…" else "中止当前执行") }
        }
    }
}

@Composable
private fun NewTaskScreen(
    initialState: RemoteAppState,
    busy: Boolean,
    onStartTask: (String, String, String?, String?) -> Unit,
) {
    var state by remember(initialState.snapshot?.eventCursor) { mutableStateOf(initialState) }
    var prompt by remember(initialState.snapshot?.eventCursor) { mutableStateOf("") }
    val snapshot = state.snapshot ?: return
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OptionSection(
            title = "项目",
            options = snapshot.projects.map { it.id to it.displayName },
            selected = state.newTask.projectId,
            onSelect = { state = state.selectProject(it) },
        )
        OptionSection(
            title = "模型",
            options = snapshot.models.map { it.id to it.displayName },
            selected = state.newTask.modelId,
            onSelect = { state = state.selectModel(it) },
        )
        val reasoningOptions = snapshot.models
            .firstOrNull { it.id == state.newTask.modelId }
            ?.reasoningOptions.orEmpty()
        Text("推理强度", style = MaterialTheme.typography.titleMedium)
        if (state.newTask.modelId == null) {
            Text("使用 Bridge 默认推理强度。")
        } else {
            RadioOption("默认", state.newTask.reasoningId == null) {
                state = state.selectReasoning(null)
            }
            reasoningOptions.forEach { option ->
                RadioOption(option.displayName, state.newTask.reasoningId == option.id) {
                    state = state.selectReasoning(option.id)
                }
            }
        }
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("任务内容") },
            minLines = 5,
            enabled = !busy,
        )
        Button(
            onClick = {
                onStartTask(
                    requireNotNull(state.newTask.projectId),
                    prompt,
                    state.newTask.modelId,
                    state.newTask.reasoningId,
                )
            },
            enabled = !busy && prompt.isNotBlank() && state.newTask.projectId != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "创建中…" else "创建任务") }
    }
}

@Composable
private fun OptionSection(
    title: String,
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    if (options.isEmpty()) Text("Bridge 没有返回可用选项。")
    options.forEach { (id, label) ->
        RadioOption(label, selected == id) { onSelect(id) }
    }
}

@Composable
private fun RadioOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AttentionNotice(message: String) {
    Spacer(Modifier.height(8.dp))
    Text(
        message,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}

private fun ThreadState.label(): String = when (this) {
    ThreadState.IDLE -> "空闲"
    ThreadState.RUNNING -> "执行中"
    ThreadState.COMPLETED -> "已完成"
    ThreadState.FAILED -> "失败"
    ThreadState.DISCONNECTED -> "已断开"
}
