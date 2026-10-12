package com.walkman.tv.data.logging

/**
 * Central redaction for everything that reaches the running log. Nothing else may format log
 * text, so a single place decides what is safe to persist.
 *
 * Rules (see spec §10):
 *  - secrets keyed by name (`token`, `password`, `connection code`, `api key`, `aes key`, `rsa
 *    key`, `client secret`, ...) are replaced with `***`;
 *  - `Authorization` / `Cookie` header values are masked to end of line;
 *  - `Bearer <credential>` is masked;
 *  - sensitive query parameters (`?token=...`, `&sign=...`) are masked;
 *  - a share URL path (`/log/<token>`) is masked;
 *  - URLs are never stored whole - only scheme/host/path.
 */
object LogSanitizer {

    private val URL_PATH = Regex("""/log/[0-9a-fA-F]{16,}""")

    private val HEADER = Regex(
        """(?i)\b(authorization|cookie|set-cookie)\b\s*[:=]\s*[^\r\n]+""",
    )

    private val BEARER = Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{4,}""")

    private val KEYED_SECRET = Regex(
        """(?i)\b(connection[-_ ]?code|connectioncode|password|passwd|token|api[-_ ]?key|apikey|""" +
            """aes[-_ ]?key|aeskey|rsa[-_ ]?key|rsakey|private[-_ ]?key|client[-_ ]?secret|""" +
            """secret|auth[-_ ]?key)\b\s*[:=]\s*("?)[^\s,;&"']+\2""",
    )

    private val QUERY_SECRET = Regex(
        """(?i)([?&])(token|sign|signature|key|auth|code|password|aeskey|rsakey|secret)=[^&\s]+""",
    )

    /** Mask anything that looks like a credential inside a free-form message. */
    fun sanitizeText(text: String): String {
        var out = text.replace(URL_PATH, "/log/***")
        out = HEADER.replace(out) { "${it.groupValues[1]}=***" }
        out = BEARER.replace(out) { "${it.groupValues[1]} ***" }
        out = KEYED_SECRET.replace(out) { "${it.groupValues[1]}=***" }
        out = QUERY_SECRET.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}=***" }
        return out
    }

    /**
     * Never store a play URL whole: keep only `scheme=... host=... path=...` (no query, no
     * userinfo, no fragment). Unparseable input degrades to `host=? path=?`.
     */
    fun sanitizeUrl(url: String): String = runCatching {
        val uri = java.net.URI(url)
        val host = uri.host
        if (host.isNullOrEmpty()) {
            "host=? path=?"
        } else {
            val path = uri.path ?: ""
            "scheme=${uri.scheme ?: "?"} host=$host path=${if (path.isEmpty()) "/" else path}"
        }
    }.getOrElse { "host=? path=?" }

    /** Convenience for logging a value without ever persisting secrets inside it. */
    fun sanitizeValue(value: Any?): String = when (value) {
        null -> "null"
        is String -> sanitizeText(value)
        else -> sanitizeText(value.toString())
    }
}
