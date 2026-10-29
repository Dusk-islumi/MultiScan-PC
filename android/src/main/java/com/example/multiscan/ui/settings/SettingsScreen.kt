package com.example.multiscan.ui.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.multiscan.AppContainer
import com.example.multiscan.R
import com.example.multiscan.data.pc.PcConnectionPhase
import com.example.multiscan.data.pc.PcConnectionState
import com.example.multiscan.ui.common.ScreenTopBar
import com.example.multiscan.ui.common.screenViewModel
import com.example.multiscan.ui.theme.StatusDanger
import com.example.multiscan.ui.theme.StatusSuccess

/** 设置页：连接状态 + 手动连接入口 + 设备信息 + 调试入口。 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onDebug: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = screenViewModel { SettingsViewModel(context) }
    val pcState by container.pcConnection.state.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ScreenTopBar(
                title = stringResource(R.string.settings_title),
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard(title = stringResource(R.string.settings_section_pc)) {
                PcStatusLine(state = pcState)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = container.pcConnection::requestPrompt,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_pc_manage))
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.settings_pc_auto_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = stringResource(R.string.settings_pc_about)) {
                Text(
                    text = stringResource(R.string.settings_pc_about_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard(title = stringResource(R.string.settings_device_info)) {
                InfoLine(label = stringResource(R.string.settings_device_model), value = Build.MODEL)
                InfoLine(label = stringResource(R.string.settings_device_manufacturer), value = Build.MANUFACTURER)
                InfoLine(
                    label = stringResource(R.string.settings_device_os),
                    value = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
                )
                InfoLine(
                    label = stringResource(R.string.settings_device_app_version),
                    value = viewModel.appVersion,
                )
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDebug),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_debug_entry),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.settings_debug_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PcStatusLine(state: PcConnectionState) {
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
    val dotColor: Color = when (state.phase) {
        PcConnectionPhase.CONNECTED -> StatusSuccess
        PcConnectionPhase.DISCONNECTED -> StatusDanger
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.url.isNotBlank()) {
                Text(
                    text = state.url,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(end = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
