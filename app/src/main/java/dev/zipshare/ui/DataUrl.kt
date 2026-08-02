package dev.zipshare.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Decodes a `data:<type>;base64,<payload>` URL to a bitmap.
 *
 * Zipline hands back both the account avatar and the two-factor QR this way rather than as
 * fetchable paths, so three screens need the same three lines - and had drifted apart on how they
 * treated a missing value. Returns null for anything undecodable; every caller already has a
 * fallback for "no image".
 */
@Composable
fun rememberDataUrlBitmap(dataUrl: String?): Bitmap? = remember(dataUrl) {
    dataUrl?.substringAfter("base64,", "")
        ?.takeIf { it.isNotBlank() }
        ?.let { encoded ->
            runCatching {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
}
