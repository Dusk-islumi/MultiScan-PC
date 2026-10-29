package com.example.multiscan.ui.send

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.multiscan.AppContainer
import com.example.multiscan.R
import com.example.multiscan.core.time.Utc
import com.example.multiscan.ui.common.ScreenTopBar
import com.example.multiscan.ui.common.screenViewModel
import com.example.multiscan.ui.theme.StatusDanger
import com.example.multiscan.ui.theme.StatusSuccess

/**
 * 扫描完成后的确认页：勾选要发送的条码，一次性 POST 给电脑接收器。
 * 电脑端会把每条条码模拟键盘输入并回车。
 */
@Composable
fun SendScreen(
    container: AppContainer,
    sessionId: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onScanAgain: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: SendViewModel = screenViewModel { SendViewModel(context, container) }
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    LaunchedEffect(sessionId) { viewModel.load(sessionId) }

    BackHandler { if (!ui.sending) onBack() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ScreenTopBar(
                title = stringResource(R.string.send_title),
                onBack = if (ui.sending) ({}) else onBack,
            )
        },
    ) { innerPadding ->
        when {
            ui.loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.send_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ui.sentCount != null -> SendSuccessContent(
                typedCount = ui.sentCount ?: 0,
                onDone = onDone,
                onScanAgain = onScanAgain,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
            )

            else -> SendListContent(
                ui = ui,
                onToggleItem = viewModel::toggleItem,
                onToggleAll = viewModel::toggleAll,
                onSend = viewModel::send,
                onConnect = { container.pcConnection.requestPrompt() },
                onDone = onDone,
                onScanAgain = onScanAgain,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun SendListContent(
    ui: SendUiState,
    onToggleItem: (String) -> Unit,
    onToggleAll: () -> Unit,
    onSend: () -> Unit,
    onConnect: () -> Unit,
    onDone: () -> Unit,
    onScanAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val allSelected = ui.items.isNotEmpty() && ui.selectedIds.size == ui.items.size
    val selectedCount = ui.selectedIds.size

    Column(modifier = modifier) content@ {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.send_target),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (ui.configured) ui.pcUrl else stringResource(R.string.pc_not_configured),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = if (ui.configured) FontFamily.Monospace else FontFamily.Default,
                        color = if (ui.configured) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onConnect) {
                        Text(stringResource(R.string.send_config_entry))
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.send_instruction),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        if (ui.items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.send_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onScanAgain, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.send_scan_again))
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.send_done))
            }
            return@content
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onToggleAll) {
                Text(
                    text = if (allSelected) {
                        stringResource(R.string.send_unselect_all)
                    } else {
                        stringResource(R.string.send_select_all)
                    },
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.send_selected_count, selectedCount, ui.items.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(items = ui.items, key = { item -> item.id }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(10.dp),
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = ui.selectedIds.contains(item.id),
                        onCheckedChange = { onToggleItem(item.id) },
                        enabled = !ui.sending,
                    )
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
                }
            }
        }

        val error = ui.error
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = error,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = StatusDanger,
            )
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onSend,
            enabled = selectedCount > 0 && !ui.sending && ui.configured,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(
                text = if (ui.sending) {
                    stringResource(R.string.send_sending)
                } else {
                    stringResource(R.string.send_button, selectedCount)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SendSuccessContent(
    typedCount: Int,
    onDone: () -> Unit,
    onScanAgain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.send_success_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = StatusSuccess,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.send_success_message, typedCount),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onScanAgain,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(stringResource(R.string.send_scan_again))
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onDone,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(stringResource(R.string.send_done))
        }
    }
}
