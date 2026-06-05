package com.xjtu.toolbox.shared.session

import com.xjtu.toolbox.shared.network.HttpClient
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.HttpResponse

interface CookieClock {
    fun nowMillis(): Long
}

@OptIn(kotlin.time.ExperimentalTime::class)
object SystemCookieClock : CookieClock {
    override fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}

class CookieAwareHttpClient(
    private val delegate: HttpClient,
    private val cookieStore: CookieStore,
    private val clock: CookieClock = SystemCookieClock,
) : HttpClient {
    override suspend fun execute(request: HttpRequest): HttpResponse {
        val requestUrl = ParsedUrl.from(request.url)
        val requestWithCookies = if (requestUrl == null) {
            request
        } else {
            request.withCookieHeader(requestUrl)
        }

        val response = delegate.execute(requestWithCookies)
        val responseUrl = ParsedUrl.from(response.finalUrl) ?: requestUrl
        if (responseUrl != null) {
            response.setCookieHeaders().forEach { header ->
                parseSetCookie(header, responseUrl)?.let(cookieStore::save)
            }
        }
        return response
    }

    private fun HttpRequest.withCookieHeader(url: ParsedUrl): HttpRequest {
        val cookies = cookieStore.loadForHost(url.host)
            .filter { it.matches(url, clock.nowMillis()) }
            .sortedByDescending { it.path.length }
            .joinToString("; ") { "${it.name}=${it.value}" }

        if (cookies.isBlank()) return this

        val existing = headers.cookieHeaderValue()
        val cookieHeader = listOfNotNull(existing, cookies)
            .filter { it.isNotBlank() }
            .joinToString("; ")
        return copy(headers = headers.withHeader("Cookie", cookieHeader))
    }

    private fun HttpResponse.setCookieHeaders(): List<String> =
        headers.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value.split('\n') }
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun parseSetCookie(header: String, responseUrl: ParsedUrl): StoredCookie? {
        val parts = header.split(';').map { it.trim() }.filter { it.isNotBlank() }
        val nameValue = parts.firstOrNull() ?: return null
        val separator = nameValue.indexOf('=')
        if (separator <= 0) return null

        val attributes = parts.drop(1)
        val maxAgeSeconds = attributes.value("Max-Age")?.toLongOrNull()
        val expiresAt = when {
            maxAgeSeconds == null -> null
            maxAgeSeconds <= 0 -> clock.nowMillis() - 1
            else -> clock.nowMillis() + maxAgeSeconds * 1_000
        }

        return StoredCookie(
            name = nameValue.substring(0, separator),
            value = nameValue.substring(separator + 1),
            domain = attributes.value("Domain")
                ?.trimStart('.')
                ?.lowercase()
                ?: responseUrl.host,
            path = attributes.value("Path") ?: responseUrl.defaultCookiePath(),
            expiresAtEpochMillis = expiresAt,
            secure = attributes.hasFlag("Secure"),
            httpOnly = attributes.hasFlag("HttpOnly"),
        )
    }

    private fun StoredCookie.matches(url: ParsedUrl, nowMillis: Long): Boolean {
        if (expiresAtEpochMillis != null && nowMillis >= expiresAtEpochMillis) return false
        if (secure && url.scheme != "https") return false
        if (!pathMatches(cookiePath = path, requestPath = url.path)) return false
        return true
    }
}

private data class ParsedUrl(
    val scheme: String,
    val host: String,
    val path: String,
) {
    fun defaultCookiePath(): String {
        if (!path.startsWith('/')) return "/"
        val lastSlash = path.lastIndexOf('/')
        return if (lastSlash <= 0) "/" else path.substring(0, lastSlash)
    }

    companion object {
        fun from(rawUrl: String): ParsedUrl? {
            val schemeEnd = rawUrl.indexOf("://")
            if (schemeEnd <= 0) return null
            val scheme = rawUrl.substring(0, schemeEnd).lowercase()
            val afterScheme = rawUrl.substring(schemeEnd + 3)
            val authorityEnd = afterScheme.indexOfAny(charArrayOf('/', '?', '#')).let { index ->
                if (index == -1) afterScheme.length else index
            }
            val authority = afterScheme.substring(0, authorityEnd)
            val host = authority
                .substringAfter('@')
                .substringBefore(':')
                .trim()
                .lowercase()
            if (host.isBlank()) return null
            val pathStart = schemeEnd + 3 + authorityEnd
            val path = rawUrl.substring(pathStart).takeIf { it.startsWith('/') }?.substringBefore('?') ?: "/"
            return ParsedUrl(scheme, host, path)
        }
    }
}

private fun Map<String, String>.cookieHeaderValue(): String? =
    entries.firstOrNull { it.key.equals("Cookie", ignoreCase = true) }?.value

private fun pathMatches(cookiePath: String, requestPath: String): Boolean =
    requestPath == cookiePath || requestPath.startsWith(
        if (cookiePath.endsWith('/')) cookiePath else "$cookiePath/",
    )

private fun Map<String, String>.withHeader(name: String, value: String): Map<String, String> {
    val withoutExisting = filterKeys { !it.equals(name, ignoreCase = true) }
    return withoutExisting + (name to value)
}

private fun List<String>.value(name: String): String? =
    firstNotNullOfOrNull { attribute ->
        val separator = attribute.indexOf('=')
        if (separator <= 0) return@firstNotNullOfOrNull null
        val key = attribute.substring(0, separator)
        if (key.equals(name, ignoreCase = true)) attribute.substring(separator + 1) else null
    }

private fun List<String>.hasFlag(name: String): Boolean =
    any { it.equals(name, ignoreCase = true) }
