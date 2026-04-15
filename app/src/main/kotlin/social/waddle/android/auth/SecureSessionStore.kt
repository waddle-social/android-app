package social.waddle.android.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import social.waddle.android.data.model.StoredSession
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureSessionStore
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val json: Json,
    ) {
        suspend fun read(): StoredSession? =
            withContext(Dispatchers.IO) {
                val sessionFile = sessionFile()
                if (!sessionFile.exists()) {
                    return@withContext null
                }
                val blob = json.decodeFromString<EncryptedBlob>(sessionFile.readText())
                val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(GCM_TAG_BITS, blob.iv.decodeBase64()),
                )
                val decrypted = cipher.doFinal(blob.cipherText.decodeBase64()).decodeToString()
                json.decodeFromString<StoredSession>(decrypted)
            }

        suspend fun write(session: StoredSession) {
            withContext(Dispatchers.IO) {
                val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key())
                val encrypted = cipher.doFinal(json.encodeToString(session).encodeToByteArray())
                val blob =
                    EncryptedBlob(
                        iv = cipher.iv.encodeBase64(),
                        cipherText = encrypted.encodeBase64(),
                    )
                val sessionFile = sessionFile()
                sessionFile.parentFile?.mkdirs()
                sessionFile.writeText(json.encodeToString(blob))
            }
        }

        suspend fun clear() {
            withContext(Dispatchers.IO) {
                sessionFile().delete()
            }
        }

        private fun sessionFile(): File = File(context.noBackupFilesDir, "secure/session.json.enc")

        private fun key(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (existing != null) {
                return existing.secretKey
            }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            generator.init(
                KeyGenParameterSpec
                    .Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(AES_KEY_BITS)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return generator.generateKey()
        }

        @Serializable
        private data class EncryptedBlob(
            val iv: String,
            val cipherText: String,
        )

        private companion object {
            const val ANDROID_KEY_STORE = "AndroidKeyStore"
            const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
            const val KEY_ALIAS = "waddle_session_v1"
            const val AES_KEY_BITS = 256
            const val GCM_TAG_BITS = 128

            fun ByteArray.encodeBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

            fun String.decodeBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
        }
    }
