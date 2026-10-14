package com.sugarpal.app.data

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新源检查（P0-3，GitHub Releases 版）。
 *
 * 通过 HttpURLConnection 请求更新源 JSON（不引入新依赖），兼容两种结构：
 * 1. GitHub Releases：{tag_name, body, assets:[{name, browser_download_url}]}（默认源）
 * 2. 自定义 HTTP(S) JSON：{versionName, versionCode, apkUrl, note}（旧格式保留）
 */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val apkUrl: String,
    val note: String,
    val apkName: String = ""
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
            conn.setRequestProperty("User-Agent", "SugarPal-Android")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(text)
            val tag = json.optString("tag_name", "")
            return if (tag.isNotBlank()) parseGithubRelease(json) else parseCustom(json)
        } finally {
            conn.disconnect()
        }
    }

    /** GitHub Releases 结构：tag_name 去 v 前缀作为版本号，body 作为更新说明 */
    private fun parseGithubRelease(json: JSONObject): UpdateInfo {
        val versionName = json.optString("tag_name", "").removePrefix("v").trim()
        val note = json.optString("body", "").trim()
        var apkUrl = ""
        var apkName = ""
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name", "")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                // 多个 APK 时优先名称含“糖伴”的正式包
                if (apkUrl.isBlank() || name.contains("糖伴")) {
                    apkUrl = a.optString("browser_download_url", "")
                    apkName = name
                }
            }
        }
        return UpdateInfo(versionName, 0, apkUrl, note, apkName)
    }

    /** 自定义 JSON 结构（旧格式兼容） */
    private fun parseCustom(json: JSONObject): UpdateInfo =
        UpdateInfo(
            versionName = json.optString("versionName", "").trim(),
            versionCode = json.optInt("versionCode", 0),
            apkUrl = json.optString("apkUrl", "").trim(),
            note = json.optString("note", "").trim()
        )

    /**
     * 语义化版本号比较（忽略前导 v），返回 a>b ? 1 : a<b ? -1 : 0。
     * 用于 GitHub Releases 源（无 versionCode 字段）与 BuildConfig.VERSION_NAME 对比。
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.trim().trimStart('v').split('.').mapNotNull { it.toIntOrNull() }
        val pb = b.trim().trimStart('v').split('.').mapNotNull { it.toIntOrNull() }
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }
}
