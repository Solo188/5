package com.instacapture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * LogoutAutomator — автоматический выход из Instagram через Accessibility API.
 * Проблема: без root невозможно полностью очистить данные приложения.
 * Решение: автоматизировать UI-шаги выхода из аккаунта.
 *
 * ВНИМАНИЕ: Этот метод НЕ гарантирует полную очистку (кэш, сохранённые логины
 * могут остаться). Для гарантии рекомендуется вручную очистить данные Instagram
 * в системных настройках (Настройки → Приложения → Instagram → Хранилище → Очистить).
 */
object LogoutAutomator {

    private const val TAG = "InstaCapture:Logout"

    /**
     * Выполняет полный цикл выхода из Instagram.
     * @return true если все шаги выполнены успешно, false если что-то пошло не так
     */
    fun performLogout(service: AccessibilityService): Boolean {
        Log.i(TAG, "Начало автоматического logout...")

        val rootNode = service.rootInActiveWindow ?: run {
            Log.e(TAG, "rootInActiveWindow == null — Instagram не на экране?")
            return false
        }

        try {
            // Шаг 1: Открыть профиль (нижняя правая иконка)
            val profileTab = findNodeByContentDescription(rootNode, "Profile", "Профиль")
                ?: findNodeByText(rootNode, "Profile", "Профиль")
                ?: findNodeByResourceId(rootNode, "tab_bar_profile", "profile_tab")

            if (profileTab == null) {
                Log.w(TAG, "Не найдена вкладка профиля — возможно уже в профиле")
            } else {
                clickNode(service, profileTab, "Профиль")
                // Даём UI отрисоваться
                Thread.sleep(600)
            }

            // Обновляем root после клика
            val updatedRoot = service.rootInActiveWindow ?: rootNode

            // Шаг 2: Открыть меню (три полоски или шестерёнка)
            val menuButton = findNodeByContentDescription(updatedRoot, "Menu", "Меню", "Настройки")
                ?: findNodeByResourceId(updatedRoot, "menu", "hamburger", "options")
                ?: findNodeByText(updatedRoot, "≡", "Menu", "Меню")

            if (menuButton != null) {
                clickNode(service, menuButton, "Меню")
                Thread.sleep(800)
            } else {
                Log.w(TAG, "Кнопка меню не найдена — пробуем найти Settings напрямую")
            }

            val menuRoot = service.rootInActiveWindow ?: updatedRoot

            // Шаг 3: Найти "Settings" / "Настройки" / "Settings and privacy"
            val settings = findNodeByText(menuRoot, "Settings", "Настройки", "Settings and privacy", "Настройки и конфиденциальность")
                ?: findNodeByContentDescription(menuRoot, "Settings", "Настройки")

            if (settings != null) {
                clickNode(service, settings, "Настройки")
                Thread.sleep(800)
            } else {
                Log.e(TAG, "Не найден пункт Настройки")
                return false
            }

            val settingsRoot = service.rootInActiveWindow ?: menuRoot

            // Шаг 4: Прокрутить вниз и найти "Log Out"
            // Instagram использует RecyclerView — логирует прокруткой
            var logoutFound = false
            var scrollAttempts = 0
            val maxScrolls = 8

            while (!logoutFound && scrollAttempts < maxScrolls) {
                val currentRoot = service.rootInActiveWindow ?: settingsRoot
                val logout = findNodeByText(currentRoot, "Log Out", "Выйти", "Logout", "Log out")

                if (logout != null) {
                    logoutFound = true
                    clickNode(service, logout, "Выйти")
                    Thread.sleep(600)
                    break
                }

                // Прокрутка вниз
                val scrollable = findScrollableNode(currentRoot)
                if (scrollable != null) {
                    scrollDown(service, scrollable)
                    Thread.sleep(600)
                    scrollAttempts++
                } else {
                    Log.w(TAG, "Нет прокручиваемого контейнера")
                    break
                }
            }

            if (!logoutFound) {
                Log.e(TAG, "Кнопка 'Выйти' не найдена после $maxScrolls прокруток")
                return false
            }

            // Шаг 5: Подтвердить выход (если появился диалог)
            val confirmRoot = service.rootInActiveWindow
            val confirm = findNodeByText(confirmRoot, "Log Out", "Выйти", "Log out", "OK", "Yes")
            if (confirm != null) {
                clickNode(service, confirm, "Подтверждение выхода")
                Thread.sleep(500)
            }

            Log.i(TAG, "Logout выполнен успешно")
            Toast.makeText(service, "Аккаунт выведен — можно регистрировать новый", Toast.LENGTH_LONG).show()
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при logout: ${e.message}", e)
            return false
        } finally {
            // Обязательно recycle для предотвращения утечек
            rootNode.recycle()
        }
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================

    /**
     * Поиск узла по contentDescription (варианты для разных локализаций)
     */
    private fun findNodeByContentDescription(root: AccessibilityNodeInfo?, vararg labels: String): AccessibilityNodeInfo? {
        if (root == null) return null
        for (label in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(label)
            if (nodes.isNotEmpty()) {
                val match = nodes.firstOrNull { it.contentDescription?.toString()?.contains(label, ignoreCase = true) == true }
                if (match != null) return match
            }
        }
        return null
    }

    /**
     * Поиск по видимому тексту (прямое совпадение или содержание)
     */
    private fun findNodeByText(root: AccessibilityNodeInfo?, vararg texts: String): AccessibilityNodeInfo? {
        if (root == null) return null
        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (nodes.isNotEmpty()) return nodes[0]
        }
        return null
    }

    /**
     * Поиск по resource-id (через getViewIdResourceName).
     * Instagram на React Native — id нестабильны, поэтому это fallback.
     */
    private fun findNodeByResourceId(root: AccessibilityNodeInfo?, vararg ids: String): AccessibilityNodeInfo? {
        if (root == null) return null
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId("com.instagram.android:id/$id")
            if (nodes.isNotEmpty()) return nodes[0]
        }
        return null
    }

    private fun findScrollableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isScrollable) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            val found = findScrollableNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun clickNode(service: AccessibilityService, node: AccessibilityNodeInfo, label: String) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val x = rect.centerX().toFloat()
        val y = rect.centerY().toFloat()

        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        service.dispatchGesture(gesture, null, null)
        Log.i(TAG, "Клик по '$label' ($x, $y)")
    }

    private fun scrollDown(service: AccessibilityService, node: AccessibilityNodeInfo) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val x = rect.centerX().toFloat()
        val fromY = rect.bottom * 0.7f
        val toY = rect.top * 0.3f

        val path = Path().apply {
            moveTo(x, fromY)
            lineTo(x, toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()

        service.dispatchGesture(gesture, null, null)
        Log.d(TAG, "Прокрутка вниз")
    }
}
