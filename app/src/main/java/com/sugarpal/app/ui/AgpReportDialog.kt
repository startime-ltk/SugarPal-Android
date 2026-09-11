package com.sugarpal.app.ui

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.bloodsugar.util.PeriodClassifier
import com.sugarpal.app.R
import com.sugarpal.app.data.AgpCalculator
import com.sugarpal.app.data.RecordDao
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * AGP 标准化葡萄糖图谱对话框（对应规格 9.11）。
 * 范围 7 / 14 / 30 天，指标区 + 24 小时分位曲线 + 分时段统计表 + 按餐别统计表。
 */
object AgpReportDialog {

    fun show(activity: Activity, dao: RecordDao, onExportPdf: (AgpCalculator.AgpReport) -> Unit = {}) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).toInt()

        var rangeDays = 30
        var currentReport: AgpCalculator.AgpReport? = null

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(12))
        }
        val scroll = ScrollView(activity).apply {
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("AGP 标准化葡萄糖图谱")
            .setView(scroll)
            .setPositiveButton("导出 PDF") { _, _ ->
                currentReport?.let(onExportPdf)
            }
            .setNegativeButton("关闭", null)
            .create()

        fun label(text: String, size: Float, colorRes: Int, bold: Boolean = false): TextView =
            TextView(activity).apply {
                this.text = text
                textSize = size
                setTextColor(activity.getColor(colorRes))
                if (bold) setTypeface(typeface, Typeface.BOLD)
            }

        fun sectionTitle(text: String): TextView = label(text, 13f, R.color.color_title, true).apply {
            setPadding(0, dp(12), 0, dp(6))
        }

        fun addTable(headers: List<String>, weights: List<Float>, rows: List<List<String>>) {
            val table = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            fun addRow(cells: List<String>, header: Boolean) {
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                cells.forEachIndexed { i, s ->
                    val tv = label(s, 11.5f, if (header) R.color.color_title else R.color.color_text, header)
                    tv.gravity = if (i == 0) Gravity.START else Gravity.CENTER
                    tv.setPadding(dp(2), dp(5), dp(2), dp(5))
                    row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weights[i]))
                }
                table.addView(row)
            }
            addRow(headers, true)
            if (rows.isEmpty()) {
                addRow(listOf("暂无数据", "", "", "", ""), false)
            } else {
                rows.forEach { addRow(it, false) }
            }
            content.addView(table)
        }

        fun metricCell(title: String, value: String): LinearLayout =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(4), 0, dp(4))
                addView(label(title, 11f, R.color.color_text))
                addView(label(value, 13f, R.color.color_title, true))
            }

        fun rebuild() {
            content.removeAllViews()

            val today = PeriodClassifier.getBusinessDate(LocalDateTime.now()) ?: LocalDate.now()
            val report = AgpCalculator.build(dao.findAll(), rangeDays, today)
            currentReport = report

            // 范围选择
            val rangeRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            AgpCalculator.RANGE_OPTIONS.forEach { days ->
                val selected = days == rangeDays
                val tv = TextView(activity).apply {
                    text = "$days 天"
                    textSize = 12.5f
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_unselected)
                    setTextColor(activity.getColor(if (selected) R.color.color_panel else R.color.color_text))
                    setOnClickListener {
                        if (rangeDays != days) {
                            rangeDays = days
                            rebuild()
                        }
                    }
                }
                val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                lp.rightMargin = dp(8)
                rangeRow.addView(tv, lp)
            }
            content.addView(rangeRow)

            content.addView(
                label(
                    "统计范围 " + report.startDate.toString() + " ~ " + report.endDate.toString(),
                    11.5f,
                    R.color.color_text
                ).apply { setPadding(0, dp(8), 0, 0) }
            )

            // 指标区
            content.addView(sectionTitle("核心指标"))
            val rows = listOf(
                listOf(
                    "平均血糖" to String.format(Locale.US, "%.1f mmol/L", report.mean),
                    "标准差 SD" to String.format(Locale.US, "%.2f", report.sd)
                ),
                listOf(
                    "变异系数 CV" to String.format(Locale.US, "%.1f%%", report.cv),
                    "估算 HbA1c" to String.format(Locale.US, "%.1f%%", report.hba1c)
                ),
                listOf(
                    "TIR 达标率" to String.format(Locale.US, "%.1f%%", report.tir),
                    "TAR 偏高率" to String.format(Locale.US, "%.1f%%", report.tar)
                ),
                listOf(
                    "TBR 偏低率" to String.format(Locale.US, "%.1f%%", report.tbr),
                    "覆盖天数 / 记录数" to "${report.dayCount} 天 / ${report.recordCount} 条"
                )
            )
            rows.forEach { pair ->
                val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEach { (t, v) ->
                    row.addView(metricCell(t, v), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                }
                content.addView(row)
            }
            content.addView(
                metricCell(
                    "波动区间（最低 ~ 最高）",
                    if (report.recordCount == 0) "—"
                    else String.format(Locale.US, "%.1f ~ %.1f mmol/L", report.minValue, report.maxValue)
                )
            )

            // 24 小时分位曲线
            content.addView(sectionTitle("24 小时分位曲线（P25 ~ P75 分位带 + P50 中位数）"))
            val curveView = AgpCurveView(activity).apply { setData(report.curve) }
            content.addView(curveView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)))

            // 分时段统计
            content.addView(sectionTitle("分时段统计"))
            addTable(
                headers = listOf("时段", "记录数", "平均", "最高/最低", "达标率"),
                weights = listOf(1.5f, 0.9f, 0.9f, 1.5f, 0.95f),
                rows = report.timeSegments.filter { it.count > 0 }.map {
                    listOf(
                        it.name,
                        it.count.toString(),
                        String.format(Locale.US, "%.1f", it.avg),
                        String.format(Locale.US, "%.1f / %.1f", it.max, it.min),
                        String.format(Locale.US, "%.1f%%", it.tirPercent)
                    )
                }
            )

            // 按餐别统计（空组不输出）
            content.addView(sectionTitle("按餐别统计"))
            addTable(
                headers = listOf("餐别", "记录数", "平均", "最高/最低", "达标率"),
                weights = listOf(1.5f, 0.9f, 0.9f, 1.5f, 0.95f),
                rows = report.mealGroups.map {
                    listOf(
                        it.name,
                        it.count.toString(),
                        String.format(Locale.US, "%.1f", it.avg),
                        String.format(Locale.US, "%.1f / %.1f", it.max, it.min),
                        String.format(Locale.US, "%.1f%%", it.tirPercent)
                    )
                }
            )
        }

        rebuild()

        dialog.setOnShowListener {
            val width = (activity.resources.displayMetrics.widthPixels * 0.92f).toInt()
            dialog.window?.setLayout(width, (d * 560).toInt())
        }
        dialog.show()
    }
}
