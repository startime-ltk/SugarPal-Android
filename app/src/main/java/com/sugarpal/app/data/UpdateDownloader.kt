package com.sugarpal.app.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新 APK 下载（P0-3）。
 *
 * HttpURLConnection 流式下载到应用外部缓存目录（cacheDir/update），
 * 完成后由调用方经 FileProvider 提供 URI 调系统安装器安装。
 */
object UpdateDownloader {

    /** 下载 APK 到目标文件；失败抛出异常，成功且文件非空返回 true */
    fun download(url: String, target: java.io.File, timeoutMs: Int = 30_000): Boolean {
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
}
