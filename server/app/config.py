"""
config.py — централизованная конфигурация сервера через Pydantic Settings.
Все секреты загружаются из переменных окружения / файла .env.
НИКОГДА не хардкодьте ключи в коде.
"""

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # ================== БАЗА ДАННЫХ ==================
    # Формат: postgresql://user:password@host:port/dbname
    DATABASE_URL: str = "postgresql://instacapture:changeme@db:5432/instacapture"

    # ================== TELEGRAM ==================
    TELEGRAM_BOT_TOKEN: str = ""  # Получить у @BotFather
    ADMIN_CHAT_ID: int = 0        # ID чата администратора (только он имеет доступ)

    # ================== ШИФРОВАНИЕ ==================
    # Ключ для Fernet (AES-128-CBC + HMAC). Генерация:
    # python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
    SERVER_ENCRYPTION_KEY: str = ""

    # ================== API ==================
    # Ключ для авторизации запросов от Android-приложения
    API_KEY: str = ""

    # ================== СЕРВЕР ==================
    HOST: str = "0.0.0.0"
    PORT: int = 8000
    DEBUG: bool = False

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"
        extra = "ignore"  # Игнорировать лишние переменные окружения


# Singleton-экземпляр настроек
settings = Settings()
