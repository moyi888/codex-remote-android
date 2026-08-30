package dev.codexremote.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
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
    onOpenThread: (String) -> Unit,
    onCloseThread: () -> Unit,
    onLoadMoreThread: (String, String) -> Unit,
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
            onOpenThread = onOpenThread,
            onCloseThread = onCloseThread,
            onLoadMoreThread = onLoadMoreThread,
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
    onOpenThread: (String) -> Unit,
    onCloseThread: () -> Unit,
    onLoadMoreThread: (String, String) -> Unit,
    onStartTask: (String, String, String?, String?) -> Unit,
    onSendTurn: (String, String) -> Unit,
    onOpenLogs: () -> Unit,
    onSteerTurn: (String, String, String) -> Unit,
    onInterruptTurn: (String, String) -> Unit,
) {
    var page by remember { mutableStateOf(HomePage.THREADS) }
    var selectedThreadId by remember { mutableStateOf<String?>(null) }
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
                        TextButton(onClick = { page = HomePage.THREADS; onCloseThread() }) { Text("返回") }
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
                        selectedThreadId = it.id
                        page = HomePage.THREAD_DETAIL
                        onOpenThread(it.id)
                    },
                )
                HomePage.NEW_TASK -> NewTaskScreen(state, busy, onStartTask)
                HomePage.THREAD_DETAIL -> state.snapshot?.threads?.firstOrNull { it.id == selectedThreadId }?.let { thread ->
                    ThreadDetailScreen(
                        thread = thread,
                        history = state.histories[thread.id],
                        canSend = state.snapshot?.capabilities?.sendTurn == true,
                        canSteer = state.snapshot?.capabilities?.steer == true,
                        canInterrupt = state.snapshot?.capabilities?.stopTurn == true,
                        busy = busy,
                        onSend = onSendTurn,
                        onSteer = onSteerTurn,
                        onInterrupt = onInterruptTurn,
                        onLoadMore = onLoadMoreThread,
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
    history: dev.codexremote.app.protocol.ThreadHistory?,
    canSend: Boolean,
    canSteer: Boolean,
    canInterrupt: Boolean,
    busy: Boolean,
    onSend: (String, String) -> Unit,
    onSteer: (String, String, String) -> Unit,
    onInterrupt: (String, String) -> Unit,
    onLoadMore: (String, String) -> Unit,
) {
    var prompt by remember(thread.id) { mutableStateOf("") }
    val listState = rememberLazyListState()
    var previousEntryCount by remember(thread.id) { mutableStateOf(0) }
    var requestedCursor by remember(thread.id) { mutableStateOf<String?>(null) }
    var autoLoadArmed by remember(thread.id) { mutableStateOf(true) }
    val activeTurn = thread.activeTurnId
    val canSteerCurrent = activeTurn != null && thread.state == ThreadState.RUNNING && canSteer
    LaunchedEffect(history?.entries?.size) {
        val entries = history?.entries.orEmpty()
        if (entries.isNotEmpty()) {
            val added = entries.size - previousEntryCount
            if (previousEntryCount == 0) {
                val firstMessageIndex = if (history?.nextCursor != null) 1 else 0
                listState.scrollToItem(firstMessageIndex + entries.lastIndex)
            } else if (added > 0) {
                listState.scrollToItem(
                    (listState.firstVisibleItemIndex + added).coerceAtMost(entries.lastIndex),
                )
            }
        }
        previousEntryCount = entries.size
    }
    LaunchedEffect(thread.id, history?.nextCursor, busy) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            if (index > 1) autoLoadArmed = true
            val cursor = history?.nextCursor
            if (index <= 1 && cursor != null && autoLoadArmed && !busy && cursor != requestedCursor) {
                autoLoadArmed = false
                requestedCursor = cursor
                onLoadMore(thread.id, cursor)
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(thread.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
            Text("${thread.projectName} · ${thread.state.label()}")
            thread.attention?.let(ThreadAttentionMessage::from)?.let { AttentionNotice(it) }
        }
        HorizontalDivider()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when {
                    history == null -> item { Text("正在读取历史对话…", color = MaterialTheme.colorScheme.secondary) }
                    history.entries.isEmpty() -> item { Text("暂无可显示的历史消息。", color = MaterialTheme.colorScheme.secondary) }
                    else -> {
                        history.nextCursor?.let { cursor ->
                            item(key = "load-more-$cursor") {
                                TextButton(
                                    onClick = { onLoadMore(thread.id, cursor) },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("向上加载更早对话") }
                            }
                        }
                        itemsIndexed(history.entries, key = { _, entry -> entry.id }) { _, entry ->
                            val isUser = entry.kind == "用户"
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                            ) {
                                Card(Modifier.fillMaxWidth(0.9f)) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(entry.kind, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                        Text(entry.text, style = MaterialTheme.typography.bodyLarge)
                                        entry.status?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("继续对话") },
                    minLines = 2,
                    maxLines = 5,
                    enabled = !busy && (canSend || canSteerCurrent),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { onSend(thread.id, prompt) }, enabled = !busy && canSend && prompt.isNotBlank(), modifier = Modifier.weight(1f)) { Text("发送新一轮") }
                    if (canSteerCurrent) {
                        Button(onClick = { onSteer(thread.id, activeTurn!!, prompt) }, enabled = !busy && prompt.isNotBlank(), modifier = Modifier.weight(1f)) { Text("追加") }
                    }
                }
                if (activeTurn != null && thread.state == ThreadState.RUNNING && canInterrupt) {
                    TextButton(onClick = { onInterrupt(thread.id, activeTurn) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("中止当前执行") }
                }
            }
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
