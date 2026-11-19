package com.drdisagree.teledrive.presentation.components

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceGray
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIFilter
import platform.CoreImage.createCGImage
import platform.CoreImage.filterWithName
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setValue

/**
 * CoreImage's CIQRCodeGenerator draws one pixel per module. The pixels are read back into a module
 * grid, cropped to the dark modules and given the same 1-module quiet zone as the zxing version.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun encodeQr(content: String): ImageBitmap? = runCatching {
    @Suppress("CAST_NEVER_SUCCEEDS")
    val data = (content as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return null
    val filter = CIFilter.filterWithName("CIQRCodeGenerator") ?: return null
    filter.setValue(data, forKey = "inputMessage")
    filter.setValue("M", forKey = "inputCorrectionLevel")
    val output = filter.outputImage ?: return null
    val cgImage = CIContext().createCGImage(output, fromRect = output.extent) ?: return null

    val width = CGImageGetWidth(cgImage).toInt()
    val height = CGImageGetHeight(cgImage).toInt()
    val gray = ByteArray(width * height)
    try {
        gray.usePinned { pinned ->
            val space = CGColorSpaceCreateDeviceGray()
            val context = CGBitmapContextCreate(
                data = pinned.addressOf(0),
                width = width.convert(),
                height = height.convert(),
                bitsPerComponent = 8u,
                bytesPerRow = width.convert(),
                space = space,
                bitmapInfo = CGImageAlphaInfo.kCGImageAlphaNone.value
            )
            CGColorSpaceRelease(space)
            context ?: return null
            CGContextDrawImage(
                context,
                CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()),
                cgImage
            )
            CGContextRelease(context)
        }
    } finally {
        CGImageRelease(cgImage)
    }

    fun isDark(x: Int, y: Int) = (gray[y * width + x].toInt() and 0xFF) < DARK_THRESHOLD

    var top = height
    var left = width
    var bottom = -1
    var right = -1
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (isDark(x, y)) {
                if (y < top) top = y
                if (y > bottom) bottom = y
                if (x < left) left = x
                if (x > right) right = x
            }
        }
    }
    if (bottom < 0) return null

    val modules = maxOf(right - left, bottom - top) + 1
    val side = modules + 2 * QR_MARGIN
    val image = ImageBitmap(side, side)
    val canvas = Canvas(image)
    val white = Paint().apply { color = Color.White }
    val black = Paint().apply { color = Color.Black }
    canvas.drawRect(0f, 0f, side.toFloat(), side.toFloat(), white)
    for (y in 0 until modules) {
        for (x in 0 until modules) {
            val sourceX = left + x
            val sourceY = top + y
            if (sourceX < width && sourceY < height && isDark(sourceX, sourceY)) {
                val px = (x + QR_MARGIN).toFloat()
                val py = (y + QR_MARGIN).toFloat()
                canvas.drawRect(px, py, px + 1f, py + 1f, black)
            }
        }
    }
    image
}.getOrNull()

private const val QR_MARGIN = 1
private const val DARK_THRESHOLD = 128
