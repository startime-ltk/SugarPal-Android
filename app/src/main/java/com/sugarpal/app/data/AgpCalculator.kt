package com.sugarpal.app.data

import com.bloodsugar.model.BloodSugarRecord
import com.bloodsugar.util.PeriodClassifier
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * AGP 标准化葡萄糖图谱计算（对应规格文档第 4 章，口径与 PC 端 AgpReportService 逐条一致）。
 *
 * 取数范围：today = getBusinessDate(now)，startDate = today - (rangeDays - 1)，
 * 查询区间 [getBusinessDayStart(startDate), getBusinessDayEnd(today))，仅取 recordTime != null && bloodSugar > 0。
 * 达标判定逐条按记录自身时段取区间，不按单一区间判定。
 */
object AgpCalculator {

    const val DEFAULT_RANGE_DAYS = 30
    const val TARGET_LOW = 3.9
    const val TARGET_HIGH_MAX = 8.9

    val RANGE_OPTIONS = listOf(7, 14, 30)

    private val MEAL_ORDER = listOf("空腹", "餐后1h", "餐后2h", "餐后3h")
    private val SEGMENT_NAMES = listOf("00:00-06:00", "06:00-12:00", "12:00-18:00", "18:00-24:00")

    /** AGP 曲线单点：整点、入箱记录数、P25 / P50 / P75 */
    data class CurvePoint(val hour: Int, val count: Int, val p25: Double, val p50: Double, val p75: Double)

    /** 分组统计：分时段（4 段）与按餐别共用 */
    data class GroupStat(
        val name: String,
        val count: Int,
        val avg: Double,
        val max: Double,
        val min: Double,
        val tirPercent: Double
    )

    data class AgpReport(
        val rangeDays: Int,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val recordCount: Int,
        val dayCount: Int,
        val mean: Double,
        val sd: Double,
        val cv: Double,
        val hba1c: Double,
        val tir: Double,
        val tar: Double,
        val tbr: Double,
        val inRangeCount: Int,
        val aboveCount: Int,
        val belowCount: Int,
        val minValue: Double,
        val maxValue: Double,
        val curve: List<CurvePoint>,
        val timeSegments: List<GroupStat>,
        val mealGroups: List<GroupStat>
    ) {
        val targetLow: Double get() = TARGET_LOW
        val targetHighMax: Double get() = TARGET_HIGH_MAX
    }

    fun build(all: List<BloodSugarRecord>, rangeDays: Int, today: LocalDate): AgpReport {
        val days = if (rangeDays <= 0) DEFAULT_RANGE_DAYS else rangeDays
        val startDate = today.minusDays((days - 1).toLong())
        val from = PeriodClassifier.getBusinessDayStart(startDate)
        val to = PeriodClassifier.getBusinessDayEnd(today)

        val records = all.filter { r ->
            val t = r.recordTime
            t != null && r.bloodSugar > 0 && !t.isBefore(from) && t.isBefore(to)
        }.sortedBy { it.recordTime }

        val n = records.size
        val values = records.map { it.bloodSugar }

        if (n == 0) {
            return AgpReport(
                days, startDate, today, 0, 0,
                0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0,
                0, 0, 0,
                0.0, 0.0,
                emptyList(),
                SEGMENT_NAMES.map { GroupStat(it, 0, 0.0, 0.0, 0.0, 0.0) },
                emptyList()
            )
        }

        val mean = values.sum() / n
        val sd = if (n <= 1) 0.0 else sqrt(values.sumOf { (it - mean) * (it - mean) } / (n - 1))
        val cv = if (mean > 0) sd / mean * 100.0 else 0.0
        val hba1c = (mean * 18.0 + 46.7) / 28.7

        var inRange = 0
        var above = 0
        var below = 0
        records.forEach { r ->
            val range = PeriodClassifier.getNormalRange(r.mealPeriod)
            val v = r.bloodSugar
            when {
                v > range[1] -> above++
                v < range[0] -> below++
                else -> inRange++
            }
        }

        val dayCount = records.mapNotNull { PeriodClassifier.getBusinessDate(it.recordTime) }.distinct().size

        return AgpReport(
            rangeDays = days,
            startDate = startDate,
            endDate = today,
            recordCount = n,
            dayCount = dayCount,
            mean = mean,
            sd = sd,
            cv = cv,
            hba1c = hba1c,
            tir = 100.0 * inRange / n,
            tar = 100.0 * above / n,
            tbr = 100.0 * below / n,
            inRangeCount = inRange,
            aboveCount = above,
            belowCount = below,
            minValue = values.min(),
            maxValue = values.max(),
            curve = buildCurve(records),
            timeSegments = buildSegments(records),
            mealGroups = buildMealGroups(records)
        )
    }

    // ---------------- 24 小时分位曲线 ----------------

    private fun buildCurve(records: List<BloodSugarRecord>): List<CurvePoint> {
        val bins = Array(24) { ArrayList<Double>() }
        records.forEach { r ->
            val t = r.recordTime ?: return@forEach
            val minuteOfDay = t.hour * 60 + t.minute
            for (h in 0..23) {
                val raw = abs(minuteOfDay - h * 60)
                val diff = min(raw, 1440 - raw)
                if (diff <= 30) {
                    bins[h].add(r.bloodSugar)
                    break
                }
            }
        }
        if (bins.all { it.isEmpty() }) return emptyList()

        val p25 = DoubleArray(24)
        val p50 = DoubleArray(24)
        val p75 = DoubleArray(24)
        for (h in 0..23) {
            val sorted = bins[h].sorted()
            p25[h] = percentile(sorted, 0.25)
            p50[h] = percentile(sorted, 0.50)
            p75[h] = percentile(sorted, 0.75)
        }
        interpolate(p25)
        interpolate(p50)
        interpolate(p75)

        return (0..23).map { CurvePoint(it, bins[it].size, p25[it], p50[it], p75[it]) }
    }

    /** 线性插值分位数：idx = p * (n - 1)，与 PC 端 percentile 算法一致 */
    private fun percentile(sorted: List<Double>, p: Double): Double {
        val n = sorted.size
        if (n == 0) return 0.0
        if (n == 1) return sorted[0]
        val idx = p * (n - 1)
        val lo = floor(idx).toInt()
        val hi = ceil(idx).toInt()
        if (lo == hi) return sorted[lo]
        val frac = idx - lo
        return sorted[lo] * (1 - frac) + sorted[hi] * frac
    }

    /** 空洞补齐：首端平推、尾端平推、中间线性插值 */
    private fun interpolate(arr: DoubleArray) {
        var first = -1
        var last = -1
        for (i in arr.indices) {
            if (arr[i] > 0.0) {
                if (first < 0) first = i
                last = i
            }
        }
        if (first < 0) return

        for (i in 0 until first) arr[i] = arr[first]
        for (i in last + 1 until arr.size) arr[i] = arr[last]

        var prev = first
        for (i in first + 1..last) {
            if (arr[i] > 0.0) {
                if (i - prev > 1) {
                    val step = (arr[i] - arr[prev]) / (i - prev)
                    for (j in prev + 1 until i) arr[j] = arr[prev] + step * (j - prev)
                }
                prev = i
            }
        }
    }

    // ---------------- 分组统计 ----------------

    private fun buildSegments(records: List<BloodSugarRecord>): List<GroupStat> {
        val groups = Array(4) { ArrayList<BloodSugarRecord>() }
        records.forEach { r ->
            val t = r.recordTime ?: return@forEach
            groups[min(3, t.hour / 6)].add(r)
        }
        return SEGMENT_NAMES.mapIndexed { i, name -> groupStat(name, groups[i]) }
    }

    private fun buildMealGroups(records: List<BloodSugarRecord>): List<GroupStat> {
        val groups = ArrayList<GroupStat>()
        MEAL_ORDER.forEach { name ->
            val list = records.filter {
                val p = it.mealPeriod
                val key = if (p.isNullOrBlank()) "空腹" else p
                key == name
            }
            if (list.isNotEmpty()) groups.add(groupStat(name, list))
        }
        return groups
    }

    /** 组内达标判定同样逐条按记录自身时段区间 */
    private fun groupStat(name: String, list: List<BloodSugarRecord>): GroupStat {
        val n = list.size
        if (n == 0) return GroupStat(name, 0, 0.0, 0.0, 0.0, 0.0)
        val values = list.map { it.bloodSugar }
        var inRange = 0
        list.forEach { r ->
            if (PeriodClassifier.isNormal(r.mealPeriod, r.bloodSugar)) inRange++
        }
        return GroupStat(
            name = name,
            count = n,
            avg = values.sum() / n,
            max = values.max(),
            min = values.min(),
            tirPercent = 100.0 * inRange / n
        )
    }
}
