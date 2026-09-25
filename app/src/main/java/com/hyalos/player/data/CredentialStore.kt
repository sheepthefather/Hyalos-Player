package com.hyalos.player.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.Base64

/**
 * Server passwords, encrypted.
 *
 * Tink AEAD (AES-256-GCM) with its keyset wrapped by a master key in the
 * Android Keystore; the ciphertexts sit in `credentials.json`. Each one is
 * bound to its server id as associated data, so a ciphertext copied to another
 * entry fails to decrypt instead of logging in somewhere it was not meant for.
 *
 * # When the key is gone
 *
 * The Keystore key does not travel with backups or device transfers, and it can
 * be wiped. Both the keyset and the ciphertexts are excluded from backup for
 * that reason, but if the keyset still cannot be decrypted — a Keystore reset,
 * say — Tink throws rather than regenerating. That is recovered here by
 * discarding the keyset and every stored password and starting over: the
 * server list survives, and the user is asked for passwords again. Silently
 * failing to log in forever would be the alternative.
 */
class CredentialStore(
    private val context: Context,
    private val store: DataStore<StoredCredentials>,
) {
    private val aeadLock = Mutex()
    private var aead: Aead? = null

    /** The password for [serverId], or `null` if none is stored or it can no longer be read. */
    suspend fun passwordFor(serverId: String): String? {
        val encoded = store.data.first().passwords[serverId] ?: return null
        val aead = aead()
        return withContext(Dispatchers.IO) {
            try {
                aead.decrypt(Base64.getDecoder().decode(encoded), serverId.toByteArray())
                    .decodeToString()
            } catch (e: GeneralSecurityException) {
                // Encrypted under a key that no longer exists. Unrecoverable,
                // and keeping it would only fail the same way next time.
                null
            } catch (e: IllegalArgumentException) {
                null // Not valid Base64: the file was edited or damaged.
            }
        }.also { if (it == null) remove(serverId) }
    }

    /** Store [password] for [serverId]; `null` removes it. */
    suspend fun setPassword(serverId: String, password: String?) {
        if (password == null) {
            remove(serverId)
            return
        }
        val aead = aead()
        val encoded = withContext(Dispatchers.IO) {
            Base64.getEncoder().encodeToString(
                aead.encrypt(password.toByteArray(), serverId.toByteArray()),
            )
        }
        store.updateData { it.copy(passwords = it.passwords + (serverId to encoded)) }
    }

    suspend fun remove(serverId: String) {
        store.updateData { it.copy(passwords = it.passwords - serverId) }
    }

    /**
     * Built lazily and on the IO dispatcher: it touches the Keystore and a
     * SharedPreferences file, neither of which belongs on the main thread.
     */
    private suspend fun aead(): Aead = aeadLock.withLock {
        aead ?: withContext(Dispatchers.IO) {
            AeadConfig.register()
            try {
                buildAead()
            } catch (e: GeneralSecurityException) {
                resetAndBuild()
            } catch (e: IOException) {
                resetAndBuild()
            }
        }.also { aead = it }
    }

    private suspend fun resetAndBuild(): Aead {
        context.deleteSharedPreferences(KEYSET_PREFS)
        // Anything encrypted under the lost keyset is unreadable now.
        store.updateData { StoredCredentials() }
        return buildAead()
    }

    private fun buildAead(): Aead = AndroidKeysetManager.Builder()
        .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
        .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
        .withMasterKeyUri(MASTER_KEY_URI)
        .build()
        .keysetHandle
        .getPrimitive(RegistryConfiguration.get(), Aead::class.java)

    companion object {
        fun create(context: Context): CredentialStore = CredentialStore(
            context.applicationContext,
            DataStoreFactory.create(
                serializer = JsonSerializer(StoredCredentials.serializer(), StoredCredentials()),
                corruptionHandler = ReplaceFileCorruptionHandler { StoredCredentials() },
                produceFile = { context.dataStoreFile(FILE_NAME) },
            ),
        )

        // The two file names below are also named in res/xml/backup_rules.xml
        // and res/xml/data_extraction_rules.xml. Keep them in step: a renamed
        // file silently starts being backed up.
        const val FILE_NAME = "credentials.json"
        private const val KEYSET_PREFS = "hyalos_keyset_prefs"
        private const val KEYSET_NAME = "hyalos_keyset"
        private const val MASTER_KEY_URI = "android-keystore://hyalos_master_key"
    }
}

/** Base64 ciphertexts, keyed by server id. */
@Serializable
data class StoredCredentials(val passwords: Map<String, String> = emptyMap())
