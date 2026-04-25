package com.instacapture

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * QueueManager — локальная SQLite очередь для офлайн-режима.
 * Критически важен для graceful degradation: если сервер недоступен,
 * данные не теряются, а накапливаются и отправляются при появлении сети.
 */
class QueueManager(context: Context) : SQLiteOpenHelper(
    context,
    Config.DB_NAME,
    null,
    Config.DB_VERSION
) {
    companion object {
        private const val TAG = "InstaCapture:Queue"
        
        const val TABLE_QUEUE = "queue"
        const val COL_ID = "id"
        const val COL_PAYLOAD = "encrypted_payload"
        const val COL_CREATED_AT = "created_at"
        const val COL_RETRY_COUNT = "retry_count"
        const val COL_LAST_ERROR = "last_error"
        
        // Максимальное число попыток перед удалением записи
        const val MAX_RETRIES = 10
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Создание таблицы очереди
        val createTable = """
            CREATE TABLE IF NOT EXISTS $TABLE_QUEUE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_PAYLOAD TEXT NOT NULL,
                $COL_CREATED_AT INTEGER DEFAULT (strftime('%s','now') * 1000),
                $COL_RETRY_COUNT INTEGER DEFAULT 0,
                $COL_LAST_ERROR TEXT
            )
        """.trimIndent()
        db.execSQL(createTable)
        Log.i(TAG, "Таблица очереди создана")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // При изменении схемы — пересоздаём таблицу (данные теряются, но это допустимо для очереди)
        db.execSQL("DROP TABLE IF EXISTS $TABLE_QUEUE")
        onCreate(db)
    }

    /**
     * Добавить зашифрованный payload в очередь.
     * Вызывается, когда NetworkManager получает ошибку сети.
     */
    fun enqueue(encryptedPayload: String): Long {
        val values = ContentValues().apply {
            put(COL_PAYLOAD, encryptedPayload)
            put(COL_CREATED_AT, System.currentTimeMillis())
            put(COL_RETRY_COUNT, 0)
        }
        val id = writableDatabase.insert(TABLE_QUEUE, null, values)
        if (Config.DEBUG_MODE) Log.d(TAG, "Запись добавлена в очередь, id=$id")
        return id
    }

    /**
     * Получить все записи из очереди (от самых старых к новым)
     */
    fun getAllPending(): List<QueueEntry> {
        val entries = mutableListOf<QueueEntry>()
        val cursor = readableDatabase.query(
            TABLE_QUEUE,
            null,
            "$COL_RETRY_COUNT < ?",
            arrayOf(MAX_RETRIES.toString()),
            null,
            null,
            "$COL_CREATED_AT ASC"
        )
        
        cursor.use {
            while (it.moveToNext()) {
                entries.add(
                    QueueEntry(
                        id = it.getLong(it.getColumnIndexOrThrow(COL_ID)),
                        encryptedPayload = it.getString(it.getColumnIndexOrThrow(COL_PAYLOAD)),
                        createdAt = it.getLong(it.getColumnIndexOrThrow(COL_CREATED_AT)),
                        retryCount = it.getInt(it.getColumnIndexOrThrow(COL_RETRY_COUNT)),
                        lastError = it.getString(it.getColumnIndexOrThrow(COL_LAST_ERROR))
                    )
                )
            }
        }
        return entries
    }

    /**
     * Удалить запись после успешной отправки
     */
    fun remove(id: Long) {
        writableDatabase.delete(TABLE_QUEUE, "$COL_ID = ?", arrayOf(id.toString()))
        if (Config.DEBUG_MODE) Log.d(TAG, "Запись $id удалена из очереди")
    }

    /**
     * Инкрементировать счётчик попыток и сохранить ошибку.
     * Вызывается при неудачной отправке.
     */
    fun incrementRetry(id: Long, error: String) {
        val values = ContentValues().apply {
            put(COL_RETRY_COUNT, getRetryCount(id) + 1)
            put(COL_LAST_ERROR, error)
        }
        writableDatabase.update(TABLE_QUEUE, values, "$COL_ID = ?", arrayOf(id.toString()))
        if (Config.DEBUG_MODE) Log.d(TAG, "Retry увеличен для записи $id, ошибка: $error")
    }

    /**
     * Очистить старые записи (старше 30 дней) — защита от разрастания БД
     */
    fun cleanupOldRecords(daysToKeep: Int = 30) {
        val cutoff = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)
        val deleted = writableDatabase.delete(
            TABLE_QUEUE,
            "$COL_CREATED_AT < ?",
            arrayOf(cutoff.toString())
        )
        Log.i(TAG, "Очищено $deleted старых записей")
    }

    private fun getRetryCount(id: Long): Int {
        val cursor = readableDatabase.query(
            TABLE_QUEUE,
            arrayOf(COL_RETRY_COUNT),
            "$COL_ID = ?",
            arrayOf(id.toString()),
            null, null, null
        )
        cursor.use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    /**
     * Количество записей в очереди (для UI — индикатор)
     */
    fun getQueueSize(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_QUEUE", null)
        cursor.use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }
}
