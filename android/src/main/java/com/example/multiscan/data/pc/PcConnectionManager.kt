package com.example.multiscan.data.pc

import com.example.multiscan.data.prefs.SettingsAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

enum class PcConnectionPhase { IDLE, SEARCHING, CONNECTING, CONNECTED, DISCONNECTED }

data class PcConnectionState(
    val phase: PcConnectionPhase = PcConnectionPhase.IDLE,
    val name: String = "",
    val url: String = "",
    /** 连接弹窗是否打开（自动搜索失败或用户点状态栏）。 */
    val promptOpen: Boolean = false,
    /** 最近一次连接失败原因，供弹窗展示。 */
    val error: String? = null,
) {
    val connected: Boolean get() = phase == PcConnectionPhase.CONNECTED
    val busy: Boolean get() = phase == PcConnectionPhase.SEARCHING || phase == PcConnectionPhase.CONNECTING
}

/**
 * 电脑接收器自动连接。
 *
 * 只在 App 回到前台时检查一次：先验证已保存地址，失败才做一次 UDP 广播搜索；
 * 连接成功后持久化地址与名称。没有轮询、没有常驻 socket，前台重复进入有冷却时间。
 * 搜索失败时最多自动弹一次手动输入窗口，之后由用户点状态栏主动打开。
 */
class PcConnectionManager(
    private val settings: SettingsAccess,
    private val healthCheck: suspend (String) -> PcHealthResult,
    private val discovery: suspend () -> List<DiscoveredPc>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val _state = MutableStateFlow(PcConnectionState())
    val state: StateFlow<PcConnectionState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val inFlight = AtomicBoolean(false)
    private var lastForegroundCheckAt = 0L
    private var autoPromptUsed = false

    /** Activity 每次回到前台调用；冷却期内直接返回。 */
    fun onAppForeground() {
        val now = clock()
        val minInterval = if (_state.value.connected) {
            RECHECK_CONNECTED_MS
        } else {
            RECHECK_DISCONNECTED_MS
        }
        if (lastForegroundCheckAt != 0L && now - lastForegroundCheckAt < minInterval) return
        lastForegroundCheckAt = now
        startConnect(promptIfFail = !autoPromptUsed, markAutoPrompt = !autoPromptUsed)
    }

    /** 弹窗里的「重新搜索」。 */
    fun search() {
        _state.update { it.copy(promptOpen = true, error = null) }
        startConnect(promptIfFail = true, markAutoPrompt = false)
    }

    /** 手动输入地址并连接。 */
    fun connectManual(rawUrl: String) {
        val normalized = PcClient.normalizeBaseUrl(rawUrl)
        if (normalized == null) {
            _state.update { it.copy(error = INVALID_URL) }
            return
        }
        startConnect(promptIfFail = false, markAutoPrompt = false) {
            _state.update { it.copy(phase = PcConnectionPhase.CONNECTING, url = normalized, error = null) }
            when (val result = health(normalized)) {
                is PcHealthResult.Ok -> {
                    val name = displayName(normalized, null)
                    persistConnection(normalized, name)
                    markConnected(normalized, name)
                }

                is PcHealthResult.Error -> _state.update {
                    it.copy(phase = PcConnectionPhase.DISCONNECTED, error = result.message)
                }
            }
        }
    }

    /** 用户点首页状态栏 / 发送页「连接电脑」。 */
    fun requestPrompt() {
        _state.update { it.copy(promptOpen = true, error = null) }
    }

    fun dismissPrompt() {
        _state.update { it.copy(promptOpen = false, error = null) }
    }

    /** 发送成功后同步状态（即使自动连接尚未完成）。 */
    fun onSendSucceeded(url: String) {
        if (url.isBlank()) return
        val current = _state.value
        val name = if (current.url == url) current.name else displayName(url, null)
        _state.update {
            it.copy(
                phase = PcConnectionPhase.CONNECTED,
                url = url,
                name = name,
                promptOpen = false,
                error = null,
            )
        }
    }

    /** 只有网络层失败才把状态改为未连接；409/413 等业务错误保持在线。 */
    fun onSendFailed(url: String, networkError: Boolean, message: String) {
        if (!networkError) return
        _state.update {
            it.copy(
                phase = PcConnectionPhase.DISCONNECTED,
                url = it.url.ifBlank { url },
                error = message,
            )
        }
    }

    private fun startConnect(
        promptIfFail: Boolean,
        markAutoPrompt: Boolean,
        block: (suspend () -> Unit)? = null,
    ) {
        if (!inFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                mutex.withLock {
                    if (block != null) {
                        block()
                    } else {
                        discoverAndConnect(promptIfFail, markAutoPrompt)
                    }
                }
            } finally {
                inFlight.set(false)
            }
        }
    }

    private suspend fun discoverAndConnect(promptIfFail: Boolean, markAutoPrompt: Boolean) {
        val snapshot = runCatching { settings.current() }.getOrNull()
        val savedUrl = snapshot?.pcUrl.orEmpty().trim()
        val savedName = snapshot?.pcName.orEmpty().trim()
        _state.update {
            it.copy(
                phase = PcConnectionPhase.SEARCHING,
                url = savedUrl,
                name = savedName,
                error = null,
            )
        }

        if (savedUrl.isNotBlank() && health(savedUrl) is PcHealthResult.Ok) {
            markConnected(savedUrl, displayName(savedUrl, savedName))
            return
        }
        if (_state.value.connected) return

        val found = runCatching { discovery() }.getOrDefault(emptyList())
        val candidates = found
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<DiscoveredPc> { it.url == savedUrl }
                    .thenBy { it.name.lowercase() },
            )
        for (pc in candidates.take(MAX_AUTO_CONNECT_CANDIDATES)) {
            if (health(pc.url) is PcHealthResult.Ok) {
                persistConnection(pc.url, pc.name)
                markConnected(pc.url, pc.name)
                return
            }
        }

        // 搜索期间可能已有发送成功把状态置为在线，不能用旧结果覆盖。
        if (_state.value.connected) return

        if (markAutoPrompt) autoPromptUsed = true
        _state.update {
            it.copy(
                phase = PcConnectionPhase.DISCONNECTED,
                promptOpen = it.promptOpen || (promptIfFail && markAutoPrompt),
            )
        }
    }

    private suspend fun health(url: String): PcHealthResult =
        runCatching { healthCheck(url) }
            .getOrElse { PcHealthResult.Error(it.message ?: NETWORK_ERROR) }

    private suspend fun persistConnection(url: String, name: String) {
        runCatching { settings.setPcUrl(url) }
        runCatching { settings.setPcName(name) }
    }

    private fun markConnected(url: String, name: String) {
        _state.update {
            it.copy(
                phase = PcConnectionPhase.CONNECTED,
                url = url,
                name = name,
                promptOpen = false,
                error = null,
            )
        }
    }

    private fun displayName(url: String, discoveredName: String?): String =
        discoveredName?.trim()?.takeIf { it.isNotEmpty() }
            ?: runCatching { java.net.URI(url).host }.getOrNull().orEmpty()

    private companion object {
        const val RECHECK_CONNECTED_MS = 60_000L
        const val RECHECK_DISCONNECTED_MS = 60_000L
        const val MAX_AUTO_CONNECT_CANDIDATES = 3
        const val INVALID_URL = "地址格式不正确"
        const val NETWORK_ERROR = "网络错误"
    }
}
