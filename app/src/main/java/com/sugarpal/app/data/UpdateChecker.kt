package com.sugarpal.app.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新源检查（P0-3）。
 *
 * 通过 HttpURLConnection 请求更新源 JSON（不引入新依赖），解析出新版本信息；
 * 版本对比由调用方完成（versionCode / versionName 与 BuildConfig 对比）。
 */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val note: String
)

object UpdateChecker {

    /** 请求更新源 JSON；网络错误 / 格式非法时抛出异常由调用方兜底 */
    fun fetch(url: String, timeoutMs: Int = 10_000): UpdateInfo {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(text)
            return UpdateInfo(
                versionName = json.optString("versionName", ""),
                versionCode = json.optInt("versionCode", 0),
                apkUrl = json.optString("apkUrl", ""),
                note = json.optString("note", "")
            )
        } finally {
            conn.disconnect()
        }
    }
}
