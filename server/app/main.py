"""
main.py — точка входа FastAPI-приложения.
Инициализирует БД, подключает роутеры, запускает Telegram-бота.
"""

import logging

from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api import router
from app.config import settings
from app.database import init_db
from app.telegram_bot import dp, bot

# Настройка логирования
logging.basicConfig(
    level=logging.INFO if not settings.DEBUG else logging.DEBUG,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    Lifespan-контекст: код, выполняемый при старте и остановке приложения.
    """
    # === Startup ===
    logger.info("Инициализация базы данных...")
    init_db()
    logger.info("База данных готова")

    # Запуск polling Telegram-бота (если токен задан)
    if bot and settings.TELEGRAM_BOT_TOKEN:
        import asyncio
        bot_task = asyncio.create_task(dp.start_polling(bot))
        logger.info("Telegram-бот запущен")
    else:
        bot_task = None
        logger.warning("Telegram-бот не настроен (отсутствует TELEGRAM_BOT_TOKEN)")

    yield

    # === Shutdown ===
    if bot_task:
        bot_task.cancel()
        logger.info("Telegram-бот остановлен")
    logger.info("Сервер завершает работу")


# Создание FastAPI-приложения
app = FastAPI(
    title="InstaCapture Server",
    description="Серверная часть для централизованного хранения данных Instagram-аккаунтов",
    version="1.0.0",
    lifespan=lifespan,
)

# CORS — в продакшене ограничьте origins!
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # TODO: Заменить на конкретный домен
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Подключение роутеров
app.include_router(router)


@app.get("/")
async def root():
    """Корневой эндпоинт — информация о сервисе."""
    return {
        "service": "InstaCapture Server",
        "version": "1.0.0",
        "endpoints": {
            "capture": "POST /api/capture",
            "health": "GET /api/health",
            "accounts": "GET /api/accounts",
        },
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "app.main:app",
        host=settings.HOST,
        port=settings.PORT,
        reload=settings.DEBUG,
    )
