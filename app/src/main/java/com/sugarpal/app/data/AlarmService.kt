package com.sugarpal.app.data

/**
 * 异常报警判定（P0-1）。
 *
 * 判定规则与 PC 端 AlarmService 完全一致：低于低阈值报警偏低，高于高阈值报警偏高；
 * 阈值默认 3.9 / 13.9，可配置。仅本地即时判定，不联网、不做后台常驻扫描。
 */
object AlarmService {

    const val STATE_NORMAL = 0
    const val STATE_LOW = 1
    const val STATE_HIGH = 2

    fun evaluate(value: Double, low: Double, high: Double): Int = when {
        value < low -> STATE_LOW
        value > high -> STATE_HIGH
        else -> STATE_NORMAL
    }

    fun isAlarm(state: Int): Boolean = state != STATE_NORMAL

    fun describe(state: Int): String = when (state) {
        STATE_LOW -> "血糖偏低"
        STATE_HIGH -> "血糖偏高"
        else -> "血糖正常"
    }
}
