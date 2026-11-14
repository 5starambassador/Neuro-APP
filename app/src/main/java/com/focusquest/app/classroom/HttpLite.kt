package com.focusquest.app.classroom

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/**
 * Just enough HTTP/1.1 for the local web dashboard: parse one request, write one response.
 * Requests are size-limited so a misbehaving client on the Wi‑Fi can't exhaust memory.
 */
data class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun cookie(name: String): String? = headers["cookie"]
        ?.split(';')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')

    val bodyText: String get() = body.toString(Charsets.UTF_8)
}

object HttpLite {
    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val MAX_BODY_BYTES = 64 * 1024

    fun readRequest(input: InputStream): HttpRequest? {
        val head = StringBuilder()
        var total = 0
        // Read until the blank line that ends the headers.
        while (true) {
            val b = input.read()
            if (b < 0) return null
            head.append(b.toChar())
            total++
            if (total > MAX_HEADER_BYTES) throw IOException("Headers too large")
            if (head.endsWith("\r\n\r\n") || head.endsWith("\n\n")) break
        }
        val lines = head.lines().filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val parts = lines[0].split(' ')
        if (parts.size < 2) return null
        val headers = lines.drop(1).mapNotNull { l ->
            val i = l.indexOf(':')
            if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
        }.toMap()
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > MAX_BODY_BYTES) throw IOException("Body too large")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw IOException("Truncated body")
            read += n
        }
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = if ('?' in target) parseQuery(target.substringAfter('?')) else emptyMap()
        return HttpRequest(parts[0].uppercase(), decode(path), query, headers, body)
    }

    fun parseQuery(q: String): Map<String, String> = q.split('&').filter { it.isNotEmpty() }.associate {
        decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
    }

    private fun decode(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    private val REASONS = mapOf(
        200 to "OK", 204 to "No Content", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden",
        404 to "Not Found", 405 to "Method Not Allowed", 409 to "Conflict", 429 to "Too Many Requests", 500 to "Internal Server Error",
    )

    /** Security headers sent on every response. */
    private val SECURITY_HEADERS = listOf(
        "X-Content-Type-Options" to "nosniff",
        "X-Frame-Options" to "DENY",
        "Referrer-Policy" to "no-referrer",
        "Cache-Control" to "no-store",
        "Content-Security-Policy" to
            "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'",
    )

    fun writeHead(out: OutputStream, status: Int, contentType: String, length: Long?, extra: List<Pair<String, String>> = emptyList()) {
        val sb = StringBuilder("HTTP/1.1 $status ${REASONS[status] ?: "OK"}\r\n")
        sb.append("Content-Type: $contentType\r\n")
        if (length != null) sb.append("Content-Length: $length\r\n")
        (SECURITY_HEADERS + extra).forEach { (k, v) -> sb.append("$k: $v\r\n") }
        if (length != null) sb.append("Connection: close\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    fun respond(out: OutputStream, status: Int, contentType: String, body: ByteArray, extra: List<Pair<String, String>> = emptyList()) {
        writeHead(out, status, contentType, body.size.toLong(), extra)
        out.write(body)
        out.flush()
    }

    fun json(out: OutputStream, status: Int, json: String, extra: List<Pair<String, String>> = emptyList()) =
        respond(out, status, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8), extra)
}
