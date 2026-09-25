package com.hyalos.player.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/** A DataStore serializer for any `@Serializable` type, stored as JSON. */
class JsonSerializer<T>(
    private val serializer: KSerializer<T>,
    override val defaultValue: T,
) : Serializer<T> {

    override suspend fun readFrom(input: InputStream): T = try {
        json.decodeFromString(serializer, input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        // Reported as corruption so the store's corruption handler decides what
        // happens, rather than every read throwing forever.
        throw CorruptionException("stored JSON could not be read", e)
    } catch (e: IllegalArgumentException) {
        throw CorruptionException("stored JSON could not be read", e)
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        output.write(json.encodeToString(serializer, t).encodeToByteArray())
    }

    private companion object {
        /**
         * `ignoreUnknownKeys` so that a file written by a newer version of the
         * app still loads after a downgrade; `encodeDefaults` so every field is
         * written out and the file is readable by eye.
         */
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
