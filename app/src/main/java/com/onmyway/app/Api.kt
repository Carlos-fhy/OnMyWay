package com.onmyway.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

internal class ApiHttpException(val status: Int, message: String) : java.io.IOException(message)

class Api(private val base: String) {
    companion object {
        fun validateBase(value: String): String {
            val uri = URI(value.trim().trimEnd('/'))
            require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/")) {
                "请输入有效服务地址，例如 http://127.0.0.1:8000"
            }
            return value.trim().trimEnd('/')
        }
    }
    private suspend fun request(path: String, body: JSONObject? = null, method: String = if (body == null) "GET" else "POST", backupToken: String? = null): String {
        try {
            return requestOnce(path, body, method, backupToken)
        } catch (error: java.io.IOException) {
            // Retry only durable proposal requests, preserving the exact body and request ID.
            // Explicit HTTP failures (including in-progress 409) require user action.
            if (error is ApiHttpException || path !in setOf("/assistant/chat", "/assistant/navigation") ||
                body?.optString("request_id").isNullOrBlank()) throw error
            kotlinx.coroutines.delay(500)
            return requestOnce(path, body, method, backupToken)
        }
    }
    private suspend fun requestOnce(path: String, body: JSONObject?, method: String, backupToken: String?): String = withContext(Dispatchers.IO) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val connection = URI(validateBase(base) + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = when {
                path.startsWith("/assistant/") -> 60_000
                path == "/location" -> 45_000
                body == null -> 30_000
                else -> 240_000
            }
            connection.setRequestProperty("Accept", "application/json")
            connection.requestMethod = method
            if (backupToken != null) connection.setRequestProperty("X-Backup-Token", backupToken)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val result = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(result).get("detail") }.getOrNull()
                throw ApiHttpException(status, if (detail is String) detail else "输入未通过校验或服务异常（HTTP $status），请检查时间和任务信息。")
            }
            result
        } finally {
            // Only endpoint and elapsed time: never log query, content or credentials.
            android.util.Log.i("OnMyWayTiming", "endpoint=" + path.substringBefore('?') +
                " elapsed_ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt))
            connection.disconnect()
        }
    }
    private fun requirePrivateBackupTransport() {
        val uri = URI(validateBase(base))
        require(uri.scheme == "https" || uri.host in setOf("127.0.0.1", "localhost", "10.0.2.2")) {
            "备份口令只能通过 HTTPS 或本机 USB 转发传输"
        }
    }
    suspend fun saveBackup(token: String, snapshot: JSONObject) {
        requirePrivateBackupTransport()
        request("/backup", snapshot, "PUT", token)
    }
    suspend fun loadBackup(token: String): JSONObject {
        requirePrivateBackupTransport()
        return JSONObject(request("/backup", backupToken = token))
    }
    suspend fun readSync(token: String): JSONObject {
        requirePrivateBackupTransport()
        return JSONObject(request("/sync", backupToken = token))
    }
    suspend fun writeSync(token: String, body: JSONObject): JSONObject {
        requirePrivateBackupTransport()
        return JSONObject(request("/sync", body, "PUT", token))
    }
    suspend fun health() = JSONObject(request("/health"))
    suspend fun navigationRequest(body: JSONObject) = JSONObject(request("/assistant/navigation", body))
    suspend fun navigationStatus(body: JSONObject) = JSONObject(request("/assistant/navigation/status", body))
    suspend fun assistant(body: JSONObject) = JSONObject(request("/assistant/chat", body))
    suspend fun assistantStatus(body: JSONObject) = JSONObject(request("/assistant/chat/status", body))
    suspend fun transcribe(audio: ByteArray) = JSONObject(request("/assistant/transcribe",
        JSONObject().put("audio", android.util.Base64.encodeToString(audio, android.util.Base64.NO_WRAP)))).getString("text")
    suspend fun currentPlace(longitude: Double, latitude: Double): Place = Place.from(JSONObject(request(
        "/location", JSONObject().put("longitude", longitude).put("latitude", latitude)
    )))
    suspend fun search(keyword: String, city: String): List<Place> {
        val result = JSONArray(request("/places?q=${URLEncoder.encode(keyword, "UTF-8")}&city=${URLEncoder.encode(city, "UTF-8")}"))
        return (0 until result.length()).map { Place.from(result.getJSONObject(it)) }
    }
    suspend fun plan(body: JSONObject) = JSONObject(request("/plans", body))
    suspend fun recommend(body: JSONObject) = JSONObject(request("/recommendations", body))
}
