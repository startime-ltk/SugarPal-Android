package com.sugarpal.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.bloodsugar.model.BloodSugarRecord
import com.bloodsugar.util.PeriodClassifier
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 数据访问层：语义与 PC 端 BloodSugarDAO 1:1 对齐。
 * 覆盖时间范围查询、去重判断、业务日聚合所需查询与用餐时间 UPSERT。
 */
class RecordDao(context: Context) {

    private val helper = SugarPalDbHelper(context.applicationContext)

    private val db: SQLiteDatabase get() = helper.writableDatabase

    private val table = "blood_sugar_records"

    // ---------------- 写入 ----------------

    fun insert(record: BloodSugarRecord): Long =
        db.insert(table, null, toValues(record))

    fun update(record: BloodSugarRecord): Int =
        db.update(table, toValues(record), "id = ?", arrayOf(record.id.toString()))

    fun delete(id: Int): Int =
        db.delete(table, "id = ?", arrayOf(id.toString()))

    /** 清空全部血糖记录（设置界面「清空数据」） */
    fun deleteAll(): Int = db.delete(table, null, null)

    /** 清空全部用餐时间 */
    fun deleteAllMealTimes(): Int = db.delete("meal_times", null, null)

    /** 去重判断：同一时间（精确到秒）+ 同一数值视为重复（与 PC 端 CsvImportService 口径一致） */
    fun countByTimeAndValue(recordTime: LocalDateTime?, bloodSugar: Double): Int {
        if (recordTime == null) return 0
        db.rawQuery(
            "SELECT COUNT(*) FROM $table WHERE record_time = ? AND blood_sugar = ?",
            arrayOf(TimeFmt.format(recordTime), bloodSugar.toString())
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** 同一时间（精确到秒）是否已有记录（录入前防重复提示用） */
    fun existsByTime(recordTime: LocalDateTime?, excludeId: Int = -1): Boolean {
        if (recordTime == null) return false
        db.rawQuery(
            "SELECT COUNT(*) FROM $table WHERE record_time = ? AND id <> ?",
            arrayOf(TimeFmt.format(recordTime), excludeId.toString())
        ).use { c -> return c.moveToFirst() && c.getInt(0) > 0 }
    }

    // ---------------- 查询 ----------------

    fun findAll(): List<BloodSugarRecord> =
        query("SELECT * FROM $table ORDER BY record_time DESC", null)

    /** 最近 n 条，按测量时间倒序（主界面默认展示近 8 条） */
    fun findLatest(limit: Int): List<BloodSugarRecord> =
        query("SELECT * FROM $table ORDER BY record_time DESC LIMIT ?", arrayOf(limit.toString()))

    /** 按时间区间查，区间为 [from, to) */
    fun findByDateRange(from: LocalDateTime, to: LocalDateTime): List<BloodSugarRecord> =
        query(
            "SELECT * FROM $table WHERE record_time >= ? AND record_time < ? ORDER BY record_time ASC",
            arrayOf(TimeFmt.format(from), TimeFmt.format(to))
        )

    /** 按业务日查询（凌晨 4 点边界，左闭右开） */
    fun findByBusinessDate(date: LocalDate): List<BloodSugarRecord> =
        findByDateRange(PeriodClassifier.getBusinessDayStart(date), PeriodClassifier.getBusinessDayEnd(date))

    /** 有记录的日期列表（业务日口径，凌晨 4 点前记的算前一天），倒序 */
    fun findDistinctDates(): List<String> {
        val sql = "SELECT DISTINCT date(datetime(record_time, '-4 hours')) AS d FROM $table ORDER BY d DESC"
        val dates = ArrayList<String>()
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                val d = c.getString(0)
                if (!d.isNullOrBlank()) dates.add(d)
            }
        }
        return dates
    }

    /** 某业务日当天、before 之前（含）最近一条记录的用餐时间，没有返回 null */
    fun findLatestMealTimeBefore(dayStart: LocalDateTime, before: LocalDateTime): LocalDateTime? {
        val sql = "SELECT MAX(meal_time) FROM $table WHERE meal_time IS NOT NULL AND meal_time >= ? AND meal_time <= ?"
        db.rawQuery(sql, arrayOf(TimeFmt.format(dayStart), TimeFmt.format(before))).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return TimeFmt.parse(c.getString(0))
        }
        return null
    }

    // ---------------- 用餐时间表 ----------------

    /** 保存/覆盖用餐时间：同一业务日同一餐别只保留最新一条（UNIQUE + REPLACE 覆盖语义） */
    fun upsertMealTime(businessDate: LocalDate, mealName: String, mealTime: LocalDateTime) {
        val cv = ContentValues().apply {
            put("business_date", TimeFmt.formatDate(businessDate))
            put("meal_name", mealName)
            put("meal_time", TimeFmt.format(mealTime))
        }
        db.insertWithOnConflict("meal_times", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** 某业务日已保存的全部用餐时间，返回 餐名 -> 用餐时间 */
    fun findMealTimesByBusinessDate(businessDate: LocalDate): LinkedHashMap<String, LocalDateTime> {
        val map = LinkedHashMap<String, LocalDateTime>()
        db.query(
            "meal_times", arrayOf("meal_name", "meal_time"), "business_date = ?", arrayOf(TimeFmt.formatDate(businessDate)),
            null, null, "meal_time ASC"
        ).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val t = TimeFmt.parse(c.getString(1)) ?: continue
                map[name] = t
            }
        }
        return map
    }

    /** 某业务日当天、before 之前（含）最近一条保存的用餐时间 */
    fun findLatestSavedMealTime(businessDate: LocalDate, before: LocalDateTime): LocalDateTime? {
        db.query(
            "meal_times", arrayOf("meal_time"), "business_date = ? AND meal_time <= ?",
            arrayOf(TimeFmt.formatDate(businessDate), TimeFmt.format(before)),
            null, null, "meal_time DESC", "1"
        ).use { c ->
            if (c.moveToFirst()) return TimeFmt.parse(c.getString(0))
        }
        return null
    }

    fun deleteMealTime(businessDate: LocalDate, mealName: String) {
        db.delete(
            "meal_times", "business_date = ? AND meal_name = ?",
            arrayOf(TimeFmt.formatDate(businessDate), mealName)
        )
    }

    // ---------------- 内部 ----------------

    private fun query(sql: String, args: Array<String>?): List<BloodSugarRecord> {
        val list = ArrayList<BloodSugarRecord>()
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) list.add(mapRow(c))
        }
        return list
    }

    private fun toValues(r: BloodSugarRecord): ContentValues = ContentValues().apply {
        // recordTime / mealTime 允许为空（未设置用餐时间时 mealTime 为 null），做空值安全处理
        put("record_time", r.recordTime?.let { TimeFmt.format(it) })
        put("blood_sugar", r.bloodSugar)
        put("meal_time", r.mealTime?.let { TimeFmt.format(it) })
        put("meal_period", r.mealPeriod)
        put("meal_type", r.mealType)
        put("note", r.note)
        put("insulin", r.insulin)
        put("carbs", r.carbs)
        put("activity", r.activity)
        put("weight", r.weight)
        put("pulse", r.pulse)
        put("blood_pressure", r.bloodPressure)
    }

    /** 结果集 → 实体，映射全部 14 列 */
    private fun mapRow(c: Cursor): BloodSugarRecord {
        val r = BloodSugarRecord()
        r.id = c.getInt(c.getColumnIndexOrThrow("id"))
        r.recordTime = TimeFmt.parse(c.getString(c.getColumnIndexOrThrow("record_time")))
        r.bloodSugar = c.getDouble(c.getColumnIndexOrThrow("blood_sugar"))
        r.mealTime = TimeFmt.parse(c.getString(c.getColumnIndexOrThrow("meal_time")))
        r.mealPeriod = c.getString(c.getColumnIndexOrThrow("meal_period"))
        r.mealType = c.getString(c.getColumnIndexOrThrow("meal_type"))
        r.note = c.getString(c.getColumnIndexOrThrow("note"))
        r.insulin = c.getDouble(c.getColumnIndexOrThrow("insulin"))
        r.carbs = c.getDouble(c.getColumnIndexOrThrow("carbs"))
        r.activity = c.getDouble(c.getColumnIndexOrThrow("activity"))
        r.weight = c.getDouble(c.getColumnIndexOrThrow("weight"))
        r.pulse = c.getDouble(c.getColumnIndexOrThrow("pulse"))
        r.bloodPressure = c.getString(c.getColumnIndexOrThrow("blood_pressure"))
        return r
    }

    fun close() {
        helper.close()
    }
}
