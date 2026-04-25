"""
crypto.py — криптографические операции сервера.
Алгоритм: Fernet (AES-128-CBC + HMAC-SHA256 под капотом).
Все чувствительные данные шифруются перед записью в БД.
"""

import base64
from cryptography.fernet import Fernet
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

from app.config import settings


class ServerCrypto:
    """
    Управление шифрованием данных на сервере.
    Ключ генерируется из SERVER_ENCRYPTION_KEY через PBKDF2.
    """

    def __init__(self):
        if not settings.SERVER_ENCRYPTION_KEY:
            raise ValueError(
                "SERVER_ENCRYPTION_KEY не задан! "
                "Сгенерируйте ключ: python -c \"from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())\""
            )
        self._init_fernet()

    def _init_fernet(self):
        """
        Инициализация Fernet из строкового ключа.
        Если ключ не 32-байт base64 — производим PBKDF2 деривацию.
        """
        key = settings.SERVER_ENCRYPTION_KEY.strip()
        try:
            # Пробуем использовать ключ напрямую (если это валидный Fernet-ключ)
            self.fernet = Fernet(key.encode())
        except ValueError:
            # Иначе деривация из пароля через PBKDF2
            kdf = PBKDF2HMAC(
                algorithm=hashes.SHA256(),
                length=32,
                salt=b"instacapture_salt_v1",  # В продакшене salt должен быть уникальным!
                iterations=480000,
            )
            derived = base64.urlsafe_b64encode(kdf.derive(key.encode()))
            self.fernet = Fernet(derived)

    def encrypt(self, plaintext: str | None) -> bytes | None:
        """Шифрует строку, возвращает bytes. None на входе -> None на выходе."""
        if plaintext is None:
            return None
        return self.fernet.encrypt(plaintext.encode("utf-8"))

    def decrypt(self, ciphertext: bytes | None) -> str | None:
        """Расшифровывает bytes, возвращает строку. None на входе -> None на выходе."""
        if ciphertext is None:
            return None
        return self.fernet.decrypt(ciphertext).decode("utf-8")

    def decrypt_to_dict(self, email: bytes | None, phone: bytes | None, password: bytes | None) -> dict:
        """Утилита для массовой расшифровки полей аккаунта."""
        return {
            "email": self.decrypt(email),
            "phone": self.decrypt(phone),
            "password": self.decrypt(password),
        }


# Singleton-экземпляр
crypto = ServerCrypto()
