package com.sugarpal.app.data

import com.bloodsugar.model.BloodSugarRecord
import com.bloodsugar.util.PeriodClassifier
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * CSV 导入：解析 → 表头识别 → 逐行校验 → 两级去重 → 餐别解析 → 预览对象。
 * 口径与 PC 端 service/CsvImportService.java 逐条对齐（规格文档第 5 章）。
 * 本类处理 CSV/TXT 文本；xlsx/xls 由 UI 层取出文本后交给本类解析。
 */
object CsvImporter {

    const val MAX_ROWS = 5000
    const val MIN_VALID = 1.0
    const val MAX_VALID = 40.0

    const val STATUS_OK = "可导入"
    const val STATUS_DUP_FILE = "文件内重复"
    const val STATUS_DUP_DB = "与已有记录重复"
    const val STATUS_INVALID = "格式错误"

    /** 模板内容：UTF-8 带 BOM + CRLF，Excel 可直接打开 */
    fun template(): String {
        val today = java.time.LocalDate.now().toString()
        return "\uFEFF" + listOf(
            "时间,血糖值(mmol/L),餐别,备注",
            "$today 07:30,5.6,空腹,起床后测量",
            "$today 09:30,7.8,餐后1h,早餐后",
            "$today 10:30,6.4,餐后2h,早餐后",
            "$today 21:30,6.9,,睡前（未填餐别将自动识别）"
        ).joinToString("\r\n") + "\r\n"
    }

    /** UTF-8 解码；出现替换字符则按 GBK 重新解码（血糖仪导出常为 GBK） */
    fun decode(bytes: ByteArray): String {
        var text = String(bytes, Charsets.UTF_8)
        if (text.contains('\uFFFD')) {
            runCatching { text = String(bytes, Charset.forName("GBK")) }
        }
        return text.removePrefix("\uFEFF")
    }

    // ---------------- 数据结构 ----------------

    data class Mapping(
        val timeIdx: Int,
        val sugarIdx: Int,
        val periodIdx: Int,
        val noteIdx: Int,
        val headerDetected: Boolean,
        val separator: Char
    )

    data class PreviewRow(
        val lineNo: Int,
        val rawTime: String,
        val rawSugar: String,
        val rawPeriod: String,
        val rawNote: String,
        val record: BloodSugarRecord?,
        val displayTime: LocalDateTime?,
        val displayPeriod: String,
        val status: String,
        val message: String
    )

    data class ImportPreview(
        val mapping: Mapping,
        val rows: List<PreviewRow>,
        val total: Int,
        val importable: Int,
        val dupInFile: Int,
        val dupInDb: Int,
        val invalid: Int,
        val warning: String
    ) {
        val summaryText: String
            get() = "共 $total 行：可导入 $importable 条，重复跳过 ${dupInFile + dupInDb} 条" +
                "（文件内 $dupInFile / 已有记录 $dupInDb），格式错误 $invalid 条"
    }

    data class ImportResult(val success: Int, val skipped: Int, val failed: Int, val failures: List<String>) {
        val text: String get() = "成功导入 $success 条，跳过 $skipped 条，失败 $failed 条"
    }

    // ---------------- 解析入口 ----------------

    /** 解析文本生成预览，不写库（对应 PC 端"解析→预览"两段式流程） */
    fun parse(text: String, dbKeys: Set<String>, dao: RecordDao): ImportPreview {
        val rawRows = split(text)
        val warning = StringBuilder()
        if (rawRows.size > MAX_ROWS + 1) {
            warning.append("文件行数较多，仅解析前 5000 行。")
        }

        val limitRows = rawRows.take(MAX_ROWS + 1)
        val firstRow = limitRows.firstOrNull { it.fields.any { f -> f.isNotBlank() } }
            ?: return emptyPreview()

        val sep = detectSeparator(text)
        val mapping = detectHeader(firstRow.fields, sep)
        val dataStart = if (mapping.headerDetected) 1 else 0
        if (mapping.timeIdx < 0) return errorPreview(Mapping(-1, -1, -1, -1, false, sep), "未能识别「时间」列，请检查文件或使用下载模板重新填写。")
        if (mapping.sugarIdx < 0) return errorPreview(mapping, "未能识别「血糖」列，请检查文件或使用下载模板重新填写。")
        if (!mapping.headerDetected) {
            if (warning.isNotEmpty()) warning.append(' ')
            warning.append("未检测到标准表头，已按第 1 列=时间、第 2 列=血糖 解析。")
        }

        val fileKeys = HashSet<String>()
        val rows = ArrayList<PreviewRow>()
        var importable = 0
        var dupInFile = 0
        var dupInDb = 0
        var invalid = 0

        limitRows.drop(dataStart).forEach { row ->
            val f = row.fields
            if (f.all { it.isBlank() }) return@forEach
            val rawTime = f.getOrElse(mapping.timeIdx) { "" }
            val rawSugar = f.getOrElse(mapping.sugarIdx) { "" }
            val rawPeriod = if (mapping.periodIdx >= 0) f.getOrElse(mapping.periodIdx) { "" } else ""
            val rawNote = if (mapping.noteIdx >= 0) f.getOrElse(mapping.noteIdx) { "" } else ""

            val msg = StringBuilder()
            val timeParse = parseDateTime(rawTime)
            val sugarParse = parseSugar(rawSugar)

            if (timeParse == null) {
                invalid++
                rows.add(PreviewRow(row.lineNo, rawTime, rawSugar, rawPeriod, rawNote, null, null, "", STATUS_INVALID, "时间无法识别"))
                return@forEach
            }
            if (sugarParse == null) {
                invalid++
                rows.add(PreviewRow(row.lineNo, rawTime, rawSugar, rawPeriod, rawNote, null, timeParse.dt, "", STATUS_INVALID, "血糖值无法识别或超出合理范围(1~40 mmol/L)"))
                return@forEach
            }
            if (timeParse.dateOnly) msg.append("仅日期，已默认 08:00")
            if (sugarParse.note.isNotEmpty()) {
                if (msg.isNotEmpty()) msg.append("；")
                msg.append(sugarParse.note)
            }

            val t = timeParse.dt
            val bizDate = PeriodClassifier.getBusinessDate(t)
            val meal = resolveMeal(t, bizDate, rawPeriod, dao)
            if (meal.hint.isNotEmpty()) {
                if (msg.isNotEmpty()) msg.append("；")
                msg.append(meal.hint)
            }

            val key = t.format(TS) + "#" + String.format(Locale.US, "%.1f", sugarParse.value)
            val status: String
            when {
                !fileKeys.add(key) -> {
                    status = STATUS_DUP_FILE; dupInFile++
                }
                dbKeys.contains(key) -> {
                    status = STATUS_DUP_DB; dupInDb++
                }
                else -> {
                    status = STATUS_OK; importable++
                }
            }

            val record = if (status == STATUS_OK) toRecord(t, sugarParse.value, meal, rawNote) else null
            rows.add(PreviewRow(row.lineNo, rawTime, rawSugar, rawPeriod, rawNote, record, t, meal.mealPeriod ?: "", status, msg.toString()))
        }

        return ImportPreview(mapping, rows, importable + dupInFile + dupInDb + invalid, importable, dupInFile, dupInDb, invalid, warning.toString())
    }

    /** 执行导入：仅写入"可导入"行 */
    fun doImport(dao: RecordDao, preview: ImportPreview): ImportResult {
        var ok = 0
        var failed = 0
        val failures = ArrayList<String>()
        preview.rows.filter { it.status == STATUS_OK }.forEach { row ->
            val rec = row.record ?: return@forEach
            runCatching { dao.insert(rec) }
                .onSuccess { ok++ }
                .onFailure {
                    failed++
                    failures.add("第 ${row.lineNo} 行：${it.message ?: it.javaClass.simpleName}")
                }
        }
        return ImportResult(ok, preview.total - preview.importable, failed, failures)
    }

    /** 构建库内去重键集合：时间精确到秒 + 血糖值保留 1 位小数（与 PC 端 dupKey 逐字一致） */
    fun buildDbKeys(records: List<BloodSugarRecord>): Set<String> {
        val keys = HashSet<String>()
        records.forEach { r ->
            val t = r.recordTime ?: return@forEach
            keys.add(t.format(TS) + "#" + String.format(Locale.US, "%.1f", r.bloodSugar))
        }
        return keys
    }

    // ---------------- 内部实现 ----------------

    private val TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private data class Row(val lineNo: Int, val fields: List<String>)
    private data class TimeParse(val dt: LocalDateTime, val dateOnly: Boolean)
    private data class SugarParse(val value: Double, val note: String)
    private data class MealResolve(val mealPeriod: String?, val mealTime: LocalDateTime?, val mealType: String?, val hint: String)

    private fun emptyPreview() = ImportPreview(Mapping(-1, -1, -1, -1, false, ','), emptyList(), 0, 0, 0, 0, 0, "")

    private fun errorPreview(m: Mapping, msg: String) = ImportPreview(m, emptyList(), 0, 0, 0, 0, 0, msg)

    private fun toRecord(t: LocalDateTime, v: Double, meal: MealResolve, rawNote: String): BloodSugarRecord {
        val r = BloodSugarRecord()
        r.recordTime = t
        r.bloodSugar = v
        r.mealTime = meal.mealTime
        r.mealPeriod = meal.mealPeriod
        r.mealType = meal.mealType
        r.note = rawNote.replace(" ", "")
        r.insulin = 0.0
        r.carbs = 0.0
        r.activity = 0.0
        r.weight = 0.0
        r.pulse = 0.0
        r.bloodPressure = null
        return r
    }

    private fun detectSeparator(text: String): Char {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return ','
        val comma = firstLine.count { it == ',' }
        val semi = firstLine.count { it == ';' }
        val tab = firstLine.count { it == '\t' }
        return when {
            tab >= comma && tab >= semi -> '\t'
            semi > comma -> ';'
            else -> ','
        }
    }

    private fun split(text: String): List<Row> {
        val sep = detectSeparator(text)
        val rows = ArrayList<Row>()
        val fields = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var lineNo = 1
        var rowStartLine = 1
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                when {
                    ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                        sb.append('"'); i += 2; continue
                    }
                    ch == '"' -> {
                        inQuotes = false; i++; continue
                    }
                    else -> {
                        if (ch == '\n') lineNo++
                        sb.append(ch); i++; continue
                    }
                }
            }
            when {
                ch == '"' -> {
                    inQuotes = true; i++
                }
                ch == sep -> {
                    fields.add(sb.toString()); sb.setLength(0); i++
                }
                ch == '\r' -> i++
                ch == '\n' -> {
                    fields.add(sb.toString()); sb.setLength(0)
                    rows.add(Row(rowStartLine, ArrayList(fields)))
                    fields.clear()
                    lineNo++; rowStartLine = lineNo; i++
                }
                else -> {
                    sb.append(ch); i++
                }
            }
        }
        if (sb.isNotEmpty() || fields.isNotEmpty()) {
            fields.add(sb.toString())
            rows.add(Row(rowStartLine, ArrayList(fields)))
        }
        return rows
    }

    private fun norm(s: String): String = s.trim().lowercase()
        .replace('（', '(').replace('）', ')')
        .replace(" ", "").replace("\u3000", "")
        .replace("_", "").replace("-", "")
        .replace("[", "").replace("]", "")
        .replace("(", "").replace(")", "")

    private fun detectHeader(first: List<String>, sep: Char): Mapping {
        val normed = first.map { norm(it) }
        val timeKeys = listOf("时间", "日期", "date", "time")
        val sugarKeys = listOf("血糖", "glucose", "sugar", "mmol", "value", "数值", "浓度", "测量值")
        val periodKeys = listOf("餐别", "餐次", "时段", "餐型", "类型", "period", "meal")
        val noteKeys = listOf("备注", "说明", "注释", "note", "remark", "comment", "memo")

        fun firstIdx(keys: List<String>, exact: String? = null): Int {
            normed.forEachIndexed { idx, n ->
                if (n.isBlank()) return@forEachIndexed
                if (exact != null && n == exact) return idx
                if (keys.any { n.contains(it) }) return idx
            }
            return -1
        }

        val timeIdx = firstIdx(timeKeys)
        val sugarIdx = firstIdx(sugarKeys, "值")
        val periodIdx = firstIdx(periodKeys, "type")
        val noteIdx = firstIdx(noteKeys)
        val headerDetected = timeIdx >= 0 || sugarIdx >= 0
        return if (headerDetected) Mapping(timeIdx, sugarIdx, periodIdx, noteIdx, true, sep)
        else Mapping(0, 1, -1, -1, false, sep)
    }

    private val YMD = listOf("yyyy-M-d H:m:s", "yyyy-M-d H:m", "yyyyMMddHHmmss", "yyyyMMddHHmm")
    private val MDY = listOf("M-d-yyyy H:m:s", "M-d-yyyy H:m")
    private val DMY = listOf("d-M-yyyy H:m:s", "d-M-yyyy H:m")
    private val DATE_ONLY = listOf("yyyy-M-d", "yyyyMMdd")

    private fun preprocessTime(raw: String): String {
        var s = raw.trim().replace('T', ' ').replace('t', ' ')
        s = s.replace('/', '-').replace('.', '-')
        s = s.replace("年", "-").replace("月", "-").replace("日", " ")
        s = s.replace("\u3000", " ")
        s = Regex("(星期|周)[一二三四五六日天]").replace(s, " ")
        return s.trim().replace(Regex("\\s+"), " ")
    }

    private fun tryFormat(s: String, pattern: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(s, DateTimeFormatter.ofPattern(pattern)) }.getOrNull()

    private fun parseDateTime(raw: String): TimeParse? {
        val s = preprocessTime(raw)
        if (s.isBlank()) return null
        YMD.forEach { p -> tryFormat(s, p)?.let { return TimeParse(it, false) } }
        val firstSeg = s.takeWhile { it.isDigit() }
        val firstNum = firstSeg.toIntOrNull()
        val mdy = if (firstNum != null && firstSeg.length <= 2 && firstNum > 12) DMY else MDY
        mdy.forEach { p -> tryFormat(s, p)?.let { return TimeParse(it, false) } }
        DATE_ONLY.forEach { p ->
            runCatching { LocalDate.parse(s, DateTimeFormatter.ofPattern(p)) }
                .getOrNull()?.let { return TimeParse(it.atTime(8, 0), true) }
        }
        return null
    }

    private fun parseSugar(raw: String): SugarParse? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val isMg = s.contains("mg", ignoreCase = true)
        var v = s.replace("，", ",").replace(',', '.')
        v = v.filter { it.isDigit() || it == '.' || it == '-' }
        var value = v.toDoubleOrNull() ?: return null
        var converted = false
        if (isMg || (value > 40.0 && value <= 600.0)) {
            value /= 18.0; converted = true
        }
        if (value < MIN_VALID - 0.0001 || value > MAX_VALID + 0.0001) return null
        value = Math.round(value * 10.0) / 10.0
        return SugarParse(value, if (converted) "已按 mg/dL ÷18 换算" else "")
    }

    private fun resolveMeal(t: LocalDateTime, bizDate: LocalDate, rawPeriod: String, dao: RecordDao): MealResolve {
        val p = rawPeriod.trim()
        val lower = p.lowercase()

        if (p.isEmpty()) {
            return try {
                val savedLatest = dao.findLatestSavedMealTime(bizDate, t)
                val recordLatest = dao.findLatestMealTimeBefore(PeriodClassifier.getBusinessDayStart(bizDate), t)
                val mt = listOfNotNull(savedLatest, recordLatest).maxOrNull()
                if (mt == null) {
                    MealResolve("空腹", null, "空腹", "未填餐别且无用餐时间记录，按空腹计")
                } else {
                    MealResolve(PeriodClassifier.classify(t, mt), mt, PeriodClassifier.classifyMealType(t, mt), "未填餐别，已按自动识别")
                }
            } catch (e: Exception) {
                MealResolve("空腹", null, "空腹", "未填餐别且无用餐时间记录，按空腹计")
            }
        }

        if (lower.contains("餐后") || lower.contains("1小时") || lower.contains("2小时") || lower.contains("3小时") || lower.contains("post")) {
            return when {
                p.contains("1") -> MealResolve("餐后1h", t.minusMinutes(45), "餐后", "")
                p.contains("3") -> MealResolve("餐后3h", t.minusMinutes(150), "餐后", "")
                else -> MealResolve("餐后2h", t.minusMinutes(90), "餐后", "")
            }
        }
        if (p.contains("空腹") || p.contains("晨起")) return MealResolve("空腹", null, "空腹", "")
        if (p.contains("餐前")) return MealResolve("空腹", null, "餐前", "")
        if (p.contains("睡前") || lower.contains("bed")) return MealResolve("空腹", null, "睡前", "")

        val mealName = when {
            p.contains("加餐") -> "加餐"
            p.contains("早") -> "早餐"
            p.contains("午") -> "午餐"
            p.contains("晚") -> "晚餐"
            else -> null
        }
        if (mealName != null) {
            val alt = if (mealName == "早餐") "早" else mealName
            return try {
                val map = dao.findMealTimesByBusinessDate(bizDate)
                val mt = listOfNotNull(map[mealName], map[alt])
                    .filter { !it.isAfter(t) }
                    .maxOrNull()
                if (mt == null) {
                    MealResolve("空腹", null, mealName, "未找到当日「$mealName」用餐时间，时段按空腹计")
                } else {
                    MealResolve(PeriodClassifier.classify(t, mt), mt, mealName, "")
                }
            } catch (e: Exception) {
                MealResolve("空腹", null, mealName, "未找到当日「$mealName」用餐时间，时段按空腹计")
            }
        }

        return try {
            val savedLatest = dao.findLatestSavedMealTime(bizDate, t)
            val recordLatest = dao.findLatestMealTimeBefore(PeriodClassifier.getBusinessDayStart(bizDate), t)
            val mt = listOfNotNull(savedLatest, recordLatest).maxOrNull()
            if (mt == null) {
                MealResolve("空腹", null, "空腹", "未填餐别且无用餐时间记录，按空腹计")
            } else {
                MealResolve(PeriodClassifier.classify(t, mt), mt, PeriodClassifier.classifyMealType(t, mt), "未填餐别，已按自动识别")
            }
        } catch (e: Exception) {
            MealResolve("空腹", null, "空腹", "未填餐别且无用餐时间记录，按空腹计")
        }
    }
}
