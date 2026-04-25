"""
database.py — управление подключением к PostgreSQL и сессиями.
Использует SQLAlchemy с пулом соединений.
"""

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.config import settings
from app.models import Base

# Создание движка с пулом соединений
# pool_pre_ping=True — проверяет соединение перед использованием (защита от stale connections)
engine = create_engine(
    settings.DATABASE_URL,
    pool_pre_ping=True,
    pool_size=10,
    max_overflow=20,
)

# Фабрика сессий
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


def init_db():
    """
    Инициализация базы данных — создание таблиц.
    Вызывается при старте приложения.
    """
    Base.metadata.create_all(bind=engine)


def get_db():
    """
    Генератор зависимостей FastAPI для получения сессии БД.
    Автоматически закрывает сессию после запроса.
    """
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
