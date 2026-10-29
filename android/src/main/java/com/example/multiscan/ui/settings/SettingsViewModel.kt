package com.example.multiscan.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import com.example.multiscan.R

/** 设置页只负责设备信息；电脑连接由 PcConnectionManager 统一管理。 */
class SettingsViewModel(context: Context) : ViewModel() {

    val appVersion: String = resolveAppVersion(context.applicationContext)
}

@Suppress("DEPRECATION")
private fun resolveAppVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
        ?: context.getString(R.string.common_unknown)
}.getOrDefault(context.getString(R.string.common_unknown))
