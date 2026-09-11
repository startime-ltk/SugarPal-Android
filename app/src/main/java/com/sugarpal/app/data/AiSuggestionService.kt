package com.sugarpal.app.data

import com.bloodsugar.model.BloodSugarRecord
import com.bloodsugar.util.PeriodClassifier
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** AI 建议异常，message 均为可直接展示的中文文案（规格 6.6） */
class AiException(message: String) : Exception(message)

/**
 * AI 建议服务（规格 6.4~6.7）：
 * 送模文本拼装 + 单次请求（不做自动重试，重试与模型切换由界面层负责）。
 */
object AiSuggestionService {

    private const val MAX_RECORDS = 30
    private const val CONNECT_TIMEOUT = 15_000
    private const val REQUEST_TIMEOUT = 60_000

    /** system prompt 必须逐字复用 PC 端原文 */
    const val SYSTEM_PROMPT = "你是专业的糖尿病管理助手。请根据用户提供的血糖记录，给出个性化控糖建议。要求：分条列出，语气温和务实，先总结趋势和问题，再给可执行的饮食、运动、监测建议，最后提醒建议仅供参考、不能替代医生诊断。控制在 400 字以内。"

    private val LINE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    // ---------------- 送模数据文本（规格 6.5） ----------------

    /**
     * 拼装送模文本：最近 30 条（时间倒序取、再升序排列）+ 统计行 + 近 30 天健康数据。
     * 纯函数，便于与 PC 端输出逐字比对。
     */
    fun buildDataText(records: List<BloodSugarRecord>, now: LocalDateTime): String {
        val picked = records
            .filter { it.recordTime != null }
            .sortedByDescending { it.recordTime }
            .take(MAX_RECORDS)
            .sortedBy { it.recordTime }

        if (picked.isEmpty()) {
            throw AiException("暂无可用的血糖记录，请先添加记录再生成建议")
        }

        val sb = StringBuilder()
        sb.append("最近 ${picked.size} 条血糖记录（正常参考区间：空腹 3.9-6.1，餐后1h 3.9-8.9，餐后2h 3.9-7.8 mmol/L）：\n")

        picked.forEach { r ->
            sb.append("- ").append(r.recordTime!!.format(LINE_FMT))
                .append(" | ").append(String.format(Locale.US, "%.1f", r.bloodSugar)).append(" mmol/L")
                .append(" | 时段:").append(r.mealPeriod?.takeIf { it.isNotBlank() } ?: "未知")
                .append(" | 餐别:").append(r.mealType?.takeIf { it.isNotBlank() } ?: "无")
            val note = r.note?.trim().orEmpty()
            if (note.isNotEmpty()) sb.append(" | 备注:").append(note)
            sb.append('\n')
        }

        // 统计行
        val values = picked.map { it.bloodSugar }
        val normalCount = picked.count { PeriodClassifier.isNormal(it.mealPeriod, it.bloodSugar) }
        sb.append(
            String.format(
                Locale.US,
                "简单统计：共 %d 条，平均值 %.1f，最高 %.1f，最低 %.1f，正常率 %d/%d (%.0f%%)",
                picked.size, values.average(), values.max(), values.min(),
                normalCount, picked.size, normalCount * 100.0 / picked.size
            )
        )
        val fasting = picked.filter { it.mealPeriod == "空腹" }.map { it.bloodSugar }
        if (fasting.isNotEmpty()) {
            sb.append(String.format(Locale.US, "；空腹平均 %.1f", fasting.average()))
        }
        val after2h = picked.filter { it.mealPeriod == "餐后2h" }.map { it.bloodSugar }
        if (after2h.isNotEmpty()) {
            sb.append(String.format(Locale.US, "；餐后2h平均 %.1f", after2h.average()))
        }
        sb.append("。\n")

        // 近 30 天健康数据（基于全部传入记录，不限于送模的 30 条）
        val since = now.minusDays(30)
        val recent = records.filter { it.recordTime != null && !it.recordTime!!.isBefore(since) }
        val parts = ArrayList<String>()

        val insulin = recent.map { it.insulin }.filter { it > 0 }
        if (insulin.isNotEmpty()) parts.add(String.format(Locale.US, "胰岛素平均 %.1f U/次", insulin.average()))
        val carbs = recent.map { it.carbs }.filter { it > 0 }
        if (carbs.isNotEmpty()) parts.add(String.format(Locale.US, "碳水平均 %.0f g/次", carbs.average()))
        val activity = recent.map { it.activity }.filter { it > 0 }
        if (activity.isNotEmpty()) parts.add(String.format(Locale.US, "运动平均 %.0f 分钟/次", activity.average()))
        val pulse = recent.map { it.pulse }.filter { it > 0 }
        if (pulse.isNotEmpty()) parts.add(String.format(Locale.US, "脉搏平均 %.0f 次/分", pulse.average()))
        val weight = recent.filter { it.weight > 0 }.maxByOrNull { it.recordTime!! }
        if (weight != null) parts.add(String.format(Locale.US, "最新体重 %.1f kg", weight.weight))
        val bp = recent.filter { !it.bloodPressure.isNullOrBlank() }.maxByOrNull { it.recordTime!! }
        if (bp != null) parts.add("最新血压 ${bp.bloodPressure}")

        if (parts.isNotEmpty()) {
            sb.append("近 30 天健康数据：").append(parts.joinToString("，")).append("。\n")
        }
        return sb.toString()
    }

    // ---------------- 请求（规格 6.4 / 6.6） ----------------

    /** 单次生成；阻塞调用，需在子线程执行 */
    fun generate(config: AiConfig, records: List<BloodSugarRecord>, now: LocalDateTime = LocalDateTime.now()): String {
        val provider = config.activeProviderId()
        val display = AiConfig.displayName(provider)

        val key = config.apiKey(provider)
        if (key.isBlank()) {
            throw AiException("尚未配置 $display 的 API Key，请先点击「设置 API Key」填写")
        }
        val baseUrl = config.baseUrl(provider)
        if (baseUrl.isBlank()) {
            throw AiException("模型接口地址未配置，请在设置中填写")
        }
        val model = config.model(provider)
        if (model.isBlank()) {
            throw AiException("模型名称未配置，请在设置中填写")
        }

        val dataText = buildDataText(records, now)

        val url = try {
            URL(baseUrl)
        } catch (e: MalformedURLException) {
            throw AiException("接口地址格式不正确，请在设置中检查")
        }

        val body = JSONObject().apply {
            put("model", model)
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    put(JSONObject().put("role", "user").put("content", "$dataText\n\n请给出控糖建议。"))
                }
            )
            put("temperature", 0.7)
        }.toString()

        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = REQUEST_TIMEOUT
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $key")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code >= 400) {
                throw AiException("模型接口返回错误（HTTP $code）：${errorMessage(text)}")
            }
            return parseContent(text)
        } catch (e: AiException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AiException("请求超时，请检查网络后稍后重试")
        } catch (e: ConnectException) {
            throw AiException("无法连接模型服务，请检查网络或接口地址")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AiException("请求被中断，请重试")
        } catch (e: IOException) {
            throw AiException("网络请求失败：${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    /** 成功路径：choices[0].message.content */
    private fun parseContent(text: String): String {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw AiException("无法解析模型返回内容，请稍后重试")
        }
        val content = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
        if (content.isNullOrBlank()) {
            throw AiException("模型返回内容为空，请稍后重试")
        }
        return content.trim()
    }

    /** 错误体：优先 error.message，非 JSON 截断前 200 字符，空体返回「无详细信息」 */
    private fun errorMessage(text: String): String {
        if (text.isBlank()) return "无详细信息"
        return try {
            val msg = JSONObject(text).optJSONObject("error")?.optString("message")
            if (msg.isNullOrBlank()) text.take(200) else msg
        } catch (e: Exception) {
            text.take(200)
        }
    }
}
