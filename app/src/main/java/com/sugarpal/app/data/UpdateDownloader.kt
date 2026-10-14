package com.sugarpal.app.data

import android.util.Log
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新 APK 下载（P0-3，GitHub Releases 版）。
 *
 * HttpURLConnection 流式下载到应用外部缓存目录（cacheDir/update），
 * 支持加速镜像前缀列表：依次尝试，任一失败自动回退下一项，最后回退直连 browser_download_url。
 * 完成后由调用方经 FileProvider 提供 URI 调系统安装器安装。
 */
object UpdateDownloader {

    /** 下载 APK 到目标文件；失败抛出异常，成功且文件非空返回 true */
    fun download(url: String, target: File, timeoutMs: Int = 30_000): Boolean {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.android.package-archive")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            target.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        output.write(buf, 0, n)
                    }
                }
            }
            return target.length() > 0
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 带加速镜像的下载：按 mirrors 顺序尝试“镜像前缀 + 原始下载地址”，
     * 全部失败后回退直连原始地址；任一成功立即返回。
     * 镜像通常需要剥掉原始地址的协议头再拼接（如 ghproxy.net/https://github.com/...）。
     */
    fun downloadWithMirrors(
        apkUrl: String,
        mirrors: List<String>,
        target: File,
        timeoutMs: Int = 30_000
    ): Boolean {
        for (m in mirrors) {
            val prefix = m.trim().trimEnd('/')
            if (prefix.isEmpty()) continue
            // 镜像标准用法：前缀 + 完整下载地址（含协议头），如 https://ghproxy.net/https://github.com/...
            val base = if (prefix.contains("://")) prefix else "https://$prefix"
            if (tryDownload("$base/$apkUrl", target, timeoutMs)) return true
        }
        return tryDownload(apkUrl, target, timeoutMs)
    }

    /** 单次下载尝试；失败清理残留文件并返回 false（供镜像回退） */
    private fun tryDownload(url: String, target: File, timeoutMs: Int): Boolean =
        try {
            download(url, target, timeoutMs)
        } catch (e: Exception) {
            Log.e("UpdateDL", "tryDownload failed url=$url msg=${e.message}")
            if (target.exists()) target.delete()
            false
        }
}
