package com.instacapture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * MainActivity — главный экран приложения.
 * Обязанности:
 * 1. Проверка и перенаправление в настройки Accessibility (при первом запуске)
 * 2. Отображение инструкции по использованию
 * 3. Индикатор статуса сервиса
 * 4. Кнопка тестового соединения с сервером
 * 5. Список захваченных аккаунтов (из локальной очереди/БД)
 * 6. Кнопка ручного logout из Instagram
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "InstaCapture:Main"
        private const val REQUEST_ACCESSIBILITY = 1001
    }

    private lateinit var tvStatus: MaterialTextView
    private lateinit var tvInstructions: MaterialTextView
    private lateinit var btnTestConnection: MaterialButton
    private lateinit var btnOpenAccessibilitySettings: MaterialButton
    private lateinit var btnLogoutInstagram: MaterialButton
    private lateinit var rvAccounts: RecyclerView
    private lateinit var tvQueueSize: MaterialTextView
    private lateinit var networkManager: NetworkManager
    private lateinit var queueManager: QueueManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация менеджеров
        networkManager = NetworkManager(this)
        queueManager = QueueManager(this)

        // Инициализация UI
        initViews()
        updateServiceStatus()
        updateQueueSize()
        setupPeriodicSync()

        // Если сервис не активен — показываем диалог с предложением включить
        if (!isAccessibilityServiceEnabled()) {
            showEnableAccessibilityDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        updateQueueSize()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvInstructions = findViewById(R.id.tvInstructions)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        btnOpenAccessibilitySettings = findViewById(R.id.btnOpenAccessibilitySettings)
        btnLogoutInstagram = findViewById(R.id.btnLogoutInstagram)
        rvAccounts = findViewById(R.id.rvAccounts)
        tvQueueSize = findViewById(R.id.tvQueueSize)

        tvInstructions.text = buildInstructions()

        btnTestConnection.setOnClickListener {
            testServerConnection()
        }

        btnOpenAccessibilitySettings.setOnClickListener {
            openAccessibilitySettings()
        }

        btnLogoutInstagram.setOnClickListener {
            showLogoutWarning()
        }

        // Настройка RecyclerView
        rvAccounts.layoutManager = LinearLayoutManager(this)
        // TODO: Подключить адаптер при реализации UI-списка
        // rvAccounts.adapter = AccountsAdapter(loadMockAccounts())
    }

    /**
     * Проверяет, включён ли InstaCapture в системных настройках Accessibility.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(packageName)
    }

    private fun updateServiceStatus() {
        val enabled = isAccessibilityServiceEnabled()
        tvStatus.text = if (enabled) {
            "Статус: АКТИВЕН (сервис запущен)"
        } else {
            "Статус: НЕ АКТИВЕН — требуется включение в настройках"
        }
        tvStatus.setTextColor(
            if (enabled) getColor(android.R.color.holo_green_dark)
            else getColor(android.R.color.holo_red_dark)
        )
    }

    private fun updateQueueSize() {
        val size = queueManager.getQueueSize()
        tvQueueSize.text = "Записей в очереди: $size"
    }

    /**
     * Показать диалог с предложением включить Accessibility Service.
     */
    private fun showEnableAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle("Требуется Accessibility Service")
            .setMessage("Для работы InstaCapture необходимо включить сервис доступности. " +
                    "Это позволит приложению перехватывать данные из Instagram.")
            .setPositiveButton("Открыть настройки") { _, _ ->
                openAccessibilitySettings()
            }
            .setNegativeButton("Позже") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, "Без Accessibility Service приложение не работает", Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivityForResult(intent, REQUEST_ACCESSIBILITY)
    }

    /**
     * Тестовое соединение с сервером — для проверки конфигурации.
     */
    private fun testServerConnection() {
        btnTestConnection.isEnabled = false
        btnTestConnection.text = "Проверка..."

        networkManager.testConnection { success, message ->
            runOnUiThread {
                btnTestConnection.isEnabled = true
                btnTestConnection.text = "Тестовое соединение с сервером"

                AlertDialog.Builder(this)
                    .setTitle(if (success) "Успех" else "Ошибка")
                    .setMessage(message)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    /**
     * Предупреждение перед автоматическим logout.
     */
    private fun showLogoutWarning() {
        AlertDialog.Builder(this)
            .setTitle("Автоматический выход из Instagram")
            .setMessage(
                "Приложение попытается нажать кнопки 'Выйти' в Instagram автоматически.\n\n" +
                        "ВАЖНО: Это НЕ гарантирует полную очистку данных (кэш, сохранённые пароли). " +
                        "Для 100%% гарантии очистите данные Instagram вручную:\n" +
                        "Настройки → Приложения → Instagram → Хранилище → Очистить данные"
            )
            .setPositiveButton("Продолжить") { _, _ ->
                launchInstagramForLogout()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun launchInstagramForLogout() {
        val intent = packageManager.getLaunchIntentForPackage("com.instagram.android")
        if (intent != null) {
            startActivity(intent)
            Toast.makeText(this, "Откройте Instagram — автоматический logout начнётся", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Instagram не установлен", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Настройка фоновой синхронизации очереди через WorkManager.
     * Запускается каждые 15 минут при наличии сети.
     */
    private fun setupPeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncWork = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "instacapture_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncWork
        )
    }

    /**
     * Формирование инструкции для пользователя.
     */
    private fun buildInstructions(): String {
        return """
            Инструкция по использованию:
            
            1. Убедитесь, что сервис InstaCapture включён в настройках Accessibility
            
            2. Откройте Instagram
            
            3. Выйдите из текущего аккаунта (кнопка "Выйти" в приложении)
               или используйте кнопку "Автовыход" ниже
            
            4. Начните регистрацию нового аккаунта:
               - Введите email/телефон
               - Введите имя и пароль
               
            5. InstaCapture автоматически захватит данные и отправит их на сервер
            
            6. Проверьте получение уведомления в Telegram
            
            Ограничения:
            • Нет гарантированной очистки данных Instagram (только logout)
            • Зависимость от layout Instagram (может измениться)
            • Требуется Accessibility Service
        """.trimIndent()
    }

    // ==================== ВРЕМЕННЫЙ МОК ДЛЯ UI ====================
    // TODO: Реализовать полноценный адаптер и загрузку из БД
    private fun loadMockAccounts(): List<AccountListItem> {
        return listOf(
            AccountListItem("corporate_1", "corp1@company.com", null, formatTime(System.currentTimeMillis())),
            AccountListItem("corporate_2", null, "+79990001122", formatTime(System.currentTimeMillis() - 86400000))
        )
    }

    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
