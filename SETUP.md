# InstaCapture — Пошаговая инструкция по настройке

> Этот документ предназначен для нетехнических пользователей. Следуйте шагам строго по порядку.

---

## Шаг 1: Купить VPS (виртуальный сервер)

Рекомендуемые провайдеры:

| Провайдер | Минимальный тариф | Цена/мес |
|-----------|------------------|----------|
| Hetzner | CX21 (2 vCPU, 4 GB RAM) | ~€6 |
| DigitalOcean | Basic Droplet (2 GB RAM) | ~$6 |
| Vultr | Cloud Compute (1 vCPU, 1 GB) | ~$5 |

**Требования к серверу:**
- ОС: Ubuntu 22.04 LTS
- RAM: минимум 1 GB (рекомендуется 2 GB+)
- Диск: 20 GB SSD
- Публичный IP-адрес

**После покупки:**
- Сохраните IP-адрес сервера (например, `123.45.67.89`)
- Получите root-доступ (пароль или SSH-ключ)

---

## Шаг 2: Подключиться к серверу

### Windows
1. Скачайте **PuTTY** (https://www.putty.org/)
2. В поле **Host Name** введите IP вашего сервера
3. Нажмите **Open**, введите логин `root` и пароль

### Mac / Linux
Откройте Терминал и выполните:
```bash
ssh root@123.45.67.89
# Введите пароль при запросе
```

---

## Шаг 3: Установить Docker

На сервере выполните по очереди:

```bash
# Обновление системы
apt update && apt upgrade -y

# Установка Docker
curl -fsSL https://get.docker.com -o get-docker.sh
sh get-docker.sh

# Установка Docker Compose
apt install docker-compose-plugin -y

# Проверка
docker --version
docker compose version
```

Если обе команды показывают версии — Docker установлен.

---

## Шаг 4: Скопировать файлы сервера

### Вариант A: Через Git
```bash
# На сервере:
apt install git -y
git clone https://github.com/yourusername/instagram-capture-suite.git
cd instagram-capture-suite/server
```

### Вариант B: Через SFTP (FileZilla)
1. Скачайте **FileZilla** (https://filezilla-project.org/)
2. Подключитесь: `sftp://root@123.45.67.89`
3. Создайте папку `/root/instacapture/`
4. Загрузите содержимое папки `server/` из этого репозитория

---

## Шаг 5: Заполнить .env

```bash
cd instagram-capture-suite/server  # или куда скопировали файлы
cp .env.example .env
nano .env
```

Откроется редактор. Заполните поля:

```env
# База данных (можно оставить как есть)
DATABASE_URL=postgresql://instacapture:changeme_strong_password@db:5432/instacapture

# Telegram — ОБЯЗАТЕЛЬНО заполнить
TELEGRAM_BOT_TOKEN=123456789:ABCdefGHIjklMNOpqrsTUVwxyz
ADMIN_CHAT_ID=123456789

# Шифрование — ОБЯЗАТЕЛЬНО сгенерировать
SERVER_ENCRYPTION_KEY=ваш_ключ_здесь

# API-ключ для Android-приложения — придумайте длинный пароль
API_KEY=мой_секретный_ключ_для_приложения_2024
```

### Как получить TELEGRAM_BOT_TOKEN:
1. Откройте Telegram, найдите **@BotFather**
2. Отправьте: `/newbot`
3. Следуйте инструкциям, получите токен вида `123456789:ABCdef...`
4. Скопируйте в `.env`

### Как получить ADMIN_CHAT_ID:
1. Найдите **@userinfobot** в Telegram
2. Нажмите **Start**, бот покажет ваш ID
3. Скопируйте число в `.env`

### Как сгенерировать SERVER_ENCRYPTION_KEY:
```bash
python3 -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
```
Скопируйте вывод (длинная строка) в `.env`.

Сохраните файл: `Ctrl+O`, `Enter`, `Ctrl+X`.

---

## Шаг 6: Запустить сервер

```bash
cd /root/instagram-capture-suite/server  # укажите правильный путь
docker compose up -d
```

Проверка:
```bash
# Показать запущенные контейнеры
docker ps

# Должно быть 2 контейнера: instacapture_db и instacapture_app

# Проверить логи
docker logs instacapture_app

# Должно быть: "Application startup complete"
```

**Сервер запущен!** API доступен на порту 8000.

---

## Шаг 7: Настроить домен + SSL (Let's Encrypt)

### Если у вас есть домен:

```bash
# Установить certbot
apt install certbot -y

# Получить сертификат
sudo certbot certonly --standalone -d your-domain.com
```

### Установить Nginx (reverse proxy):
```bash
apt install nginx -y
```

Создайте конфиг:
```bash
nano /etc/nginx/sites-available/instacapture
```

Вставьте:
```nginx
server {
    listen 80;
    server_name your-domain.com;
    return 301 https://$server_name$request_uri;
}

server {
    listen 443 ssl http2;
    server_name your-domain.com;

    ssl_certificate /etc/letsencrypt/live/your-domain.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/your-domain.com/privkey.pem;

    location / {
        proxy_pass http://localhost:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

Активируйте:
```bash
ln -s /etc/nginx/sites-available/instacapture /etc/nginx/sites-enabled/
nginx -t
systemctl restart nginx
```

Теперь сервер доступен по `https://your-domain.com`

---

## Шаг 8: Настроить Android-приложение

1. Откройте проект в Android Studio
2. Откройте файл:
   `app/src/main/java/com/instacapture/Config.kt`

3. Замените значения:
```kotlin
const val SERVER_URL = "https://your-domain.com"  // ваш домен или IP
const val API_KEY = "мой_секретный_ключ_для_приложения_2024"  // тот же, что в .env
```

4. Получите SHA-256 публичного ключа вашего SSL-сертификата:
```bash
echo | openssl s_client -connect your-domain.com:443 2>/dev/null | \
  openssl x509 -pubkey -noout | \
  openssl pkey -pubin -outform der | \
  openssl dgst -sha256 -binary | \
  openssl enc -base64
```

Замените:
```kotlin
const val PINNED_CERTIFICATE_HASH = "sha256/ВАШ_ХЕШ_ЗДЕСЬ="
```

---

## Шаг 9: Собрать APK через GitHub Actions

1. Загрузите проект на GitHub
2. Перейдите во вкладку **Actions**
3. Выберите **Build APK**
4. Нажмите **Run workflow**
5. Через ~5 минут APK будет готов в разделе **Artifacts**

Или соберите локально:
```bash
cd android
./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release-unsigned.apk
```

---

## Шаг 10: Установить на телефон

### Вариант A: Android Studio + USB
1. Включите **Режим разработчика** на телефоне
2. Подключите USB, разрешите отладку
3. Android Studio → **Run** → выберите устройство

### Вариант B: APK-файл
```bash
adb install app-release-unsigned.apk
```

### После установки:
1. Откройте InstaCapture
2. Нажмите **"Открыть настройки Accessibility"**
3. Включите сервис InstaCapture
4. Нажмите **"Тестовое соединение"** — должно показать "Сервер доступен"

---

## Проверка работы

1. Откройте **Instagram**
2. Начните регистрацию нового аккаунта
3. Введите тестовые данные
4. Проверьте Telegram — должно прийти уведомление `/get username`

---

## Частые проблемы

| Проблема | Решение |
|----------|---------|
| "Сервер недоступен" | Проверьте firewall: `ufw allow 8000` |
| Ошибка SSL | Проверьте дату на телефоне и сервере |
| Бот не отвечает | Проверьте TELEGRAM_BOT_TOKEN в .env |
| Данные не приходят | Проверьте логи: `docker logs instacapture_app` |

---

## Обновление сервера

```bash
cd /root/instagram-capture-suite/server
git pull  # если через git
docker compose down
docker compose up -d --build
```

## Бэкап базы данных

```bash
# Создание бэкапа
docker exec instacapture_db pg_dump -U instacapture instacapture > backup.sql

# Восстановление
cat backup.sql | docker exec -i instacapture_db psql -U instacapture instacapture
```

---

**Готово!** Теперь вы можете управлять Instagram-аккаунтами через Telegram-бот.
