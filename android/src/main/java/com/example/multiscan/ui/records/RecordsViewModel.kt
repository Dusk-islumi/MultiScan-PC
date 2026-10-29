package com.example.multiscan.ui.records

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.multiscan.AppContainer
import com.example.multiscan.R
import com.example.multiscan.core.time.Utc
import com.example.multiscan.data.pc.PcSendResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 记录页 ViewModel（扫码枪版）：只读本地批次，提供「整批重发到电脑」与「清空记录」。
 *
 * 不涉及服务器同步 / outbox 状态；发送结果仅通过 snackbar 文案反馈。
 */
class RecordsViewModel(context: Context, container: AppContainer) : ViewModel() {

    private val appContext: Context = context.applicationContext
    private val repository = container.scanRepository
    private val settingsStore = container.settingsStore
    private val pcClient = container.pcClient
    private val pcConnection = container.pcConnection

    /** null = 首份数据尚未到达。 */
    val sessions = repository.sessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 正在重发的批次；null 表示空闲。 */
    private val _sendingSessionId = MutableStateFlow<String?>(null)
    val sendingSessionId: StateFlow<String?> = _sendingSessionId.asStateFlow()

    fun sendSession(sessionId: String) {
        if (_sendingSessionId.value != null) return
        viewModelScope.launch {
            _sendingSessionId.value = sessionId
            try {
                val settings = settingsStore.current()
                if (settings.pcUrl.isBlank()) {
                    pcConnection.requestPrompt()
                    _message.value = appContext.getString(R.string.records_send_need_config)
                    return@launch
                }
                val codes = repository.itemsForSession(sessionId).map { it.code }
                if (codes.isEmpty()) {
                    _message.value = appContext.getString(R.string.send_empty)
                    return@launch
                }
                when (val result = pcClient.send(settings.pcUrl, codes)) {
                    is PcSendResult.Ok -> {
                        settingsStore.setLastPcSend(Utc.nowIso())
                        pcConnection.onSendSucceeded(settings.pcUrl)
                        _message.value = appContext.getString(R.string.records_sent, result.typed)
                    }

                    is PcSendResult.Error -> {
                        pcConnection.onSendFailed(settings.pcUrl, result.networkError, result.message)
                        _message.value = appContext.getString(R.string.records_send_failed, result.message)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = appContext.getString(
                    R.string.records_send_failed,
                    e.message ?: appContext.getString(R.string.common_unknown),
                )
            } finally {
                _sendingSessionId.value = null
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            runCatching { repository.clearAllData() }
                .onSuccess { _message.value = appContext.getString(R.string.records_cleared_all) }
                .onFailure { throwable ->
                    _message.value = appContext.getString(
                        R.string.records_action_failed,
                        throwable.message ?: appContext.getString(R.string.common_unknown),
                    )
                }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
