package com.onmyway.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

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
    private suspend fun request(path: String, body: JSONObject? = null): String = withContext(Dispatchers.IO) {
        val connection = URI(validateBase(base) + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = if (body == null) 30_000 else 240_000
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val result = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(result).get("detail") }.getOrNull()
                error(if (detail is String) detail else "输入未通过校验或服务异常（HTTP $status），请检查时间和任务信息。")
            }
            result
        } finally { connection.disconnect() }
    }
    suspend fun health() = JSONObject(request("/health"))
    suspend fun navigation(text: String, destination: String, tasks: List<Errand>, history: JSONArray = JSONArray(), proposed: JSONObject? = null) = JSONObject(request("/assistant/navigation",
        JSONObject().put("text", text).put("destination", destination).put("tasks", JSONArray(tasks.map {
            JSONObject().put("id", it.id).put("title", it.title).put("place_type", it.placeType)
        })).put("history", history).put("proposed_destination", proposed?.getJSONObject("destination")?.getString("name") ?: "")
            .put("proposed_task_ids", proposed?.getJSONArray("tasks")?.let { tasks ->
                JSONArray((0 until tasks.length()).map { tasks.getJSONObject(it).getString("id") })
            } ?: JSONObject.NULL)))
    suspend fun assistant(messages: JSONArray) = JSONObject(request("/assistant/chat",
        JSONObject().put("messages", messages).put("now", java.time.OffsetDateTime.now().toString())))
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
