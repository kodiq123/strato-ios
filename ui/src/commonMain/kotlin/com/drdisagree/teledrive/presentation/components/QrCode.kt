package com.drdisagree.teledrive.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale

/**
 * Drawn black on white whatever the theme: scanners expect dark on light and many reject an
 * inverted code.
 */
@Composable
fun QrCode(content: String, contentDescription: String?, modifier: Modifier = Modifier) {
    val image: ImageBitmap? = remember(content) { encodeQr(content) }
    if (image == null) return
    Image(
        bitmap = image,
        contentDescription = contentDescription,
        filterQuality = FilterQuality.None,
        contentScale = ContentScale.Fit,
        modifier = modifier.aspectRatio(1f)
    )
}

/** A black-on-white QR code (error correction M, 1-module quiet zone), or null when it cannot be encoded. */
internal expect fun encodeQr(content: String): ImageBitmap?
