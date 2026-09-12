package com.sugarpal.app.data

import com.bloodsugar.model.BloodSugarRecord
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * CSV 导出：列顺序与取值格式与 PC 端导入功能（CsvImportService）的识别口径对齐，
 * 保证安卓端导出的文件能被 PC 端糖伴直接导入（时间列已含完整日期，避免仅 HH:mm 无法解析）。
 *
 * 格式约定（逐字对齐规格文档第 5 章）：
 *  - 编码 UTF-8 带 BOM（\uFEFF），行结束符 CRLF，Excel 可直接打开；
 *  - 表头：时间,血糖值(mmol/L),餐别,备注
 *  - 时间列 yyyy-MM-dd HH:mm:ss；血糖值保留 1 位小数；空值输出空字段；
 *  - 字段含逗号 / 双引号 / 换行时用双引号包裹，内部双引号转义为两个双引号。
 */
object CsvExporter {

    const val HEADER = "时间,血糖值(mmol/L),餐别,备注"

    private const val BOM = '\uFEFF'
    private const val CRLF = "\r\n"

    private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** 全部记录 → CSV 文本（按测量时间升序，时间为空的记录排在最后） */
    fun render(records: List<BloodSugarRecord>): String {
        val sb = StringBuilder()
        sb.append(BOM).append(HEADER).append(CRLF)
        records
            .sortedBy { it.recordTime ?: LocalDateTime.MAX }
            .forEach { sb.append(row(it)).append(CRLF) }
        return sb.toString()
    }

    /** 将 CSV 文本写入指定输出流（供 SAF 导出 / 分享使用） */
    fun write(out: OutputStream, records: List<BloodSugarRecord>) {
        out.use { it.write(render(records).toByteArray(Charsets.UTF_8)) }
    }

    /** 餐别列优先取值集合：命中则导出 mealType，否则回退 mealPeriod */
    private val MEAL_TYPE_LABELS = setOf("早餐", "午餐", "晚餐", "加餐")

    private fun row(r: BloodSugarRecord): String {
        val time = r.recordTime?.format(TS) ?: ""
        val sugar = String.format(Locale.US, "%.1f", r.bloodSugar)
        return listOf(time, sugar, periodLabel(r), r.note ?: "").joinToString(",") { escape(it) }
    }

    /**
     * 餐别列取值：记录的 mealType 为「早餐 / 午餐 / 晚餐 / 加餐」时导出 mealType，
     * 否则导出 mealPeriod（保留「空腹 / 餐后1h / 餐后2h / 餐后3h」等时段精度）。
     */
    private fun periodLabel(r: BloodSugarRecord): String {
        val type = (r.mealType ?: "").trim()
        if (type.isNotEmpty() && MEAL_TYPE_LABELS.contains(type)) return type
        return r.mealPeriod ?: ""
    }

    private fun escape(value: String): String {
        if (value.indexOfAny(charArrayOf(',', '"', '\n', '\r')) < 0) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}
