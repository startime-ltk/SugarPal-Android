package com.sugarpal.app.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 与 PC 端一致的本地时间存储格式（无 UTC 转换，秒级精度） */
object TimeFmt {

    private val DT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val D: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun format(dt: LocalDateTime): String = dt.format(DT)

    fun formatDate(d: LocalDate): String = d.format(D)

    /** 兼容空格格式与 ISO(T) 格式，解析失败返回 null */
    fun parse(text: String?): LocalDateTime? {
        if (text.isNullOrBlank()) return null
        return runCatching { LocalDateTime.parse(text, DT) }
            .recoverCatching { LocalDateTime.parse(text.replace(' ', 'T')) }
            .getOrNull()
    }

    fun parseDate(text: String?): LocalDate? {
        if (text.isNullOrBlank()) return null
        return runCatching { LocalDate.parse(text, D) }.getOrNull()
    }

    /** 界面展示用：MM-dd HH:mm */
    fun display(dt: LocalDateTime?): String =
        dt?.format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) ?: ""

    /** 界面展示用：HH:mm */
    fun hm(dt: LocalDateTime?): String =
        dt?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: ""

    /** 界面展示用：yyyy-MM-dd HH:mm */
    fun full(dt: LocalDateTime?): String =
        dt?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) ?: ""
}
