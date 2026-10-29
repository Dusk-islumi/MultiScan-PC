package com.example.multiscan.data.prefs

import kotlinx.coroutines.flow.StateFlow

/**
 * 设置的可测试抽象（生产实现：[SettingsStore]，JVM 单测用 fake）。
 *
 * Repository / SyncEngine / NetworkFactory 只依赖本接口，避免在纯 JVM 单测里触碰
 * Android DataStore / Keystore。
 */
interface SettingsAccess {

    val snapshot: StateFlow<SettingsSnapshot>

    suspend fun current(): SettingsSnapshot

    /** 解密后的 token；未注册或解密失败返回 null。 */
    suspend fun token(): String?

    suspend fun setServerUrl(url: String)

    suspend fun setEnrollSecret(secret: String)

    suspend fun saveRegistration(deviceId: String, token: String)

    suspend fun setOverlayVisible(visible: Boolean)

    suspend fun setLastSync(atUtcIso: String?, status: String)

    suspend fun clearRegistration()

    // ---- v3 同步设置（契约 §13.1） ----

    /** 扫描完成后是否自动上传。 */
    suspend fun setAutoSync(enabled: Boolean)

    /** 是否仅在 Wi-Fi / 不计费网络同步；切换不会清本地数据、不会重新注册。 */
    suspend fun setSyncWifiOnly(enabled: Boolean)

    /** 同步开始：只把 last_sync_status 置为 [SettingsSnapshot.SYNC_RUNNING]（不动 last_sync_at）。 */
    suspend fun setSyncRunning()

    // ---- 扫码枪版：电脑接收器 ----

    /** 保存电脑地址（只 trim，不改写协议）。 */
    suspend fun setPcUrl(url: String)

    /** 保存自动发现到的电脑名称。 */
    suspend fun setPcName(name: String)
}
