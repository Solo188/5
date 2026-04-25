package com.instacapture

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * CryptoManager — шифрование данных через Android Keystore System.
 * Ключ генерируется при первом запуске и НИКОГДА не покидает Keystore.
 * Алгоритм: AES-256-GCM (рекомендован Google для конфиденциальных данных).
 */
class CryptoManager {
    companion object {
        private const val TAG = "InstaCapture:Crypto"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "InstaCaptureMasterKey"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BITS = 128
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    init {
        if (!keyStore.containsAlias(ALIAS)) {
            generateKey()
        }
    }

    /**
     * Генерация 256-битного ключа внутри TEE (Trusted Execution Environment).
     * Ключ защищён аппаратно на устройствах с StrongBox.
     */
    private fun generateKey() {
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).apply {
            setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            setKeySize(256)
            setRandomizedEncryptionRequired(true)
            // Если устройство поддерживает StrongBox — используем аппаратный HSM
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                setIsStrongBoxBacked(true)
            }
        }.build()
        keyGen.init(spec)
        keyGen.generateKey()
        Log.i(TAG, "Ключ AES-256 сгенерирован в Keystore")
    }

    private fun getSecretKey(): SecretKey {
        return keyStore.getEntry(ALIAS, null) as KeyStore.SecretKeyEntry
    }.secretKey

    /**
     * Шифрует plaintext и возвращает Base64(IV + ciphertext + authTag).
     */
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        
        // Конкатенация: [12 байт IV] + [ciphertext + 16 байт tag]
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Расшифровывает данные. Используется для тестов и отладки;
     * в продакшене расшифровка происходит только на сервере.
     */
    fun decrypt(encryptedBase64: String): String {
        val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        
        val iv = ByteArray(IV_LENGTH_BYTES)
        val ciphertext = ByteArray(combined.size - IV_LENGTH_BYTES)
        
        System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES)
        System.arraycopy(combined, IV_LENGTH_BYTES, ciphertext, 0, ciphertext.size)
        
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
