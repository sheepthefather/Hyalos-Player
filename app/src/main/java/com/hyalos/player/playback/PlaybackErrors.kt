package com.hyalos.player.playback

import android.content.Context
import android.util.Pair
import androidx.media3.common.ErrorMessageProvider
import androidx.media3.common.PlaybackException
import com.hyalos.player.R

/**
 * What `PlayerView` shows when playback fails, in Chinese.
 *
 * Grouped by what the user can do about it rather than by every error code:
 * the file is gone, access is refused, the network dropped, or the device
 * cannot handle the format. The error code's name is appended to the generic
 * case so a report from the field still says what actually happened.
 */
class PlaybackErrors(private val context: Context) : ErrorMessageProvider<PlaybackException> {
    override fun getErrorMessage(e: PlaybackException): Pair<Int, String> {
        val message = when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> context.getString(R.string.player_error_not_found)
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> context.getString(R.string.player_error_permission)
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> context.getString(R.string.player_error_network)
            in PARSING_ERRORS -> context.getString(R.string.player_error_format)
            in DECODER_ERRORS -> context.getString(R.string.player_error_decoder)
            else -> context.getString(R.string.player_error_generic) + "（${e.errorCodeName}）"
        }
        return Pair.create(0, message)
    }

    private companion object {
        val PARSING_ERRORS = setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        )

        val DECODER_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )
    }
}
