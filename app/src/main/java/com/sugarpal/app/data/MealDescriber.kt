package com.sugarpal.app.data

import com.bloodsugar.util.PeriodClassifier
import java.time.Duration
import java.time.LocalDateTime

/**
 * 界面展示用餐别描述：对齐 PC 端 MainUI.resolveNearestMealTime + computeMealTypeDescription。
 *
 * 与 PeriodClassifier.classifyMealType 并存（后者写入 meal_type 字段），
 * 本类仅负责列表/对话框上的口语化描述文案。
 */
object MealDescriber {

    /** 最近的一顿：餐名 + 用餐时间 */
    data class NearestMeal(val name: String, val time: LocalDateTime)

    /**
     * 找"最近的一顿"：只接受不晚于测量时间的用餐时间，多个候选取最近者。
     * 数据来源优先级口径与 PC 端一致：面板用原餐名，表/历史记录用"用餐"。
     */
    fun resolveNearestMealTime(
        recordTime: LocalDateTime?,
        panelMealTimes: Map<String, LocalDateTime>,
        savedMealTimes: Map<String, LocalDateTime>,
        historyMealTime: LocalDateTime?
    ): NearestMeal? {
        if (recordTime == null) return null
        var best: NearestMeal? = null

        fun consider(name: String, t: LocalDateTime?) {
            if (t == null || t.isAfter(recordTime)) return
            val cur = best
            if (cur == null || t.isAfter(cur.time)) best = NearestMeal(name, t)
        }

        panelMealTimes.forEach { (name, t) -> consider(name, t) }
        savedMealTimes.forEach { (_, t) -> consider("用餐", t) }
        consider("用餐", historyMealTime)
        return best
    }

    /** 生成描述文案 */
    fun describe(recordTime: LocalDateTime?, nearest: NearestMeal?): String {
        if (recordTime == null) return "空腹"
        if (nearest == null) return "空腹"
        if (recordTime.hour >= 22) return "睡前"
        val minutes = Duration.between(nearest.time, recordTime).toMinutes()
        if (minutes < 0) return "空腹"
        if (minutes < 60) return "${nearest.name}后 ${minutes}分钟"
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0L) "${nearest.name}后 ${h}小时" else "${nearest.name}后 ${h}小时${m}分钟"
    }

    fun descriptionOf(
        recordTime: LocalDateTime?,
        panelMealTimes: Map<String, LocalDateTime>,
        savedMealTimes: Map<String, LocalDateTime>,
        historyMealTime: LocalDateTime?
    ): String = describe(recordTime, resolveNearestMealTime(recordTime, panelMealTimes, savedMealTimes, historyMealTime))

    /** 按时段名取正常区间 [下限, 上限]（便于界面着色，口径同 PeriodClassifier） */
    fun normalRange(periodName: String?): DoubleArray =
        PeriodClassifier.getNormalRange(periodName ?: "空腹")

    fun isNormal(periodName: String?, bloodSugar: Double): Boolean =
        PeriodClassifier.isNormal(periodName ?: "空腹", bloodSugar)
}
