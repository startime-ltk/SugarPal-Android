package com.sugarpal.app

import android.app.TimePickerDialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bloodsugar.util.PeriodClassifier
import com.sugarpal.app.data.AiConfig
import com.sugarpal.app.data.AgpCalculator
import com.sugarpal.app.data.AgpPdfExporter
import com.sugarpal.app.data.CsvExporter
import com.sugarpal.app.data.CsvImporter
import com.sugarpal.app.data.RecordDao
import com.sugarpal.app.data.TimeFmt
import com.sugarpal.app.ui.AddRecordDialog
import com.sugarpal.app.ui.AgpReportDialog
import com.sugarpal.app.ui.AiSuggestionDialog
import com.sugarpal.app.ui.CalendarDialog
import com.sugarpal.app.ui.ImportPreviewDialog
import com.sugarpal.app.ui.RecordRowBinder
import com.sugarpal.app.ui.SettingsDialog
import com.sugarpal.app.ui.TrendDialog
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class MainActivity : AppCompatActivity() {

    private lateinit var dao: RecordDao
    private lateinit var recordContainer: LinearLayout
    private lateinit var tvRecordCount: TextView
    private lateinit var tvEmptyHint: TextView
    private lateinit var tvMealBusinessDate: TextView
    private lateinit var tvToday: TextView

    private val mealNames = listOf("早餐", "午餐", "晚餐", "加餐")
    private val mealViews = LinkedHashMap<String, TextView>()

    /** 标记本次文件保存动作是「导出模板」还是「导出数据」 */
    private var pendingTemplate = false

    /** SAF 选择待导入的 CSV/TXT 文件 */
    private val importCsv = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) handleImport(uri)
    }

    /** SAF 选择导出（数据或模板）的保存位置 */
    private val saveCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) handleSave(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Android 15+ 强制 edge-to-edge：为根布局补系统栏内边距，避免标题栏被状态栏遮挡
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        dao = RecordDao(this)

        recordContainer = findViewById(R.id.recordContainer)
        tvRecordCount = findViewById(R.id.tvRecordCount)
        tvEmptyHint = findViewById(R.id.tvEmptyHint)
        tvMealBusinessDate = findViewById(R.id.tvMealBusinessDate)
        tvToday = findViewById(R.id.tvToday)

        mealViews["早餐"] = findViewById(R.id.tvMealBreakfast)
        mealViews["午餐"] = findViewById(R.id.tvMealLunch)
        mealViews["晚餐"] = findViewById(R.id.tvMealDinner)
        mealViews["加餐"] = findViewById(R.id.tvMealSnack)
        mealNames.forEach { name -> bindMealRow(mealViews[name]!!, name) }

        findViewById<View>(R.id.btnAddRecord).setOnClickListener {
            AddRecordDialog(this, dao, { todayPanelMealTimes() }, { refreshAll() }).show()
        }
        findViewById<View>(R.id.btnTrend).setOnClickListener {
            TrendDialog(this, dao.findAll()).show()
        }
        findViewById<View>(R.id.btnCalendar).setOnClickListener {
            CalendarDialog(this, dao, { todayPanelMealTimes() }, { refreshAll() }).show()
        }
        findViewById<View>(R.id.tvSettings).setOnClickListener { openSettings() }

        // AI 控糖建议（规格 6 章）
        findViewById<View>(R.id.tvAi).setOnClickListener {
            AiSuggestionDialog.show(this, dao, aiConfig)
        }

        // AGP 标准化葡萄糖图谱（规格 9.11）
        findViewById<View>(R.id.tvAgp).setOnClickListener {
            AgpReportDialog.show(this, dao) { report ->
                pendingAgpReport = report
                val name = "糖伴SugarPal-AGP报告-${TimeFmt.formatDate(LocalDate.now())}.pdf"
                runCatching { saveAgpPdf.launch(name) }.onFailure {
                    Toast.makeText(this, "无法打开保存对话框：${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---------------- AI 配置（规格 6.1~6.3） ----------------

    private val aiConfig by lazy { AiConfig.load(this) }

    // ---------------- AGP 报告 PDF 导出（规格 7.3 / 9.11） ----------------

    private var pendingAgpReport: AgpCalculator.AgpReport? = null

    private val saveAgpPdf =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            val report = pendingAgpReport
            if (uri == null || report == null) return@registerForActivityResult
            val ok = runCatching {
                contentResolver.openOutputStream(uri)?.use { out -> AgpPdfExporter.export(out, report) }
                    ?: throw IllegalStateException("无法写入所选文件")
            }.isSuccess
            Toast.makeText(this, if (ok) "导出成功" else "导出失败", Toast.LENGTH_SHORT).show()
        }

    // ---------------- 设置与数据互通（CSV 导入 / 导出） ----------------

    private fun openSettings() {
        SettingsDialog.show(
            this,
            dao.findAll().size,
            BuildConfig.VERSION_NAME,
            onImport = {
                runCatching {
                    importCsv.launch(
                        arrayOf(
                            "text/comma-separated-values",
                            "text/csv",
                            "text/plain",
                            "application/vnd.ms-excel"
                        )
                    )
                }.onFailure {
                    Toast.makeText(this, "无法打开文件选择器：${it.message}", Toast.LENGTH_LONG).show()
                }
            },
            onExport = {
                pendingTemplate = false
                saveCsv.launch("糖伴SugarPal-导出-${TimeFmt.formatDate(LocalDate.now())}.csv")
            },
            onTemplate = {
                pendingTemplate = true
                saveCsv.launch("糖伴SugarPal-导入模板.csv")
            },
            onClear = { confirmClearAll() },
            onAi = { AiSuggestionDialog.show(this, dao, aiConfig) }
        )
    }

    /** 读取所选文件 → 解码 → 解析预览 → 弹预览对话框确认入库 */
    private fun handleImport(uri: Uri) {
        val outcome = runCatching {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("无法读取所选文件")
            val text = CsvImporter.decode(bytes)
            CsvImporter.parse(text, CsvImporter.buildDbKeys(dao.findAll()), dao)
        }
        outcome.onSuccess { preview ->
            if (preview.rows.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("无法导入")
                    .setMessage(preview.warning.ifBlank { "文件中没有可解析的数据行。" })
                    .setPositiveButton("知道了", null)
                    .show()
            } else {
                ImportPreviewDialog.show(this, dao, preview) { result ->
                    val msg = if (result.failed > 0) {
                        "${result.text}；${result.failures.take(3).joinToString("；")}"
                    } else {
                        result.text
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    refreshAll()
                }
            }
        }
        outcome.onFailure {
            AlertDialog.Builder(this)
                .setTitle("导入失败")
                .setMessage(it.message ?: it.javaClass.simpleName)
                .setPositiveButton("知道了", null)
                .show()
        }
    }

    /** 写出文件：模板或全量数据 */
    private fun handleSave(uri: Uri) {
        val template = pendingTemplate
        pendingTemplate = false
        val outcome = runCatching {
            val out = contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("无法写入所选文件")
            if (template) {
                out.use { it.write(CsvImporter.template().toByteArray(Charsets.UTF_8)) }
            } else {
                CsvExporter.write(out, dao.findAll())
            }
        }
        outcome.onSuccess {
            Toast.makeText(this, if (template) "模板已保存" else "数据已导出", Toast.LENGTH_SHORT).show()
        }
        outcome.onFailure {
            Toast.makeText(this, "保存失败：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("清空全部数据？")
            .setMessage("将删除全部血糖记录与已保存的用餐时间，操作不可撤销。")
            .setPositiveButton("清空") { _, _ ->
                val n = dao.deleteAll()
                dao.deleteAllMealTimes()
                Toast.makeText(this, "已清空 $n 条记录", Toast.LENGTH_SHORT).show()
                refreshAll()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    /** 当前业务日（凌晨 4 点边界） */
    private fun currentBusinessDate(): LocalDate =
        PeriodClassifier.getBusinessDate(LocalDateTime.now()) ?: LocalDate.now()

    /** 今日面板用餐时间：读 meal_times 表当前业务日数据，跨过凌晨 4 点自然清零 */
    private fun todayPanelMealTimes(): Map<String, LocalDateTime> =
        dao.findMealTimesByBusinessDate(currentBusinessDate())

    private fun bindMealRow(tv: TextView, mealName: String) {
        tv.setOnClickListener {
            val bd = currentBusinessDate()
            val saved = dao.findMealTimesByBusinessDate(bd)[mealName]
            val base = saved ?: LocalDateTime.now()
            TimePickerDialog(
                this,
                { _, h, mi ->
                    dao.upsertMealTime(bd, mealName, LocalDateTime.of(bd, LocalTime.of(h, mi)))
                    refreshAll()
                    Toast.makeText(this, "$mealName 用餐时间已保存", Toast.LENGTH_SHORT).show()
                },
                base.hour, base.minute, true
            ).show()
        }
        tv.setOnLongClickListener {
            val bd = currentBusinessDate()
            if (dao.findMealTimesByBusinessDate(bd).containsKey(mealName)) {
                AlertDialog.Builder(this)
                    .setTitle("清除$mealName 用餐时间？")
                    .setPositiveButton("清除") { _, _ ->
                        dao.deleteMealTime(bd, mealName)
                        refreshAll()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            true
        }
    }

    private fun refreshAll() {
        val bd = currentBusinessDate()
        tvToday.text = TimeFmt.formatDate(LocalDate.now())
        tvMealBusinessDate.text = "业务日 ${TimeFmt.formatDate(bd)}（凌晨 4 点切换）"

        val panel = dao.findMealTimesByBusinessDate(bd)
        mealNames.forEach { name ->
            val tv = mealViews[name] ?: return@forEach
            val t = panel[name]
            tv.text = if (t == null) "未设置" else TimeFmt.hm(t)
            tv.setTextColor(getColor(if (t == null) R.color.color_text else R.color.color_orange_dark))
        }

        refreshRecords()
    }

    private fun refreshRecords() {
        val list = dao.findLatest(8)
        recordContainer.removeAllViews()
        tvEmptyHint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        tvRecordCount.text = "共 ${dao.findAll().size} 条 · 显示近 8 条"

        list.forEach { r ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_record, recordContainer, false)
            RecordRowBinder.bind(this, dao, { todayPanelMealTimes() }, row, r, true) { record ->
                AlertDialog.Builder(this)
                    .setTitle("删除这条记录？")
                    .setMessage("${TimeFmt.full(record.recordTime)} · ${String.format("%.1f", record.bloodSugar)} mmol/L")
                    .setPositiveButton("删除") { _, _ ->
                        dao.delete(record.id)
                        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                        refreshAll()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            recordContainer.addView(row)
        }
    }
}
