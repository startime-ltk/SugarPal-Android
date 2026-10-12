package com.sugarpal.app.data

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 健康摘要 PDF 导出（P0-2）。
 *
 * 复用 AGP 报告同一套 Android PdfDocument 绘制技术栈与糖果马卡龙配色，
 * 排版刻意与完整 AGP 报告区分：单页、大字卡片、突出可读性的趋势说明，
 * 便于家人/医生快速阅读近 30 天整体情况。
 */
object SummaryPdfExporter {

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
    private val TEXT_GRAY = Color.parseColor("#6B6B6B")
    private val FOOTER_RED = Color.parseColor("#C0392B")
    private val WARN_ORANGE = Color.parseColor("#F58B36")

    fun export(out: OutputStream, summary: HealthSummaryService.Summary) {
        Renderer(summary).render(out)
    }

    private class Renderer(private val s: HealthSummaryService.Summary) {

        private val doc = PdfDocument()
        private lateinit var page: PdfDocument.Page
        private lateinit var canvas: Canvas
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
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
            canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            y = MARGIN_T

            drawHeader()
            if (s.recordCount == 0) {
                drawText(
                    "近 30 天暂无血糖记录，无法生成健康摘要。",
                    paint(11f, TEXT_GRAY, false, Paint.Align.CENTER),
                    y + 24f,
                    Paint.Align.CENTER,
                    PAGE_W / 2f
                )
            } else {
                drawCards()
                drawTrend()
                drawNotes()
            }
            drawFooter()

            doc.finishPage(page)
            doc.writeTo(out)
            doc.close()
            out.flush()
        }

        private fun drawText(text: String, p: Paint, baseline: Float, align: Paint.Align, anchorX: Float = MARGIN_L) {
            canvas.drawText(text, anchorX, baseline, p)
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

        private fun drawHeader() {
            drawText("糖伴SugarPal · 健康摘要", paint(19f, CANDY_GREEN, true, Paint.Align.CENTER), y + 16f, Paint.Align.CENTER, PAGE_W / 2f)
            y += 30f

            val sub = "统计范围：${s.startDate} 至 ${s.endDate}（近 ${s.rangeDays} 天）　" +
                "记录数：${s.recordCount} 条　覆盖天数：${s.dayCount} 天　" +
                "生成时间：${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}"
            val subPaint = fitPaint(sub, 10.5f, CONTENT_W, TEXT_GRAY)
            drawText(sub, subPaint, y + 11f, Paint.Align.CENTER, PAGE_W / 2f)
            y += 26f
        }

        private fun drawCards() {
            drawText("一、近 30 天核心指标", paint(12.5f, CANDY_GREEN, true), y + 14f, Paint.Align.LEFT)
            y += 22f

            val cards = listOf(
                "平均血糖" to String.format(Locale.US, "%.2f mmol/L", s.mean),
                "估算 HbA1c" to String.format(Locale.US, "%.2f %%", s.hba1c),
                "TIR 达标占比" to String.format(Locale.US, "%.1f %%", s.tir),
                "变异系数 CV" to String.format(Locale.US, "%.1f %%", s.cv),
                "偏低次数" to "${s.lowCount} 次",
                "偏高次数" to "${s.highCount} 次",
                "覆盖天数" to "${s.dayCount} 天",
                "记录条数" to "${s.recordCount} 条"
            )

            val gap = 8f
            val cardW = (CONTENT_W - gap * 3) / 4f
            val cardH = 46f
            val fill = paint(1f, CANDY_MINT).apply { style = Paint.Style.FILL }
            val stroke = paint(1f, CANDY_GREEN_LIGHT).apply { style = Paint.Style.STROKE }

            cards.forEachIndexed { i, (label, value) ->
                val col = i % 4
                val row = i / 4
                val left = MARGIN_L + col * (cardW + gap)
                val top = y + row * (cardH + gap)
                val rect = RectF(left, top, left + cardW, top + cardH)
                canvas.drawRoundRect(rect, 8f, 8f, fill)
                canvas.drawRoundRect(rect, 8f, 8f, stroke)
                drawText(label, fitPaint(label, 9f, cardW - 8f, TEXT_GRAY), top + 16f, Paint.Align.CENTER, rect.centerX())
                val valuePaint = fitPaint(value, 12.5f, cardW - 8f, CANDY_GREEN, bold = true)
                drawText(value, valuePaint, top + 34f, Paint.Align.CENTER, rect.centerX())
            }
            y += 2 * cardH + gap + 10f
        }

        private fun drawTrend() {
            drawText("二、趋势说明", paint(12.5f, CANDY_GREEN, true), y + 14f, Paint.Align.LEFT)
            y += 22f

            val trendPaint = paint(11.5f, Color.parseColor("#333333"))
            wrap(s.trendNote, trendPaint, CONTENT_W).forEach { line ->
                drawText(line, trendPaint, y + 12f, Paint.Align.LEFT)
                y += 15f
            }
            y += 4f

            if (s.lowCount > 0 || s.highCount > 0) {
                val warn = if (s.lowCount > 0 && s.highCount > 0) {
                    "摘要期间存在偏低与偏高报警记录，建议尽快就医或调整控糖方案。"
                } else if (s.lowCount > 0) {
                    "摘要期间存在偏低报警记录，需警惕低血糖风险，随身携带含糖食品。"
                } else {
                    "摘要期间存在偏高报警记录，建议加强监测并咨询医生调整方案。"
                }
                val warnPaint = paint(11f, WARN_ORANGE, true)
                wrap(warn, warnPaint, CONTENT_W).forEach { line ->
                    drawText(line, warnPaint, y + 12f, Paint.Align.LEFT)
                    y += 15f
                }
            }
        }

        private fun drawNotes() {
            y += 10f
            drawText("三、口径说明", paint(12.5f, CANDY_GREEN, true), y + 14f, Paint.Align.LEFT)
            y += 22f

            val notes = listOf(
                "1. 统计范围为近 ${s.rangeDays} 个业务日（业务日以凌晨 4 点为分界，0~4 点记录归前一天）。",
                "2. 平均血糖、CV、估算 HbA1c、TIR 与 AGP 报告同口径：达标判定按记录时段区间（空腹 3.9~6.1、餐后1h 3.9~8.9、餐后2h/3h 3.9~7.8 mmol/L）。",
                "3. 偏低/偏高次数按报警阈值判定（默认偏低 < 3.9、偏高 > 13.9 mmol/L，可在设置中调整）。",
                "4. 估算 HbA1c 由平均血糖换算：mmol/L × 18 → mg/dL，HbA1c% ≈ (mg/dL + 46.7) ÷ 28.7，仅供参考。"
            )
            val notePaint = paint(9f, TEXT_GRAY)
            notes.forEach { note ->
                wrap(note, notePaint, CONTENT_W).forEach { line ->
                    drawText(line, notePaint, y + 11f, Paint.Align.LEFT)
                    y += 13f
                }
            }
        }

        private fun drawFooter() {
            val footerY = PAGE_H - MARGIN_B + 14f
            drawText(
                "本摘要仅供健康管理参考，不作为医疗诊断依据。如有身体不适，请及时就医。",
                paint(9f, FOOTER_RED, true, Paint.Align.CENTER),
                footerY,
                Paint.Align.CENTER,
                PAGE_W / 2f
            )
        }
    }
}
