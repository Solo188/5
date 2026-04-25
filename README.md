# InstaCapture Suite

Система автоматического захвата и централизованного хранения данных корпоративных Instagram-аккаунтов.

## Описание

InstaCapture решает проблему управления ~15 корпоративными Instagram-аккаунтами с одного устройства. Приложение автоматически перехватывает данные (email, username, пароль) в момент регистрации, шифрует их и отправляет на ваш защищённый сервер. Управление аккаунтами осуществляется через Telegram-бот.

**Важное ограничение**: Приложение НЕ обходит защиту Instagram. Оно использует Accessibility Service для чтения данных, которые пользователь сам вводит в поля формы регистрации.

## Архитектура

```
+------------------+          HTTPS + TLS 1.3          +------------------+
|                  |  ==============================>  |                  |
|   Android App    |       Certificate Pinning         |   FastAPI Server |
|  (Kotlin + AES)  |  <==============================  |  (Python + Fernet|
|                  |                                   |   Encryption)    |
+------------------+                                   +------------------+
       |                                                       |
       | Accessibility Service                                 | PostgreSQL
       v                                                       v
+------------------+                                   +------------------+
|   Instagram App  |                                   |  Telegram Bot   |
|  (React Native)  |                                   |  (aiogram 3.x)  |
+------------------+                                   +------------------+
```

**Компоненты:**
- **Android** — Accessibility Service, AES-256-GCM шифрование (Keystore), offline-очередь SQLite
- **Server** — FastAPI, Fernet шифрование, PostgreSQL, уведомления в Telegram
- **Telegram Bot** — управление аккаунтами (/list, /get, /delete, /search)

## Установка Android-приложения

### Через GitHub Actions (рекомендуется)

1. Форкните этот репозиторий
2. Перейдите в **Actions** → **Build APK** → **Run workflow**
3. Скачайте собранный APK из артефактов

### Локальная сборка

```bash
# Клонирование
git clone https://github.com/yourusername/instagram-capture-suite.git
cd instagram-capture-suite/android

# Настройка Config.kt
# Откройте app/src/main/java/com/instacapture/Config.kt
# Замените:
#   SERVER_URL = "https://your-vps.com"
#   API_KEY = "YOUR_API_KEY_HERE"
#   PINNED_CERTIFICATE_HASH = "sha256/..." (получить: openssl s_client -connect your-vps.com:443 ...)

# Сборка
./gradlew assembleRelease

# APK будет в:
# app/build/outputs/apk/release/app-release-unsigned.apk
```

### Установка APK

```bash
adb install app/build/outputs/apk/release/app-release-unsigned.apk
```

### Включение Accessibility Service

1. Откройте приложение InstaCapture
2. Нажмите **"Открыть настройки Accessibility"**
3. Найдите **InstaCapture** в списке → Включите
4. Подтвердите предупреждение системы

## Деплой сервера

### Требования

- VPS (рекомендуется: Hetzner CX21, DigitalOcean Droplet 2GB+)
- Docker + Docker Compose
- Домен (опционально, для HTTPS)

### Настройка .env

```bash
cd server
cp .env.example .env
# Отредактируйте .env:
#   DATABASE_URL=postgresql://...
#   TELEGRAM_BOT_TOKEN=... (от @BotFather)
#   ADMIN_CHAT_ID=... (ваш chat_id)
#   SERVER_ENCRYPTION_KEY=... (python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())")
#   API_KEY=... (случайная строка 32+ символов)
```

### Запуск

```bash
cd server
docker-compose up -d

# Проверка
curl http://localhost:8000/api/health
```

### Настройка домена + SSL (Let's Encrypt)

```bash
# Установите certbot
sudo apt install certbot

# Получите сертификат
sudo certbot certonly --standalone -d your-vps.com

# Настройте reverse-proxy (nginx/traefik) на порт 8000
# Или используйте Let's Encrypt вместе с Traefik:
# docker-compose с Traefik: см. SETUP.md
```

## Настройка Telegram-бота

1. Напишите **@BotFather** в Telegram → `/newbot`
2. Получите токен, запишите в `.env` (`TELEGRAM_BOT_TOKEN`)
3. Узнайте свой chat_id через **@userinfobot**
4. Запишите в `.env` (`ADMIN_CHAT_ID`)
5. Перезапустите сервер: `docker-compose restart`

**Команды бота:**
- `/start` — приветствие
- `/list` — список аккаунтов
- `/get {username}` — полные данные с паролем
- `/delete {id}` — удалить аккаунт
- `/search {query}` — поиск
- `/categories` — статистика по категориям

## Использование

### Регистрация нового аккаунта

1. Убедитесь, что InstaCapture включён в Accessibility
2. Откройте **Instagram**
3. Выйдите из текущего аккаунта (кнопка **"Автовыход"** в InstaCapture или вручную)
4. Начните регистрацию нового аккаунта
5. Введите email, имя, username, пароль
6. InstaCapture автоматически захватит данные
7. Проверьте уведомление в Telegram

### Управление через бота

```
/list                — показать все аккаунты
/get corporate_1     — показать пароль аккаунта
/delete 42           — удалить аккаунт с ID 42
/search @company     — найти по username
```

## Безопасность

| Компонент | Механизм |
|-----------|----------|
| Android | AES-256-GCM, ключ в Android Keystore |
| Передача | HTTPS + TLS 1.3 + Certificate Pinning |
| Сервер | Fernet (AES-128-CBC + HMAC), ключ в env |
| База данных | Поля email/phone/password — зашифрованы |
| API | Ключ авторизации (X-API-Key) |
| Telegram | Доступ только по ADMIN_CHAT_ID |

**Рекомендации по VPS:**
- Используйте SSH-ключи (отключите парольную аутентификацию)
- Настройте UFW firewall (только 22, 80, 443)
- Включите автоматические обновления безопасности
- Делайте бэкапы PostgreSQL

## Ограничения

- **Нет гарантированной очистки данных Instagram** — автоматический logout не удаляет кэш и сохранённые пароли. Для 100% гарантии очистите данные в системных настройках телефона.
- **Зависимость от layout Instagram** — React Native UI может измениться, что потребует обновления accessibility-логики.
- **Требуется Accessibility Service** — без него приложение не работает.
- **Только Android 11+** (API 30+)

## Лицензия

Этот проект предназначен для управления собственными корпоративными аккаунтами. Используйте ответственно и в соответствии с законодательством.
# 2
