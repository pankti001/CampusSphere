package com.campussphere.app.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionStore(context: Context, private val apiBaseUrl: String) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "auth_session"))

    suspend fun load(): String? = withContext(Dispatchers.IO) {
        val payload = try {
            file.readFully()
        } catch (_: FileNotFoundException) {
            if (file.baseFile.exists()) throw IOException("Could not read saved session")
            return@withContext null
        }

        try {
            if (payload.size <= IV_LENGTH + TAG_BYTES || payload[0] != FORMAT_VERSION) {
                throw GeneralSecurityException("Unknown session format")
            }
            val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
                ?: throw GeneralSecurityException("Session key is unavailable")
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(
                TAG_BYTES * Byte.SIZE_BITS, payload.copyOfRange(1, IV_LENGTH + 1)
            ))
            cipher.updateAAD(apiBaseUrl.encodeToByteArray())
            cipher.doFinal(payload, IV_LENGTH + 1, payload.size - IV_LENGTH - 1).decodeToString()
        } catch (_: GeneralSecurityException) {
            deleteSavedSession()
            null
        }
    }

    suspend fun save(token: String) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        check(cipher.iv.size == IV_LENGTH)
        cipher.updateAAD(apiBaseUrl.encodeToByteArray())
        val payload = byteArrayOf(FORMAT_VERSION) + cipher.iv + cipher.doFinal(token.encodeToByteArray())
        val stream = file.startWrite()
        try {
            stream.write(payload)
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { deleteSavedSession() }

    private fun deleteSavedSession() {
        file.delete()
        val basePath = file.baseFile.path
        if (listOf(basePath, "$basePath.bak", "$basePath.new").any { File(it).exists() }) {
            throw IOException("Could not remove saved session")
        }
    }

    private fun encryptionKey(): SecretKey {
        val store = keyStore()
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val CIPHER = "AES/GCM/NoPadding"
        const val KEY_ALIAS = "campussphere.session"
        const val IV_LENGTH = 12
        const val TAG_BYTES = 16
        const val FORMAT_VERSION: Byte = 1
    }
}
