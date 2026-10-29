package com.example.multiscan.ui.records

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.multiscan.AppContainer
import com.example.multiscan.R
import com.example.multiscan.core.time.Utc
import com.example.multiscan.data.repository.SessionRecord
import com.example.multiscan.ui.common.ScreenTopBar
import com.example.multiscan.ui.common.screenViewModel

/**
 * 记录页（扫码枪版）：按批次列出本地扫描结果 —— 开始时间 / 条码数 / 可展开明细（复制）/
 * 整批「发送到电脑」/ 顶部「清空记录」。不同步服务器，不显示 outbox 状态。
 */
@Composable
fun RecordsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: RecordsViewModel = screenViewModel { RecordsViewModel(context, container) }
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val sendingSessionId by viewModel.sendingSessionId.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showClearDialog by rememberSaveable { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    LaunchedEffect(message) {
        val current = message
        if (current != null) {
            snackbarHostState.showSnackbar(current)
            viewModel.consumeMessage()
        }
    }

    val copyCode: (String) -> Unit = { code ->
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("barcode", code))
        Toast.makeText(context, R.string.common_copied, Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ScreenTopBar(
                title = stringResource(R.string.records_title),
                onBack = onBack,
                actionText = stringResource(R.string.records_clear_all),
                onAction = { showClearDialog = true },
            )
        },
    ) { innerPadding ->
        val records = sessions
        when {
            records == null -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            records.isEmpty() -> EmptyRecords(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )

            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = records, key = { record -> record.sessionId }) { record ->
                    var expanded by rememberSaveable(record.sessionId) { mutableStateOf(false) }
                    SessionCard(
                        record = record,
                        expanded = expanded,
                        sending = sendingSessionId == record.sessionId,
                        sendEnabled = sendingSessionId == null,
                        onToggle = { expanded = !expanded },
                        onSend = { viewModel.sendSession(record.sessionId) },
                        onCopy = copyCode,
                    )
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.records_clear_all_confirm_title)) },
            text = { Text(stringResource(R.string.records_clear_all_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        viewModel.clearAll()
                    },
                ) {
                    Text(stringResource(R.string.records_clear_all_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun SessionCard(
    record: SessionRecord,
    expanded: Boolean,
    sending: Boolean,
    sendEnabled: Boolean,
    onToggle: () -> Unit,
    onSend: () -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.records_started_at, Utc.formatDisplay(record.startedAt)),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.records_items_count, record.itemCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (record.finishedAt != null) {
                    Text(
                        text = Utc.formatDisplay(record.finishedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onToggle) {
                    Text(
                        text = if (expanded) {
                            stringResource(R.string.common_collapse)
                        } else {
                            stringResource(R.string.common_expand)
                        },
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSend, enabled = sendEnabled && record.itemCount > 0) {
                    Text(
                        text = if (sending) {
                            stringResource(R.string.records_sending)
                        } else {
                            stringResource(R.string.records_send)
                        },
                    )
                }
            }

            if (expanded) {
                if (record.items.isEmpty()) {
                    Text(
                        text = stringResource(R.string.records_no_items),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    record.items.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.code,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = Utc.formatDisplayShort(item.firstSeenAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { onCopy(item.code) }) {
                                Text(text = stringResource(R.string.common_copy))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyRecords(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.records_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.records_empty_hint_pc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
