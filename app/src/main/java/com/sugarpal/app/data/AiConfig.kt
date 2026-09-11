package com.sugarpal.app.data

import android.content.Context
import android.util.Base64
import java.io.File
import java.util.Properties

/**
 * AI 配置（规格 6.1~6.3）。
 *
 * 键名、Key 混淆算法（XOR + 标准 Base64）、明文兼容分支与 PC 端完全一致，
 * 因此 PC 端 `<数据根>/config.properties` 可原样迁移到 App（filesDir/config.properties）后直接使用。
 */
class AiConfig private constructor(private val file: File) {

    private val props = Properties()
    private var loaded = false

    /** 一个内置模型提供方 */
    data class Provider(
        val id: String,
        val display: String,
        val defaultBaseUrl: String,
        val defaultModel: String
    )

    companion object {
        /** 源码常量盐，逐字节循环异或，8 字节 */
        private val SALT = byteArrayOf(0x35, 0x5A, 0x13, 0x77, 0x2E, 0x44, 0x61, 0x1C)

        const val DEFAULT_PROVIDER = "glm"
        const val KEY_PREFIX = "enc:"
        const val PROP_FILE_NAME = "config.properties"

        /** 顺序即界面下拉框顺序 */
        val PROVIDERS = listOf(
            Provider("glm", "智谱 GLM-4.6-Flash", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4.6-flash"),
            Provider("deepseek", "DeepSeek Chat", "https://api.deepseek.com/v1/chat/completions", "deepseek-chat"),
            Provider("qwen", "通义千问 Qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-plus"),
            Provider("kimi", "Kimi (月之暗面)", "https://api.moonshot.cn/v1/chat/completions", "moonshot-v1-8k"),
            Provider("doubao", "豆包 (火山方舟)", "https://ark.cn-beijing.volces.com/api/v3/chat/completions", "doubao-seed-1-6-250615"),
            Provider("custom", "自定义 OpenAI 兼容", "", "")
        )

        /** 未知 provider 回退显示 id 本身 */
        fun displayName(id: String): String = PROVIDERS.firstOrNull { it.id == id }?.display ?: id

        /** 由显示名反查 id */
        fun providerIdByDisplay(display: String): String? = PROVIDERS.firstOrNull { it.display == display }?.id

        fun load(context: Context): AiConfig =
            AiConfig(File(context.filesDir, PROP_FILE_NAME)).apply { loadInternal() }

        /** 混淆：空串直接返回空串（不产生 enc: 前缀） */
        fun encodeKey(plain: String?): String {
            if (plain.isNullOrEmpty()) return ""
            val src = plain.toByteArray(Charsets.UTF_8)
            val out = ByteArray(src.size)
            for (i in src.indices) {
                out[i] = (src[i].toInt() xor SALT[i % SALT.size].toInt()).toByte()
            }
            return KEY_PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
        }

        /** 反混淆：非 enc: 前缀视为旧的明文 Key，原样返回；损坏数据返回空串不崩溃 */
        fun decodeKey(stored: String?): String {
            if (stored.isNullOrEmpty()) return ""
            if (!stored.startsWith(KEY_PREFIX)) return stored
            return try {
                val raw = Base64.decode(stored.substring(KEY_PREFIX.length), Base64.DEFAULT)
                val out = ByteArray(raw.size)
                for (i in raw.indices) {
                    out[i] = (raw[i].toInt() xor SALT[i % SALT.size].toInt()).toByte()
                }
                String(out, Charsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                ""
            }
        }
    }

    private fun loadInternal() {
        if (loaded) return
        loaded = true
        if (file.exists()) {
            runCatching { file.inputStream().use { props.load(it) } }
        }
        applyDefaults()
    }

    /** 键集合为固定全集，缺失即填默认值，保证与 PC 端配置文件双向兼容 */
    private fun applyDefaults() {
        putIfAbsent("ai.activeProvider", DEFAULT_PROVIDER)
        PROVIDERS.forEach { p ->
            putIfAbsent("ai.${p.id}.baseUrl", p.defaultBaseUrl)
            putIfAbsent("ai.${p.id}.model", p.defaultModel)
            putIfAbsent("ai.${p.id}.apiKey", "")
        }
    }

    private fun putIfAbsent(key: String, value: String) {
        if (!props.containsKey(key)) props.setProperty(key, value)
    }

    // ---------------- 读取 ----------------

    /** 非法值回退 glm */
    fun activeProviderId(): String {
        val v = props.getProperty("ai.activeProvider").orEmpty().trim()
        return if (PROVIDERS.any { it.id == v }) v else DEFAULT_PROVIDER
    }

    fun baseUrl(provider: String): String = props.getProperty("ai.$provider.baseUrl").orEmpty().trim()

    fun model(provider: String): String = props.getProperty("ai.$provider.model").orEmpty().trim()

    /** 返回解密后的明文 Key */
    fun apiKey(provider: String): String = decodeKey(props.getProperty("ai.$provider.apiKey"))

    fun hasApiKey(provider: String): Boolean = apiKey(provider).isNotBlank()

    // ---------------- 写入 ----------------

    fun setActiveProvider(provider: String) {
        props.setProperty("ai.activeProvider", provider)
    }

    fun setBaseUrl(provider: String, value: String) {
        props.setProperty("ai.$provider.baseUrl", value.trim())
    }

    fun setModel(provider: String, value: String) {
        props.setProperty("ai.$provider.model", value.trim())
    }

    /** 入参为明文，内部即混淆，内存中存储的始终是 enc: 串 */
    fun setApiKey(provider: String, value: String) {
        val plain = value.trim()
        props.setProperty("ai.$provider.apiKey", if (plain.isEmpty()) "" else encodeKey(plain))
    }

    /** 落盘；落盘前把历史遗留的明文 Key 统一混淆 */
    fun save() {
        try {
            PROVIDERS.forEach { p ->
                val key = "ai.${p.id}.apiKey"
                val raw = props.getProperty(key).orEmpty()
                if (raw.isNotEmpty() && !raw.startsWith(KEY_PREFIX)) {
                    props.setProperty(key, encodeKey(raw))
                }
            }
            file.parentFile?.mkdirs()
            file.outputStream().use { out ->
                props.store(out, "Blood Sugar AI config. Keys are stored locally only.")
            }
        } catch (e: Exception) {
            throw RuntimeException("无法保存 AI 配置: ${e.message}")
        }
    }

    /** 配置文件绝对路径（设置界面提示用） */
    fun filePath(): String = file.absolutePath
}
