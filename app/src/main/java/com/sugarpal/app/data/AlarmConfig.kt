package com.sugarpal.app.data

import android.content.Context
import java.io.File
import java.util.Locale
import java.util.Properties

/**
 * 报警阈值配置（P0-1）。
 *
 * 键名与 PC 端 AlarmConfig 完全一致（alarm.low / alarm.high），默认低阈值 3.9、高阈值 13.9
 * （与 AGP 指标口径一致）。数据写入 filesDir/config.properties，与 AI 配置同文件互不覆盖：
 * 每次读写前重新加载磁盘属性，避免覆盖对方已保存的键。
 */
object AlarmConfig {

    const val DEFAULT_LOW = 3.9
    const val DEFAULT_HIGH = 13.9

    private const val KEY_LOW = "alarm.low"
    private const val KEY_HIGH = "alarm.high"

    private fun file(context: Context): File = File(context.filesDir, AiConfig.PROP_FILE_NAME)

    private fun read(context: Context): Properties {
        val props = Properties()
        val f = file(context)
        if (f.exists()) {
            runCatching { f.inputStream().use { props.load(it) } }
        }
        return props
    }

    fun low(context: Context): Double =
        read(context).getProperty(KEY_LOW)?.toDoubleOrNull() ?: DEFAULT_LOW

    fun high(context: Context): Double =
        read(context).getProperty(KEY_HIGH)?.toDoubleOrNull() ?: DEFAULT_HIGH

    /** 保存阈值；合法区间校验由调用方（设置对话框）负责，这里仅做落盘 */
    fun save(context: Context, low: Double, high: Double) {
        val props = read(context)
        props.setProperty(KEY_LOW, String.format(Locale.US, "%.1f", low))
        props.setProperty(KEY_HIGH, String.format(Locale.US, "%.1f", high))
        val f = file(context)
        f.parentFile?.mkdirs()
        f.outputStream().use { props.store(it, "Blood Sugar config. Keys are stored locally only.") }
    }
}
