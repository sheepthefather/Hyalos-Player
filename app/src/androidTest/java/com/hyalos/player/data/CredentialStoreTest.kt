package com.hyalos.player.data

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

/**
 * Against the real Android Keystore, because that is the part worth testing:
 * the encryption itself is Tink's, but what happens when the key disappears is
 * ours.
 *
 * Note that these tests use the app's real keyset name and master key alias,
 * so running them on a device resets any passwords the app had saved there.
 */
@RunWith(AndroidJUnit4::class)
class CredentialStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var file: File

    @Before
    fun setUp() {
        file = File(context.cacheDir, "credentials-test-${System.nanoTime()}.json")
        resetKeys()
    }

    @After
    fun tearDown() {
        file.delete()
        resetKeys()
    }

    /**
     * A fresh store over the same file. DataStore allows one instance per file
     * per process, so each "restart" gets a new file seeded with the old one's
     * contents.
     */
    private fun store(from: File? = null): CredentialStore {
        if (from != null) {
            val next = File(context.cacheDir, "credentials-test-${System.nanoTime()}.json")
            from.copyTo(next)
            file = next
        }
        val target = file
        return CredentialStore(
            context,
            DataStoreFactory.create(
                serializer = JsonSerializer(StoredCredentials.serializer(), StoredCredentials()),
                produceFile = { target },
            ),
        )
    }

    @Test
    fun aPasswordRoundTrips() = runTest {
        val store = store()
        store.setPassword("a", "hunter2 密码")
        assertEquals("hunter2 密码", store.passwordFor("a"))
        assertNull(store.passwordFor("b"))
    }

    @Test
    fun nothingIsStoredInTheClear() = runTest {
        // `setPassword` returns once DataStore has written the file.
        store().setPassword("a", "hunter2")
        assertFalse("password written in the clear", file.readText().contains("hunter2"))
    }

    @Test
    fun aCiphertextMovedToAnotherServerDoesNotDecrypt() = runTest {
        // The server id is the associated data, so a copied ciphertext must
        // fail rather than log in somewhere else.
        val store = store()
        store.setPassword("a", "secret")
        val stolen = file.readText().replace("\"a\"", "\"b\"")
        val tampered = File(context.cacheDir, "credentials-test-${System.nanoTime()}.json")
        tampered.writeText(stolen)
        file = tampered
        assertNull(store(from = null).passwordFor("b"))
    }

    @Test
    fun removingForgetsThePassword() = runTest {
        val store = store()
        store.setPassword("a", "secret")
        store.remove("a")
        assertNull(store.passwordFor("a"))
    }

    @Test
    fun aLostMasterKeyResetsInsteadOfFailingForever() = runTest {
        store().setPassword("a", "secret")

        // What a Keystore wipe or a restore onto another device looks like:
        // the keyset is still on disk, but the key that unwraps it is gone.
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(MASTER_KEY_ALIAS)

        val restarted = store(from = file)
        assertNull("the old password cannot be recovered", restarted.passwordFor("a"))
        // And the store works again afterwards.
        restarted.setPassword("a", "new")
        assertEquals("new", restarted.passwordFor("a"))
    }

    private fun resetKeys() {
        context.deleteSharedPreferences(KEYSET_PREFS)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(MASTER_KEY_ALIAS)
    }

    private companion object {
        // Mirrors CredentialStore's private constants.
        const val KEYSET_PREFS = "hyalos_keyset_prefs"
        const val MASTER_KEY_ALIAS = "hyalos_master_key"
    }
}
