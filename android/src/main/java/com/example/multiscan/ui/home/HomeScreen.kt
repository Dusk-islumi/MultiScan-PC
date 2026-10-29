package com.example.multiscan.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.multiscan.AppContainer
import com.example.multiscan.R
import com.example.multiscan.data.pc.PcConnectionPhase
import com.example.multiscan.data.pc.PcConnectionState
import com.example.multiscan.ui.common.screenViewModel
import com.example.multiscan.ui.theme.StatusDanger
import com.example.multiscan.ui.theme.StatusSuccess

/** 首页：一行电脑连接状态 + 扫描统计 + 开始扫描 + 记录/设置入口。 */
@Composable
fun HomeScreen(
    container: AppContainer,
    onScan: () -> Unit,
    onRecords: () -> Unit,
    onSettings: () -> Unit,
) {
    val viewModel: HomeViewModel = screenViewModel { HomeViewModel(container) }
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val pcState by container.pcConnection.state.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.home_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))

            PcStatusBar(
                state = pcState,
                onClick = {
                    container.pcConnection.requestPrompt()
                    if (!pcState.connected) container.pcConnection.search()
                },
            )

            Spacer(Modifier.height(16.dp))

            val currentStats = stats
            if (currentStats == null) {
                LoadingCard()
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(
                        title = stringResource(R.string.home_stat_today),
                        value = currentStats.todayScanned.toString(),
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        title = stringResource(R.string.home_stat_total),
                        value = currentStats.totalRecords.toString(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onScan,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.home_start_scan),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_scan_hint),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                EntryCard(
                    title = stringResource(R.string.home_records),
                    description = stringResource(R.string.home_records_desc),
                    onClick = onRecords,
                    modifier = Modifier.weight(1f),
                )
                EntryCard(
                    title = stringResource(R.string.home_settings),
                    description = stringResource(R.string.home_settings_desc),
                    onClick = onSettings,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PcStatusBar(
    state: PcConnectionState,
    onClick: () -> Unit,
) {
    val text = when (state.phase) {
        PcConnectionPhase.IDLE -> stringResource(R.string.pc_status_idle)
        PcConnectionPhase.SEARCHING -> stringResource(R.string.pc_status_searching)
        PcConnectionPhase.CONNECTING -> stringResource(R.string.pc_status_connecting)
        PcConnectionPhase.CONNECTED -> stringResource(
            R.string.pc_status_connected,
            state.name.ifBlank { state.url },
        )
        PcConnectionPhase.DISCONNECTED -> stringResource(R.string.pc_status_disconnected)
    }
    val action = when (state.phase) {
        PcConnectionPhase.CONNECTED -> stringResource(R.string.pc_status_switch)
        PcConnectionPhase.DISCONNECTED -> stringResource(R.string.pc_status_connect)
        else -> ""
    }
    val dotColor: Color = when (state.phase) {
        PcConnectionPhase.CONNECTED -> StatusSuccess
        PcConnectionPhase.DISCONNECTED -> StatusDanger
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(dotColor, CircleShape),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (action.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = action,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun LoadingCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.home_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EntryCard(
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
