package com.sugarpal.app.data

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * AGP 报告 PDF 导出（A4 595 × 842 pt，边距 40/40/42/42，版式对齐规格 7.3）。
 * 内容超出单页时自动续页，保证口径说明与页脚完整输出。
 */
object AgpPdfExporter {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN_L = 40f
    private const val MARGIN_R = 40f
    private const val MARGIN_T = 42f
    private const val MARGIN_B = 42f
    private const val CONTENT_W = PAGE_W - MARGIN_L - MARGIN_R

    private val CANDY_GREEN = Color.parseColor("#2BB673")
    private val CANDY_GREEN_LIGHT = Color.parseColor("#D9F9E4")
    private val CANDY_MINT = Color.parseColor("#EAFCF0")
    private val BAND_COLOR = Color.parseColor("#9FE0BD")
    private val TARGET_BG = Color.parseColor("#E4F9EC")
    private val GRID_COLOR = Color.parseColor("#DDDDDD")
    private val AXIS_COLOR = Color.parseColor("#999999")
    private val TEXT_GRAY = Color.parseColor("#6B6B6B")
    private val FOOTER_RED = Color.parseColor("#C0392B")

    fun export(out: OutputStream, report: AgpCalculator.AgpReport) {
        Renderer(report).render(out)
    }

    private class Renderer(private val report: AgpCalculator.AgpReport) {

        private val doc = PdfDocument()
        private lateinit var page: PdfDocument.Page
        private lateinit var canvas: Canvas
        private var pageNo = 0
        private var y = MARGIN_T

        private fun paint(
            size: Float,
            color: Int,
            bold: Boolean = false,
            align: Paint.Align = Paint.Align.LEFT
        ): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            textAlign = align
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

        fun render(out: OutputStream) {
            newPage()
            drawHeader()

            if (report.recordCount == 0) {
                drawText(
                    "所选范围内暂无血糖记录，无法生成 AGP 报告。",
                    paint(11f, TEXT_GRAY, false, Paint.Align.CENTER),
                    y + 16f,
                    Paint.Align.CENTER
                )
            } else {
                drawCards()
                drawCurve()
                drawTimeSegmentTable()
                drawMealTable()
                drawNotes()
            }

            drawFooter()
            doc.finishPage(page)
            doc.writeTo(out)
            doc.close()
            out.flush()
        }

        private fun newPage() {
            if (::page.isInitialized) doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            y = MARGIN_T
        }

        /** 空间不足时自动续页 */
        private fun ensure(need: Float) {
            if (y + need > PAGE_H - MARGIN_B - 18f) newPage()
        }

        private fun drawHeader() {
            drawText("糖伴SugarPal · AGP 标准化葡萄糖图谱报告", paint(17f, CANDY_GREEN, true, Paint.Align.CENTER), y + 15f, Paint.Align.CENTER)
            y += 30f

            val sub = "统计范围：${report.startDate} 至 ${report.endDate}（近 ${report.rangeDays} 天）　" +
                "记录数：${report.recordCount} 条　覆盖天数：${report.dayCount} 天　" +
                "生成时间：${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}"
            val subPaint = fitPaint(sub, 10f, CONTENT_W, TEXT_GRAY)
            drawText(sub, subPaint, y + 11f, Paint.Align.CENTER)
            y += 26f
        }

        private fun drawFooter() {
            val footerY = PAGE_H - MARGIN_B + 14f
            drawText(
                "本报告仅供健康管理参考，不作为医疗诊断依据。如有身体不适，请及时就医。",
                paint(9f, FOOTER_RED, true, Paint.Align.CENTER),
                footerY,
                Paint.Align.CENTER
            )
        }

        // ---------------- 一、核心指标 ----------------

        private fun drawCards() {
            ensure(24f)
            drawText("一、核心指标", paint(12f, CANDY_GREEN, true), y + 14f, Paint.Align.LEFT)
            y += 22f

            val cards = listOf(
                "平均血糖" to String.format(Locale.US, "%.2f mmol/L", report.mean),
                "标准差 SD" to String.format(Locale.US, "%.2f mmol/L", report.sd),
                "变异系数 CV" to String.format(Locale.US, "%.1f %%", report.cv),
                "估算 HbA1c" to String.format(Locale.US, "%.2f %%", report.hba1c),
                "TIR 达标占比" to String.format(Locale.US, "%.1f %%（%d 条）", report.tir, report.inRangeCount),
                "TAR 偏高占比" to String.format(Locale.US, "%.1f %%（%d 条）", report.tar, report.aboveCount),
                "TBR 偏低占比" to String.format(Locale.US, "%.1f %%（%d 条）", report.tbr, report.belowCount),
                "血糖波动区间" to String.format(Locale.US, "%.1f ~ %.1f", report.minValue, report.maxValue)
            )

            val gap = 8f
            val cardW = (CONTENT_W - gap * 3) / 4f
            val cardH = 44f
            val fill = paint(1f, CANDY_MINT).apply { style = Paint.Style.FILL }
            val stroke = paint(1f, CANDY_GREEN_LIGHT).apply { style = Paint.Style.STROKE }

            cards.forEachIndexed { i, (label, value) ->
                val col = i % 4
                val row = i / 4
                val left = MARGIN_L + col * (cardW + gap)
                val top = y + row * (cardH + gap)
                val rect = RectF(left, top, left + cardW, top + cardH)
                canvas.drawRoundRect(rect, 6f, 6f, fill)
                canvas.drawRoundRect(rect, 6f, 6f, stroke)
                drawText(label, fitPaint(label, 8.5f, cardW - 8f, TEXT_GRAY), top + 15f, Paint.Align.CENTER, rect.centerX())
                val valuePaint = fitPaint(value, 11f, cardW - 8f, CANDY_GREEN, bold = true)
                drawText(value, valuePaint, top + 32f, Paint.Align.CENTER, rect.centerX())
            }
            y += 2 * cardH + gap + 10f
        }

        // ---------------- 二、AGP 24 小时分位曲线 ----------------

        private fun drawCurve() {
            ensure(260f)
            drawText("二、AGP 24 小时分位曲线", paint(12f, CANDY_GREEN, true), y + 14f, Paint.Align.LEFT)
            y += 20f

            val note = "深绿实线 = 中位数（P50）；浅绿区域 = 25%~75% 分位区间；浅绿底色为目标范围（3.9~8.9 mmol/L，上限依餐别浮动，图中取各餐别上限最大值）。"
            val notePaint = fitPaint(note, 8f, CONTENT_W, TEXT_GRAY)
            drawText(note, notePaint, y + 9f, Paint.Align.LEFT)
            y += 16f

            val x = MARGIN_L
            val w = CONTENT_W
            val h = 210f
            val plotLeft = x + 34f
            val plotRight = x + w - 10f
            val plotTop = y + 8f
            val plotBottom = y + h - 20f

            val yMin = minOf(2.0, floor(report.minValue) - 1.0).coerceAtLeast(0.0)
            val yMax = maxOf(12.0, ceil(report.maxValue) + 1.0)

            fun yOf(v: Double): Float {
                val ratio = ((v - yMin) / (yMax - yMin)).coerceIn(0.0, 1.0)
                return (plotBottom - (plotBottom - plotTop) * ratio).toFloat()
            }
            fun xOf(hour: Int): Float = plotLeft + (plotRight - plotLeft) * hour / 23f

            // 目标范围底色
            val targetFill = paint(1f, TARGET_BG).apply { style = Paint.Style.FILL }
            canvas.drawRect(
                plotLeft, yOf(AgpCalculator.TARGET_HIGH_MAX), plotRight, yOf(AgpCalculator.TARGET_LOW), targetFill
            )

            // 网格
            val gridPaint = paint(0.4f, GRID_COLOR).apply { style = Paint.Style.STROKE; strokeWidth = 0.4f }
            val scalePaint = paint(7f, TEXT_GRAY, align = Paint.Align.RIGHT)
            var v = ceil(yMin / 2.0) * 2.0
            while (v <= yMax) {
                val py = yOf(v)
                canvas.drawLine(plotLeft, py, plotRight, py, gridPaint)
                drawText(String.format(Locale.US, "%.0f", v), scalePaint, py + 2.5f, Paint.Align.RIGHT, plotLeft - 4f)
                v += 2.0
            }
            val hourLabelPaint = paint(7f, TEXT_GRAY, align = Paint.Align.CENTER)
            var hh = 0
            while (hh <= 21) {
                val px = xOf(hh)
                canvas.drawLine(px, plotTop, px, plotBottom, gridPaint)
                drawText(String.format(Locale.US, "%02d:00", hh), hourLabelPaint, plotBottom + 10f, Paint.Align.CENTER, px)
                hh += 3
            }

            val axisPaint = paint(0.8f, AXIS_COLOR).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f }
            canvas.drawRect(plotLeft, plotTop, plotRight, plotBottom, axisPaint)

            val curve = report.curve
            if (curve.isNotEmpty()) {
                // 分位带 P25~P75
                val bandPath = Path()
                curve.forEachIndexed { i, p ->
                    val px = xOf(p.hour)
                    val py = yOf(p.p75)
                    if (i == 0) bandPath.moveTo(px, py) else bandPath.lineTo(px, py)
                }
                for (i in curve.indices.reversed()) {
                    bandPath.lineTo(xOf(curve[i].hour), yOf(curve[i].p25))
                }
                bandPath.close()
                val bandPaint = paint(1f, BAND_COLOR).apply { style = Paint.Style.FILL; alpha = 140 }
                canvas.drawPath(bandPath, bandPaint)

                // 中位数折线 + 数据点
                val medianPath = Path()
                curve.forEachIndexed { i, p ->
                    val px = xOf(p.hour)
                    val py = yOf(p.p50)
                    if (i == 0) medianPath.moveTo(px, py) else medianPath.lineTo(px, py)
                }
                val medianPaint = paint(2f, CANDY_GREEN).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                canvas.drawPath(medianPath, medianPaint)

                val dotPaint = paint(1f, CANDY_GREEN).apply { style = Paint.Style.FILL }
                curve.forEach { p -> canvas.drawCircle(xOf(p.hour), yOf(p.p50), 2.2f, dotPaint) }
            }

            // 图例（右上）
            val legendPaint = paint(7.5f, TEXT_GRAY)
            val legendX = plotRight - 96f
            var legendY = plotTop + 12f
            val items = listOf(
                Triple("中位数 P50", CANDY_GREEN, 0.6f),
                Triple("25%~75% 分位带", BAND_COLOR, 0.55f),
                Triple("目标范围 3.9~8.9", TARGET_BG, 0.9f)
            )
            items.forEach { (text, color, alpha) ->
                val swatch = paint(1f, color).apply { style = Paint.Style.FILL; this.alpha = (alpha * 255).toInt() }
                canvas.drawRect(legendX, legendY - 5f, legendX + 8f, legendY + 3f, swatch)
                drawText(text, legendPaint, legendY + 2.5f, Paint.Align.LEFT, legendX + 12f)
                legendY += 12f
            }

            y += h + 8f
        }

        // ---------------- 表格 ----------------

        private fun drawTimeSegmentTable() {
            ensure(140f)
            drawText("三、分时段统计", paint(12f, CANDY_GREEN, true), y + 14f, Paint.Align.LEFT)
            y += 20f

            val ratios = listOf(22f, 14f, 20f, 20f, 24f)
            val total = ratios.sum()
            val widths = ratios.map { CONTENT_W * it / total }

            drawTableHeader(listOf("时段", "记录数", "平均(mmol/L)", "最高/最低", "达标率"), widths)

            val rows = report.timeSegments.map { g ->
                if (g.count == 0) {
                    listOf(g.name, "0", "-", "-", "-")
                } else {
                    listOf(
                        g.name,
                        g.count.toString(),
                        String.format(Locale.US, "%.2f", g.avg),
                        String.format(Locale.US, "%.1f / %.1f", g.max, g.min),
                        String.format(Locale.US, "%.1f %%", g.tirPercent)
                    )
                }
            }
            drawTableRows(rows, widths)
            y += 12f
        }

        private fun drawMealTable() {
            ensure(120f)
            drawText("四、按餐别统计", paint(12f, CANDY_GREEN, true), y + 14f, Paint.Align.LEFT)
            y += 20f

            val ratios = listOf(22f, 14f, 20f, 20f, 24f)
            val total = ratios.sum()
            val widths = ratios.map { CONTENT_W * it / total }

            drawTableHeader(listOf("餐别", "记录数", "平均(mmol/L)", "最高/最低", "达标率"), widths)

            val rows = report.mealGroups.map { g ->
                listOf(
                    g.name,
                    g.count.toString(),
                    String.format(Locale.US, "%.2f", g.avg),
                    String.format(Locale.US, "%.1f / %.1f", g.max, g.min),
                    String.format(Locale.US, "%.1f %%", g.tirPercent)
                )
            }
            drawTableRows(rows, widths)
            y += 12f
        }

        private fun drawTableHeader(headers: List<String>, widths: List<Float>) {
            val bg = paint(1f, CANDY_GREEN_LIGHT).apply { style = Paint.Style.FILL }
            val rowH = 20f
            canvas.drawRect(MARGIN_L, y, MARGIN_L + CONTENT_W, y + rowH, bg)
            var x = MARGIN_L
            headers.forEachIndexed { i, text ->
                drawText(text, fitPaint(text, 9f, widths[i] - 4f, CANDY_GREEN, bold = true), y + 14f, Paint.Align.CENTER, x + widths[i] / 2f)
                x += widths[i]
            }
            y += rowH
        }

        private fun drawTableRows(rows: List<List<String>>, widths: List<Float>) {
            if (rows.isEmpty()) return
            val rowH = 19f
            val linePaint = paint(0.4f, GRID_COLOR).apply { style = Paint.Style.STROKE; strokeWidth = 0.4f }
            val maxRowsPerPage = 12

            rows.chunked(maxRowsPerPage).forEach { chunk ->
                ensure(rowH * chunk.size + 6f)
                chunk.forEach { cells ->
                    var x = MARGIN_L
                    cells.forEachIndexed { i, text ->
                        drawText(
                            text,
                            fitPaint(text, 9f, widths[i] - 4f, Color.parseColor("#333333")),
                            y + 13f,
                            Paint.Align.CENTER,
                            x + widths[i] / 2f
                        )
                        x += widths[i]
                    }
                    canvas.drawLine(MARGIN_L, y + rowH, MARGIN_L + CONTENT_W, y + rowH, linePaint)
                    y += rowH
                }
            }
        }

        // ---------------- 五、指标口径说明 ----------------

        private fun drawNotes() {
            ensure(30f)
            drawText("五、指标口径说明", paint(12f, CANDY_GREEN, true), y + 14f, Paint.Align.LEFT)
            y += 20f

            val notes = listOf(
                "1. 统计范围为近 ${report.rangeDays} 个业务日（业务日以凌晨 4 点为分界，0~4 点记录归前一天）。",
                "2. 达标判定按每条记录所属时段的正常区间：空腹 3.9~6.1、餐后1h 3.9~8.9、餐后2h/3h 3.9~7.8 mmol/L；TIR 为目标范围内记录占比，TAR 为高于各自上限占比，TBR 为低于 3.9 mmol/L 占比。",
                "3. 标准差采用样本标准差（n-1）；CV = SD ÷ 平均血糖 × 100%，一般建议控制在 36% 以内。",
                "4. 估算 HbA1c 由本区间平均血糖换算：mmol/L × 18 → mg/dL，HbA1c% ≈ (mg/dL + 46.7) ÷ 28.7，与实验室检测存在偏差，仅供参考。",
                "5. AGP 曲线以每个整点为箱中心、±30 分钟为取样窗口，取该窗口内记录的 25%/50%/75% 分位数；无记录的整点按相邻整点线性插值补齐，以保证曲线连续、便于与医师沟通。"
            )

            val notePaint = paint(8f, TEXT_GRAY)
            notes.forEach { note ->
                wrap(note, notePaint, CONTENT_W).forEach { line ->
                    ensure(13f)
                    drawText(line, notePaint, y + 10f, Paint.Align.LEFT)
                    y += 13f
                }
            }
            y += 6f
        }

        // ---------------- 工具方法 ----------------

        private fun drawText(text: String, p: Paint, baseline: Float, align: Paint.Align, anchorX: Float = MARGIN_L) {
            val x = when (align) {
                Paint.Align.CENTER -> anchorX
                Paint.Align.RIGHT -> anchorX
                else -> anchorX
            }
            canvas.drawText(text, x, baseline, p)
        }

        /** 超宽文本自动缩字号（保持不换行） */
        private fun fitPaint(text: String, size: Float, maxWidth: Float, color: Int, bold: Boolean = false): Paint {
            val p = paint(size, color, bold)
            val w = p.measureText(text)
            if (w > maxWidth && w > 0f) {
                p.textSize = (size * maxWidth / w).coerceAtLeast(size * 0.6f)
            }
            return p
        }

        /** 中文按字符折行 */
        private fun wrap(text: String, p: Paint, maxWidth: Float): List<String> {
            val lines = ArrayList<String>()
            val sb = StringBuilder()
            var w = 0f
            text.forEach { ch ->
                val cw = p.measureText(ch.toString())
                if (w + cw > maxWidth && sb.isNotEmpty()) {
                    lines.add(sb.toString())
                    sb.setLength(0)
                    w = 0f
                }
                sb.append(ch)
                w += cw
            }
            if (sb.isNotEmpty()) lines.add(sb.toString())
            return lines
        }
    }
}
