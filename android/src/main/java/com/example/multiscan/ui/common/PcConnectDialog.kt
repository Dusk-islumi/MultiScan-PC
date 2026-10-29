package com.example.multiscan.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.multiscan.R
import com.example.multiscan.data.pc.PcClient
import com.example.multiscan.data.pc.PcConnectionPhase
import com.example.multiscan.data.pc.PcConnectionState
import com.example.multiscan.ui.theme.StatusDanger

/** 全局连接弹窗：自动搜索失败时弹出，也可从首页状态栏/设置页主动打开。 */
@Composable
fun PcConnectDialog(
    state: PcConnectionState,
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit,
    onSearch: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf(state.url) }
    var localError by remember { mutableStateOf<String?>(null) }
    val busy = state.busy
    val invalidText = stringResource(R.string.pc_connect_invalid)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.pc_connect_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = if (state.connected) {
                        stringResource(
                            R.string.pc_connect_message_connected,
                            state.name.ifBlank { state.url },
                        )
                    } else {
                        stringResource(R.string.pc_connect_message)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                if (state.phase == PcConnectionPhase.SEARCHING) {
                                    R.string.pc_connect_searching
                                } else {
                                    R.string.pc_connect_connecting
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        localError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.pc_connect_address)) },
                    placeholder = { Text(stringResource(R.string.pc_connect_address_hint)) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )

                val errorText = localError
                    ?: state.error?.let { stringResource(R.string.pc_connect_failed, it) }
                if (errorText != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusDanger,
                    )
                }

                TextButton(onClick = onSearch, enabled = !busy) {
                    Text(stringResource(R.string.pc_connect_retry))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val normalized = PcClient.normalizeBaseUrl(input)
                    if (normalized == null) {
                        localError = invalidText
                    } else {
                        localError = null
                        onConnect(normalized)
                    }
                },
                enabled = !busy,
            ) {
                Text(stringResource(R.string.pc_connect_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
