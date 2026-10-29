package com.example.multiscan.data.pc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed interface PcHealthResult {
    data class Ok(val version: String) : PcHealthResult
    data class Error(val message: String) : PcHealthResult
}

sealed interface PcSendResult {
    data class Ok(val typed: Int) : PcSendResult

    /** [networkError] 为 true 表示没连上电脑（地址错误 / 接收器未启动 / 超时）。 */
    data class Error(val message: String, val networkError: Boolean = false) : PcSendResult
}

/**
 * 电脑接收器（pc-receiver）的极简 HTTP 客户端。
 *
 * 协议：
 *  - GET  /health      → {"ok":true,"version":"1.1"}
 *  - POST /v1/type     → body {"batch_id":..., "mode":"enter", "items":[...]}
 *
 * 接收器在电脑端逐条模拟键盘输入并回车；本类只负责发送，不记录条码。
 */
class PcClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** 自动连接只做快速探测，限制整次请求耗时，避免拖住状态展示。 */
    private val healthClient = client.newBuilder()
        .callTimeout(HEALTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun health(rawBaseUrl: String): PcHealthResult = withContext(Dispatchers.IO) {
        val base = normalizeBaseUrl(rawBaseUrl)
            ?: return@withContext PcHealthResult.Error("电脑地址格式不正确")
        val request = Request.Builder()
            .url("$base/health")
            .get()
            .build()
        runCatching {
            healthClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use PcHealthResult.Error("电脑端返回 HTTP ${response.code}")
                }
                val root = Json.parseToJsonElement(response.body.string()).jsonObject
                if (root["ok"]?.jsonPrimitive?.content != "true") {
                    return@use PcHealthResult.Error("电脑端响应异常")
                }
                PcHealthResult.Ok(root["version"]?.jsonPrimitive?.content ?: "1.0")
            }
        }.getOrElse { throwable ->
            PcHealthResult.Error(throwable.toMessage())
        }
    }

    suspend fun send(
        rawBaseUrl: String,
        codes: List<String>,
    ): PcSendResult = withContext(Dispatchers.IO) {
        if (codes.isEmpty()) return@withContext PcSendResult.Error("没有可发送的条码")

        val base = normalizeBaseUrl(rawBaseUrl)
            ?: return@withContext PcSendResult.Error("电脑地址格式不正确")

        val payload = buildJsonObject {
            put("batch_id", UUID.randomUUID().toString())
            put("mode", "enter")
            putJsonArray("items") { codes.forEach { add(it) } }
        }.toString()

        val request = Request.Builder()
            .url("$base/v1/type")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.isSuccessful) {
                    // 接收器先受理后异步输入，返回 accepted；兼容旧字段 typed。
                    val accepted = runCatching {
                        val root = Json.parseToJsonElement(body).jsonObject
                        (root["accepted"] ?: root["typed"])?.jsonPrimitive?.content?.toIntOrNull()
                    }.getOrNull() ?: codes.size
                    return@use PcSendResult.Ok(accepted)
                }
                val message = when (response.code) {
                    409 -> "电脑端正在输入，请稍后重试"
                    413 -> "条码太多，请减少选择数量"
                    400 -> "发送内容无效"
                    else -> "电脑端返回 HTTP ${response.code}"
                }
                PcSendResult.Error(message)
            }
        }.getOrElse { throwable ->
            PcSendResult.Error(throwable.toMessage(), networkError = throwable is IOException)
        }
    }

    private fun Throwable.toMessage(): String = when (this) {
        is java.net.SocketTimeoutException -> "连接电脑超时，请检查地址和接收器状态"
        is java.net.ConnectException -> "连接不到电脑，请确认接收器已启动、地址正确且防火墙已放行"
        else -> message ?: "网络错误"
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 3L
        private const val READ_TIMEOUT_SECONDS = 10L
        private const val WRITE_TIMEOUT_SECONDS = 6L
        private const val HEALTH_TIMEOUT_SECONDS = 3L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 接受 `192.168.1.10:8765` / `http://192.168.1.10:8765/` 等写法，统一为无尾斜杠的 base URL。 */
        fun normalizeBaseUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "http://$trimmed"
            }
            val url = withScheme.toHttpUrlOrNull() ?: return null
            if (url.host.isBlank()) return null
            return url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
        }
    }
}
