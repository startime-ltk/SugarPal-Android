package com.sugarpal.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite 建表：字段、类型、约束与 PC 端 H2（DatabaseConfig）完全一致。
 *
 * 时间统一以本地时区 "yyyy-MM-dd HH:mm:ss" 文本存储，不做 UTC 转换，
 * 以保证业务日归属跨端一致（H2 TIMESTAMP ↔ SQLite TEXT 语义对齐）。
 */
class SugarPalDbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS blood_sugar_records (
              id             INTEGER PRIMARY KEY AUTOINCREMENT,
              record_time    TEXT,
              blood_sugar    REAL,
              meal_time      TEXT,
              meal_period    TEXT,
              meal_type      TEXT,
              note           TEXT,
              insulin        REAL DEFAULT 0,
              carbs          REAL DEFAULT 0,
              activity       REAL DEFAULT 0,
              weight         REAL DEFAULT 0,
              pulse          REAL DEFAULT 0,
              blood_pressure TEXT,
              created_at     TEXT DEFAULT (datetime('now','localtime'))
            )
            """.trimIndent()
        )
        // 用餐时间表：同一业务日（凌晨4点边界）同一餐别只保留一条，重复保存覆盖旧值
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS meal_times (
              id            INTEGER PRIMARY KEY AUTOINCREMENT,
              business_date TEXT NOT NULL,
              meal_name     TEXT NOT NULL,
              meal_time     TEXT NOT NULL,
              created_at    TEXT DEFAULT (datetime('now','localtime')),
              CONSTRAINT uk_meal_business UNIQUE (business_date, meal_name)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 兼容从 PC 端迁移来的旧库：缺列则补（幂等），与 PC 端 migrateAddColumn 口径一致
        addColumnIfMissing(db, "blood_sugar_records", "insulin", "REAL DEFAULT 0")
        addColumnIfMissing(db, "blood_sugar_records", "carbs", "REAL DEFAULT 0")
        addColumnIfMissing(db, "blood_sugar_records", "activity", "REAL DEFAULT 0")
        addColumnIfMissing(db, "blood_sugar_records", "weight", "REAL DEFAULT 0")
        addColumnIfMissing(db, "blood_sugar_records", "pulse", "REAL DEFAULT 0")
        addColumnIfMissing(db, "blood_sugar_records", "blood_pressure", "TEXT")
    }

    /** 表缺列时才执行 ALTER TABLE，不丢已有数据 */
    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, typeDecl: String) {
        if (columnExists(db, table, column)) return
        runCatching { db.execSQL("ALTER TABLE $table ADD COLUMN $column $typeDecl") }
    }

    private fun columnExists(db: SQLiteDatabase, table: String, column: String): Boolean {
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (nameIdx >= 0 && column.equals(c.getString(nameIdx), ignoreCase = true)) return true
            }
        }
        return false
    }

    companion object {
        const val DB_NAME = "sugarpal.db"
        const val DB_VERSION = 1
    }
}
