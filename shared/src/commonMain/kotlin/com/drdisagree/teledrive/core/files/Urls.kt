package com.drdisagree.teledrive.core.files

object Urls {

    val PATTERN = Regex("""(?:https?://|www\.)[^\s<>"')\]]+""", RegexOption.IGNORE_CASE)

    fun all(text: String): List<String> =
        PATTERN.findAll(text).map { it.value.trimEnd('.', ',', ';', ':') }.toList()

    fun sole(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        return PATTERN.matchEntire(trimmed)?.value
    }

    /** Host of an absolute URL, like java.net.URI.getHost; null when absent or malformed. */
    fun host(url: String): String? {
        val afterScheme = url.substringAfter("://", "").ifEmpty { return null }
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPort = authority.substringAfterLast('@')
        val host = if (hostPort.startsWith("[")) {
            hostPort.substringBefore(']') + "]"
        } else {
            hostPort.substringBefore(':')
        }
        return host.takeIf { it.isNotEmpty() && it.none { c -> c.isWhitespace() || c in ILLEGAL } }
    }

    fun normalize(url: String): String =
        if (url.startsWith("http", ignoreCase = true)) url else "https://$url"

    private const val ILLEGAL = "<>\"{}|^`\\"
}
