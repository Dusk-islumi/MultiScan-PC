package com.example.multiscan.ui.send

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.multiscan.AppContainer
import com.example.multiscan.R
import com.example.multiscan.core.time.Utc
import com.example.multiscan.data.pc.PcSendResult
import com.example.multiscan.data.repository.ScanItemRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SendUiState(
    val loading: Boolean = true,
    val items: List<ScanItemRecord> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val sending: Boolean = false,
    val sentCount: Int? = null,
    val error: String? = null,
    val pcUrl: String = "",
    val configured: Boolean = false,
)

/** 发送确认页：列出本次扫描结果，勾选后一次性发到电脑。 */
class SendViewModel(context: Context, container: AppContainer) : ViewModel() {

    private val appContext: Context = context.applicationContext
    private val repository = container.scanRepository
    private val settingsStore = container.settingsStore
    private val pcClient = container.pcClient
    private val pcConnection = container.pcConnection

    private val _ui = MutableStateFlow(SendUiState())
    val ui: StateFlow<SendUiState> = _ui.asStateFlow()

    private var loadedSessionId: String? = null

    init {
        viewModelScope.launch {
            settingsStore.snapshot.collect { snapshot ->
                _ui.update {
                    it.copy(
                        pcUrl = snapshot.pcUrl,
                        configured = snapshot.pcUrl.isNotBlank(),
                    )
                }
            }
        }
    }

    fun load(sessionId: String) {
        if (loadedSessionId == sessionId) return
        loadedSessionId = sessionId
        viewModelScope.launch {
            runCatching { repository.itemsForSession(sessionId) }
                .onSuccess { items ->
                    _ui.update {
                        it.copy(
                            loading = false,
                            items = items,
                            selectedIds = items.map { item -> item.id }.toSet(),
                            error = null,
                        )
                    }
                }
                .onFailure { throwable ->
                    _ui.update {
                        it.copy(
                            loading = false,
                            error = appContext.getString(
                                R.string.send_failed,
                                throwable.message ?: appContext.getString(R.string.common_unknown),
                            ),
                        )
                    }
                }
        }
    }

    fun toggleItem(id: String) {
        _ui.update { state ->
            val selected = state.selectedIds.toMutableSet()
            if (!selected.add(id)) selected.remove(id)
            state.copy(selectedIds = selected, error = null)
        }
    }

    fun toggleAll() {
        _ui.update { state ->
            val allSelected = state.items.isNotEmpty() && state.selectedIds.size == state.items.size
            state.copy(
                selectedIds = if (allSelected) emptySet() else state.items.map { it.id }.toSet(),
                error = null,
            )
        }
    }

    fun send() {
        val state = _ui.value
        if (state.sending) return

        val codes = state.items
            .filter { state.selectedIds.contains(it.id) }
            .map { it.code }
        if (codes.isEmpty()) {
            _ui.update { it.copy(error = appContext.getString(R.string.send_need_selection)) }
            return
        }

        _ui.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            val settings = settingsStore.current()
            if (settings.pcUrl.isBlank()) {
                pcConnection.requestPrompt()
                _ui.update {
                    it.copy(
                        sending = false,
                        error = appContext.getString(R.string.send_need_config),
                    )
                }
                return@launch
            }
            when (val result = pcClient.send(settings.pcUrl, codes)) {
                is PcSendResult.Ok -> {
                    settingsStore.setLastPcSend(Utc.nowIso())
                    pcConnection.onSendSucceeded(settings.pcUrl)
                    _ui.update { it.copy(sending = false, sentCount = result.typed) }
                }

                is PcSendResult.Error -> {
                    pcConnection.onSendFailed(settings.pcUrl, result.networkError, result.message)
                    _ui.update {
                        it.copy(
                            sending = false,
                            error = appContext.getString(R.string.send_failed, result.message),
                        )
                    }
                }
            }
        }
    }
}
