package com.sugarpal.app.data

import android.content.Context
import com.bloodsugar.model.BloodSugarRecord
import com.bloodsugar.util.PeriodClassifier
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 健康摘要（P0-2）。
 *
 * 近 30 天统计：复用 AgpCalculator 的均值 / CV / 估算 HbA1c / TIR / 覆盖天数等指标
 * （口径与 PC 端 AgpReportService 一致），另按报警阈值独立统计偏低 / 偏高次数
 * （报警阈值默认 3.9 / 13.9，可在设置中调整）。数据全程本地计算，不联网。
 */
object HealthSummaryService {

    data class Summary(
        val rangeDays: Int,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val recordCount: Int,
        val dayCount: Int,
        val mean: Double,
        val cv: Double,
        val hba1c: Double,
        val tir: Double,
        val lowCount: Int,
        val highCount: Int,
        val trendNote: String
    )

    fun build(context: Context, all: List<BloodSugarRecord>, rangeDays: Int = 30): Summary {
        val today = PeriodClassifier.getBusinessDate(LocalDateTime.now()) ?: LocalDate.now()
        val report = AgpCalculator.build(all, rangeDays, today)
        val low = AlarmConfig.low(context)
        val high = AlarmConfig.high(context)

        val startDate = today.minusDays((rangeDays - 1).toLong())
        val from = PeriodClassifier.getBusinessDayStart(startDate)
        val to = PeriodClassifier.getBusinessDayEnd(today)
        val records = all.filter { r ->
            val t = r.recordTime
            t != null && r.bloodSugar > 0 && !t.isBefore(from) && t.isBefore(to)
        }
        val lowCount = records.count { it.bloodSugar < low }
        val highCount = records.count { it.bloodSugar > high }

        val trendNote = trendNote(report.mean, report.cv, report.tir, report.hba1c, lowCount, highCount, report.recordCount)
        return Summary(
            rangeDays = rangeDays,
            startDate = startDate,
            endDate = today,
            recordCount = report.recordCount,
            dayCount = report.dayCount,
            mean = report.mean,
            cv = report.cv,
            hba1c = report.hba1c,
            tir = report.tir,
            lowCount = lowCount,
            highCount = highCount,
            trendNote = trendNote
        )
    }

    /** 趋势说明：与 PC 端 HealthSummaryService 口径一致（均值 / CV≤36 / TIR≥70 / 报警次数） */
    fun trendNote(
        mean: Double,
        cv: Double,
        tir: Double,
        hba1c: Double,
        lowCount: Int,
        highCount: Int,
        count: Int
    ): String {
        if (count == 0) return "近 30 天暂无血糖记录，建议坚持每日测量后再查看摘要。"
        val parts = ArrayList<String>()
        parts.add(if (mean in 3.9..8.9) "平均血糖处于目标范围内" else "平均血糖偏离目标范围")
        parts.add(if (cv <= 36.0) "血糖波动较平稳" else "血糖波动偏大，建议关注饮食与作息规律")
        parts.add(if (tir >= 70.0) "达标率较好" else "达标率偏低，建议与医生沟通调整方案")
        if (lowCount > 0) parts.add("期间出现 $lowCount 次偏低报警")
        if (highCount > 0) parts.add("期间出现 $highCount 次偏高报警")
        return parts.joinToString("；") + "。"
    }
}
