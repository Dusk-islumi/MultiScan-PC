package com.example.multiscan.data.pc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** 局域网自动发现到的一台电脑接收器。 */
data class DiscoveredPc(
    val name: String,
    val host: String,
    val port: Int,
    val version: String,
) {
    val url: String get() = "http://$host:$port"
}

/**
 * 通过 UDP 广播搜索电脑接收器（`MULTISCAN_DISCOVER_V1`）。
 *
 * 接收器在 TCP 同号端口上监听 UDP 并回复 JSON；手机同时向全局广播和
 * 各网卡子网广播各发一次，避免部分路由器丢弃 255.255.255.255。
 */
class PcDiscoveryClient {

    suspend fun discover(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): List<DiscoveredPc> =
        withContext(Dispatchers.IO) {
            val request = REQUEST.toByteArray(Charsets.UTF_8)
            val found = LinkedHashMap<String, DiscoveredPc>()

            // 显式绑定 IPv4 通配地址，避免默认 IPv6 socket 无法发送 IPv4 广播。
            DatagramSocket(0, InetAddress.getByName("0.0.0.0")).use { socket ->
                socket.broadcast = true
                socket.soTimeout = RECEIVE_SLICE_MILLIS

                broadcastDestinations().forEach { address ->
                    runCatching {
                        socket.send(DatagramPacket(request, request.size, address, DISCOVERY_PORT))
                    }
                }

                val deadline = System.currentTimeMillis() + timeoutMillis
                val buffer = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val pc = parse(packet) ?: continue
                    val key = "${pc.host}:${pc.port}"
                    if (!found.containsKey(key)) found[key] = pc
                }
            }

            found.values.sortedBy { it.name.lowercase() }
        }

    private fun broadcastDestinations(): List<InetAddress> {
        val result = linkedSetOf<InetAddress>()
        runCatching { result += InetAddress.getByName("255.255.255.255") }
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
                .filterIsInstance<Inet4Address>()
                .forEach { result += it }
        }
        return result.toList()
    }

    private fun parse(packet: DatagramPacket): DiscoveredPc? {
        val root = runCatching {
            Json.parseToJsonElement(
                String(packet.data, packet.offset, packet.length, Charsets.UTF_8),
            ).jsonObject
        }.getOrNull() ?: return null

        if (root["ok"]?.jsonPrimitive?.contentOrNull != "true") return null
        if (root["service"]?.jsonPrimitive?.contentOrNull != SERVICE) return null
        val port = root["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        val host = packet.address?.hostAddress ?: return null
        val name = root["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: host
        val version = root["version"]?.jsonPrimitive?.contentOrNull ?: ""
        return DiscoveredPc(name = name, host = host, port = port, version = version)
    }

    private companion object {
        const val DISCOVERY_PORT = 8765
        const val REQUEST = "MULTISCAN_DISCOVER_V1"
        const val SERVICE = "multiscan-pc"
        const val DEFAULT_TIMEOUT_MILLIS = 1600L
        const val RECEIVE_SLICE_MILLIS = 250
    }
}
