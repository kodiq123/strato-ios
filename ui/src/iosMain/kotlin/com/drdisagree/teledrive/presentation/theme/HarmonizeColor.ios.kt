package com.drdisagree.teledrive.presentation.theme

import kotlin.math.floor

/** Same hue shift as the desktop version; [rgbToHsb] and [hsbToRgb] follow java.awt.Color exactly. */
internal actual fun harmonizeColor(argb: Int, primaryArgb: Int): Int {
    val source = rgbToHsb(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF)
    val target = rgbToHsb(primaryArgb shr 16 and 0xFF, primaryArgb shr 8 and 0xFF, primaryArgb and 0xFF)
    val sourceDegrees = source[0] * 360f
    val targetDegrees = target[0] * 360f
    var delta = targetDegrees - sourceDegrees
    if (delta > 180f) delta -= 360f
    if (delta < -180f) delta += 360f
    val shift = delta.coerceIn(-MAX_SHIFT_DEGREES, MAX_SHIFT_DEGREES)
    val hue = ((sourceDegrees + shift + 360f) % 360f) / 360f
    val rgb = hsbToRgb(hue, source[1], source[2])
    return (argb.toLong() and 0xFF000000L).toInt() or (rgb and 0xFFFFFF)
}

private fun rgbToHsb(r: Int, g: Int, b: Int): FloatArray {
    val cmax = maxOf(r, g, b)
    val cmin = minOf(r, g, b)
    val brightness = cmax / 255f
    val saturation = if (cmax != 0) (cmax - cmin).toFloat() / cmax.toFloat() else 0f
    var hue = 0f
    if (saturation != 0f) {
        val range = (cmax - cmin).toFloat()
        val redc = (cmax - r) / range
        val greenc = (cmax - g) / range
        val bluec = (cmax - b) / range
        hue = when {
            r == cmax -> bluec - greenc
            g == cmax -> 2f + redc - bluec
            else -> 4f + greenc - redc
        } / 6f
        if (hue < 0) hue += 1f
    }
    return floatArrayOf(hue, saturation, brightness)
}

private fun hsbToRgb(hue: Float, saturation: Float, brightness: Float): Int {
    var r = 0
    var g = 0
    var b = 0
    if (saturation == 0f) {
        val v = (brightness * 255f + 0.5f).toInt()
        r = v; g = v; b = v
    } else {
        val h = (hue - floor(hue)) * 6f
        val f = h - floor(h)
        val p = brightness * (1f - saturation)
        val q = brightness * (1f - saturation * f)
        val t = brightness * (1f - saturation * (1f - f))
        fun c(value: Float) = (value * 255f + 0.5f).toInt()
        when (h.toInt()) {
            0 -> { r = c(brightness); g = c(t); b = c(p) }
            1 -> { r = c(q); g = c(brightness); b = c(p) }
            2 -> { r = c(p); g = c(brightness); b = c(t) }
            3 -> { r = c(p); g = c(q); b = c(brightness) }
            4 -> { r = c(t); g = c(p); b = c(brightness) }
            5 -> { r = c(brightness); g = c(p); b = c(q) }
        }
    }
    return (0xFF000000L.toInt()) or (r shl 16) or (g shl 8) or b
}

private const val MAX_SHIFT_DEGREES = 15f
