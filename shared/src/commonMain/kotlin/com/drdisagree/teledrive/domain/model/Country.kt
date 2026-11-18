package com.drdisagree.teledrive.domain.model

data class Country(
    val isoCode: String,
    val name: String,
    val callingCode: String
) {

    val flag: String
        get() = isoCode
            .uppercase()
            .filter { it in 'A'..'Z' }
            .takeIf { it.length == 2 }
            ?.map { surrogatePair(REGIONAL_INDICATOR_BASE + (it - 'A')) }
            ?.joinToString("")
            .orEmpty()

    private companion object {
        const val REGIONAL_INDICATOR_BASE = 0x1F1E6

        /** UTF-16 encoding of a supplementary code point, same result as Character.toChars. */
        fun surrogatePair(codePoint: Int): String {
            val offset = codePoint - 0x10000
            return charArrayOf(
                (0xD800 + (offset shr 10)).toChar(),
                (0xDC00 + (offset and 0x3FF)).toChar()
            ).concatToString()
        }
    }
}
