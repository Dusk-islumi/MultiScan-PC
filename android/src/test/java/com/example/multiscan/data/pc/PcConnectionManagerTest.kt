package com.example.multiscan.data.pc

import com.example.multiscan.data.FakeSettings
import com.example.multiscan.data.prefs.SettingsSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PcConnectionManagerTest {

    private val healthyUrls = mutableSetOf<String>()
    private val healthCalls = mutableListOf<String>()
    private var discovered: List<DiscoveredPc> = emptyList()
    private var discoveryCalls = 0
    private var now = 1_000L

    private fun TestScope.manager(settings: FakeSettings): PcConnectionManager =
        PcConnectionManager(
            settings = settings,
            healthCheck = { url ->
                healthCalls += url
                if (url in healthyUrls) {
                    PcHealthResult.Ok("1.1")
                } else {
                    PcHealthResult.Error("连接不到电脑")
                }
            },
            discovery = {
                discoveryCalls++
                discovered
            },
            scope = this,
            clock = { now },
        )

    @Test
    fun `foreground connects to saved address without discovery`() = runTest {
        val saved = "http://192.168.1.10:8765"
        healthyUrls += saved
        val settings = FakeSettings(SettingsSnapshot(pcUrl = saved, pcName = "Desk"))
        val manager = manager(settings)

        manager.onAppForeground()
        advanceUntilIdle()

        assertEquals(PcConnectionPhase.CONNECTED, manager.state.value.phase)
        assertEquals("Desk", manager.state.value.name)
        assertEquals(0, discoveryCalls)
        assertEquals(listOf(saved), healthCalls)
    }

    @Test
    fun `foreground discovers and persists when no address is saved`() = runTest {
        discovered = listOf(DiscoveredPc(name = "Office", host = "192.168.1.20", port = 8765, version = "1.1"))
        healthyUrls += "http://192.168.1.20:8765"
        val settings = FakeSettings()
        val manager = manager(settings)

        manager.onAppForeground()
        advanceUntilIdle()

        assertEquals(PcConnectionPhase.CONNECTED, manager.state.value.phase)
        assertEquals("Office", manager.state.value.name)
        assertEquals("http://192.168.1.20:8765", settings.value.pcUrl)
        assertEquals("Office", settings.value.pcName)
        assertFalse(manager.state.value.promptOpen)
    }

    @Test
    fun `failure opens manual prompt only once`() = runTest {
        val settings = FakeSettings()
        val manager = manager(settings)

        manager.onAppForeground()
        advanceUntilIdle()

        assertEquals(PcConnectionPhase.DISCONNECTED, manager.state.value.phase)
        assertTrue(manager.state.value.promptOpen)

        manager.dismissPrompt()
        now += 61_000
        manager.onAppForeground()
        advanceUntilIdle()

        assertEquals(PcConnectionPhase.DISCONNECTED, manager.state.value.phase)
        assertFalse(manager.state.value.promptOpen)
        assertEquals(2, discoveryCalls)
    }

    @Test
    fun `manual connect saves normalized address and name`() = runTest {
        healthyUrls += "http://192.168.1.30:8765"
        val settings = FakeSettings()
        val manager = manager(settings)

        manager.connectManual("192.168.1.30:8765")
        advanceUntilIdle()

        assertEquals(PcConnectionPhase.CONNECTED, manager.state.value.phase)
        assertEquals("192.168.1.30", manager.state.value.name)
        assertEquals("http://192.168.1.30:8765", settings.value.pcUrl)
        assertEquals("192.168.1.30", settings.value.pcName)
    }

    @Test
    fun `invalid manual address reports error without probing`() = runTest {
        val settings = FakeSettings()
        val manager = manager(settings)

        manager.connectManual("not a url")
        advanceUntilIdle()

        assertNotNull(manager.state.value.error)
        assertTrue(healthCalls.isEmpty())
        assertEquals(PcConnectionPhase.IDLE, manager.state.value.phase)
    }

    @Test
    fun `business send error keeps connection while network error disconnects`() = runTest {
        val url = "http://192.168.1.40:8765"
        val settings = FakeSettings()
        val manager = manager(settings)
        manager.onSendSucceeded(url)

        manager.onSendFailed(url, networkError = false, message = "电脑端正在输入")
        assertEquals(PcConnectionPhase.CONNECTED, manager.state.value.phase)

        manager.onSendFailed(url, networkError = true, message = "连接电脑超时")
        assertEquals(PcConnectionPhase.DISCONNECTED, manager.state.value.phase)
    }

    @Test
    fun `foreground checks are throttled for one minute`() = runTest {
        val saved = "http://192.168.1.50:8765"
        healthyUrls += saved
        val settings = FakeSettings(SettingsSnapshot(pcUrl = saved))
        val manager = manager(settings)

        manager.onAppForeground()
        advanceUntilIdle()
        assertEquals(1, healthCalls.size)

        now += 30_000
        manager.onAppForeground()
        advanceUntilIdle()
        assertEquals(1, healthCalls.size)

        now += 31_000
        manager.onAppForeground()
        advanceUntilIdle()
        assertEquals(2, healthCalls.size)
    }
}
