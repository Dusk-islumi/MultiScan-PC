package com.example.multiscan.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.multiscan.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

/** 首页数据：扫描统计。电脑连接状态由 AppContainer 的 PcConnectionManager 统一提供。 */
class HomeViewModel(container: AppContainer) : ViewModel() {

    val stats = container.scanRepository.homeStats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
