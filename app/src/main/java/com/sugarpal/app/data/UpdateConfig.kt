package com.sugarpal.app.data

import android.content.Context

/**
 * 应用内更新配置（P0-3）。
 *
 * 更新源地址为 HTTP(S) JSON，格式：
 * {"versionName":"x.y.z","versionCode":N,"apkUrl":"https://.../糖伴SugarPal-x.y.z.apk","note":"更新说明"}
 * 默认留空；未配置时检查更新直接提示用户先配置。
 * 存储使用 SharedPreferences（仅本机，与 PC 端 config.properties 无关）。
 */
object UpdateConfig {

    private const val PREFS = "update_config"
    private const val KEY_SOURCE_URL = "source_url"

    fun sourceUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SOURCE_URL, "").orEmpty().trim()

    fun saveSourceUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SOURCE_URL, url.trim())
            .apply()
    }
}
