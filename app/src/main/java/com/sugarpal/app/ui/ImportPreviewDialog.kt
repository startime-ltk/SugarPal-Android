package com.sugarpal.app.ui

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.sugarpal.app.R
import com.sugarpal.app.data.CsvImporter
import com.sugarpal.app.data.RecordDao
import com.sugarpal.app.data.TimeFmt

/**
 * 导入预览对话框：解析结果表 + 汇总文案 + 确认导入。
 * 保留 PC 端「解析 → 预览 → 确认写入」两段式流程，便于用户入库前核对。
 * 表列：行号 / 时间 / 血糖 / 餐别 / 备注 / 状态。
 */
object ImportPreviewDialog {

    /** 预览最多渲染的行数，超出部分仅计数不渲染，避免大文件卡顿 */
    private const val MAX_SHOW = 300

    fun show(
        activity: Activity,
        dao: RecordDao,
        preview: CsvImporter.ImportPreview,
        onDone: (CsvImporter.ImportResult) -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(4))
        }

        root.addView(TextView(activity).apply {
            text = preview.summaryText
            textSize = 13f
            setTextColor(activity.getColor(R.color.color_title))
            setTypeface(typeface, Typeface.BOLD)
        })

        if (preview.warning.isNotBlank()) {
            root.addView(TextView(activity).apply {
                text = preview.warning
                textSize = 12f
                setTextColor(activity.getColor(R.color.color_orange_dark))
                setPadding(0, dp(4), 0, 0)
            })
        }

        val table = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val weights = listOf(0.55f, 1.85f, 0.95f, 1.05f, 1.35f, 1.25f)

        fun addRow(cells: List<String>, header: Boolean, statusColor: Int?) {
            val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            cells.forEachIndexed { i, s ->
                val tv = TextView(activity).apply {
                    text = s
                    textSize = if (header) 11.5f else 11f
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(2), dp(5), dp(2), dp(5))
                    if (header) {
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(activity.getColor(R.color.color_text))
                    } else if (statusColor != null && i == cells.lastIndex) {
                        setTextColor(statusColor)
                    }
                }
                line.addView(
                    tv,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weights.getOrElse(i) { 1f })
                )
            }
            table.addView(line)
        }

        addRow(listOf("行号", "时间", "血糖", "餐别", "备注", "状态"), true, null)

        val okColor = activity.getColor(R.color.color_green_dark)
        val warnColor = activity.getColor(R.color.color_orange_dark)
        val grayColor = activity.getColor(R.color.color_text)

        preview.rows.take(MAX_SHOW).forEach { row ->
            val timeText = if (row.displayTime != null) TimeFmt.full(row.displayTime) else row.rawTime
            val sugarText = row.record?.let { String.format("%.1f", it.bloodSugar) } ?: row.rawSugar
            val statusColor = when (row.status) {
                CsvImporter.STATUS_OK -> okColor
                CsvImporter.STATUS_INVALID -> warnColor
                else -> grayColor
            }
            addRow(
                listOf(
                    row.lineNo.toString(),
                    timeText,
                    sugarText,
                    row.displayPeriod.ifBlank { "-" },
                    row.rawNote.ifBlank { "-" },
                    row.status
                ),
                false,
                statusColor
            )
        }

        if (preview.rows.size > MAX_SHOW) {
            table.addView(TextView(activity).apply {
                text = "仅展示前 $MAX_SHOW 行，其余行仍会按状态处理。"
                textSize = 11f
                setTextColor(grayColor)
                setPadding(0, dp(6), 0, 0)
            })
        }

        val scroll = ScrollView(activity).apply { addView(table) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(360)))

        val builder = AlertDialog.Builder(activity)
            .setTitle("导入预览")
            .setView(root)
            .setNegativeButton("取消", null)

        if (preview.importable > 0) {
            builder.setPositiveButton("确认导入 ${preview.importable} 条") { _, _ ->
                val result = CsvImporter.doImport(dao, preview)
                onDone(result)
            }
        } else {
            builder.setPositiveButton("知道了", null)
        }

        builder.show()
    }
}
