package com.sugarpal.app.data

import android.content.Context

/**
 * 应用内更新配置（P0-3）。
 *
 * 更新源地址为空时使用默认 GitHub Releases 接口；
 * 下载加速地址为空时使用内置镜像前缀列表（依次尝试，失败回退直连）。
 * 存储使用 SharedPreferences（仅本机）。
 */
object UpdateConfig {

    private const val PREFS = "update_config"
    private const val KEY_SOURCE_URL = "source_url"
    private const val KEY_MIRROR_URL = "mirror_url"

    /** 默认更新源：GitHub Releases latest API（返回 GitHub Releases JSON 结构） */
    const val DEFAULT_SOURCE_URL =
        "https://api.github.com/repos/startime-ltk/SugarPal-Android/releases/latest"

    /** 内置下载加速镜像前缀（按顺序尝试，任一失败自动回退下一项，最后回退直连） */
    val DEFAULT_MIRRORS = listOf(
        "https://ghproxy.net/",
        "https://github.moeyy.xyz/",
        "https://gh.llkk.cc/"
    )

    fun sourceUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SOURCE_URL, "").orEmpty().trim()

    fun saveSourceUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SOURCE_URL, url.trim())
            .apply()
    }

    fun mirrorUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MIRROR_URL, "").orEmpty().trim()

    fun saveMirrorUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MIRROR_URL, url.trim())
            .apply()
    }
}
