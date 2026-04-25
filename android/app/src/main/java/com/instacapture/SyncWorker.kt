package com.instacapture

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * SyncWorker — фоновая синхронизация очереди через WorkManager.
 * Запускается периодически (каждые 15 минут) при наличии интернета.
 * Обрабатывает отложенные записи из SQLite-очереди.
 */
class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val TAG = "InstaCapture:SyncWorker"
    }

    override fun doWork(): Result {
        Log.i(TAG, "Запуск фоновой синхронизации...")

        return try {
            val networkManager = NetworkManager(applicationContext)
            networkManager.syncQueue()

            // Очистка старых записей (старше 30 дней)
            val queueManager = QueueManager(applicationContext)
            queueManager.cleanupOldRecords()

            Log.i(TAG, "Синхронизация завершена успешно")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при синхронизации: ${e.message}", e)
            // Повторим при следующем запуске
            Result.retry()
        }
    }
}