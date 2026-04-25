# Dockerfile — контейнеризация FastAPI-сервера
# Базовый образ: официальный Python 3.11 slim (минимальный размер)
FROM python:3.11-slim

# Метаданные
LABEL maintainer="InstaCapture"
LABEL description="InstaCapture Server — FastAPI + PostgreSQL + Telegram Bot"

# Переменные окружения
ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV APP_HOME=/app

# Рабочая директория
WORKDIR $APP_HOME

# Установка системных зависимостей (для psycopg2 и cryptography)
RUN apt-get update && apt-get install -y --no-install-recommends \
    gcc \
    libpq-dev \
    && rm -rf /var/lib/apt/lists/*

# Копирование зависимостей
COPY requirements.txt .

# Установка Python-зависимостей
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir -r requirements.txt

# Копирование исходного кода
COPY app/ ./app/

# Создание непривилегированного пользователя (безопасность)
RUN groupadd -r instacapture && useradd -r -g instacapture instacapture && \
    chown -R instacapture:instacapture $APP_HOME
USER instacapture

# Порт, который слушает приложение
EXPOSE 8000

# Команда запуска
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
