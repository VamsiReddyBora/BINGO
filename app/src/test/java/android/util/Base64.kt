package android.util

/** JVM test implementation of Android's Base64 API; never packaged in the APK. */
object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String {
        var encoder = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder()
            else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) encoder = encoder.withoutPadding()
        val encoded = encoder.encodeToString(input)
        if (flags and NO_WRAP != 0 || encoded.isEmpty()) return encoded
        val separator = if (flags and CRLF != 0) "\r\n" else "\n"
        return encoded.chunked(76).joinToString(separator, postfix = separator)
    }

    @JvmStatic
    fun decode(input: String, flags: Int): ByteArray {
        val decoder = if (flags and URL_SAFE != 0) java.util.Base64.getUrlDecoder()
            else java.util.Base64.getDecoder()
        return decoder.decode(input.filterNot { it.isWhitespace() })
    }
}
