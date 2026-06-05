package com.xjtu.toolbox.shared.session

import com.xjtu.toolbox.shared.auth.AuthContext
import com.xjtu.toolbox.shared.auth.CasHtmlParser
import com.xjtu.toolbox.shared.auth.SiteKey
import com.xjtu.toolbox.shared.auth.SiteVerificationContext
import com.xjtu.toolbox.shared.auth.SiteVerificationRequiredException
import com.xjtu.toolbox.shared.network.HttpClient
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.HttpResponse
import com.xjtu.toolbox.shared.webvpn.WebVpnUrlCodec

data class MobileJwappAuthConfig(
    val authorizeUrl: String = "https://org.xjtu.edu.cn/openplatform/oauth/authorize?appId=1370&redirectUri=http://jwapp.xjtu.edu.cn/app/index&responseType=code&scope=user_info&state=1234",
    val baseHost: String = "jwapp.xjtu.edu.cn",
)

class MobileJwappSessionAuthenticator(
    private val httpClient: HttpClient,
    private val config: MobileJwappAuthConfig = MobileJwappAuthConfig(),
    private val webVpnUrlCodec: WebVpnUrlCodec = WebVpnUrlCodec(),
) {
    suspend fun authenticate(context: AuthContext): Map<String, String> {
        require(context.username.isNotBlank()) { "username is required" }
        val response = exchangeRegisteredCodeCallback(
            followCasOauthRedirects(executeBrowserRequest(config.authorizeUrl)),
        )
        val finalUrl = normalizedUrl(response.finalUrl)
        if (response.bodyText.isCasOrSafetyPage() || finalUrl.contains("login.xjtu.edu.cn/cas", ignoreCase = true)) {
            throw SiteVerificationRequiredException(
                site = SiteKey.GRADE,
                message = "移动教务需要重新完成 CAS/Safety Verify 验证",
                verificationContext = SiteVerificationContext(finalUrl = response.finalUrl, bodyText = response.bodyText),
            )
        }
        if (!finalUrl.contains(config.baseHost, ignoreCase = true)) {
            throw SiteVerificationRequiredException(
                site = SiteKey.GRADE,
                message = "移动教务 SSO 未进入应用，需要重新登录",
                verificationContext = SiteVerificationContext(finalUrl = response.finalUrl, bodyText = response.bodyText),
            )
        }
        val token = extractToken(finalUrl)
            ?: error("移动教务 OAuth 响应缺少 token")
        if (!response.isSuccessful && response.code !in 300..399) {
            error("移动教务 OAuth 请求失败: HTTP ${response.code}")
        }
        return mapOf(
            "Authorization" to token,
            "User-Agent" to BROWSER_UA,
        )
    }

    private suspend fun executeBrowserRequest(url: String): HttpResponse =
        httpClient.execute(
            HttpRequest(
                url = url,
                headers = mapOf("User-Agent" to BROWSER_UA),
            ),
        )

    private suspend fun followCasOauthRedirects(initial: HttpResponse): HttpResponse {
        var response = initial
        repeat(MAX_CAS_OAUTH_REDIRECTS) {
            val finalUrl = normalizedUrl(response.finalUrl)
            if (!finalUrl.isCasOauthRedirectUrl()) return response
            response = executeBrowserRequest(response.finalUrl)
        }
        return response
    }

    private suspend fun exchangeRegisteredCodeCallback(response: HttpResponse): HttpResponse {
        val finalUrl = normalizedUrl(response.finalUrl)
        if (finalUrl.isMobileJwappCallbackWithToken()) return response
        return if (finalUrl.isMobileJwappCallbackWithCode()) {
            followCasOauthRedirects(executeBrowserRequest(finalUrl.asHttpsMobileJwappCallback()))
        } else {
            response
        }
    }

    private fun normalizedUrl(url: String): String =
        if (webVpnUrlCodec.isVpnUrl(url)) webVpnUrlCodec.fromVpnUrl(url) ?: url else url

    private fun String.isCasOauthRedirectUrl(): Boolean {
        val base = substringBefore("?")
        return base.equals(
            "https://login.xjtu.edu.cn/cas/oauth2.0/callbackAuthorize",
            ignoreCase = true,
        ) || base.equals(
            "https://login.xjtu.edu.cn/cas/oauth2.0/authorize",
            ignoreCase = true,
        )
    }

    private fun String.isMobileJwappCallbackWithCode(): Boolean =
        isMobileJwappCallback() && extractParameter("code") != null

    private fun String.isMobileJwappCallbackWithToken(): Boolean =
        isMobileJwappCallback() && extractToken(this) != null

    private fun String.isMobileJwappCallback(): Boolean =
        startsWith("http://jwapp.xjtu.edu.cn/app/index", ignoreCase = true) ||
            startsWith("https://jwapp.xjtu.edu.cn/app/index", ignoreCase = true)

    private fun String.asHttpsMobileJwappCallback(): String =
        if (startsWith("http://", ignoreCase = true)) {
            "https://" + substringAfter("://")
        } else {
            this
        }

    private fun String.isCasOrSafetyPage(): Boolean {
        if (isBlank()) return false
        if (CasHtmlParser.isSafetyVerifyPage(this)) return true
        return contains("/cas/login", ignoreCase = true) ||
            contains("login.xjtu.edu.cn/cas", ignoreCase = true)
    }

    private fun extractToken(url: String): String? =
        url.extractParameter("token")

    private fun String.extractParameter(name: String): String? {
        val query = substringAfter("?", missingDelimiterValue = "")
        val fragment = substringAfter("#", missingDelimiterValue = "")
        return listOf(query, fragment)
            .flatMap { it.split("&", "?") }
            .mapNotNull { part ->
                val pieces = part.split("=", limit = 2)
                val key = pieces.getOrNull(0)?.urlDecode() ?: return@mapNotNull null
                val value = pieces.getOrNull(1).orEmpty()
                if (key.equals(name, ignoreCase = true) && value.isNotBlank()) {
                    value.urlDecode()
                } else {
                    null
                }
            }
            .firstOrNull()
    }

    private fun String.urlDecode(): String {
        val bytes = mutableListOf<Byte>()
        val output = StringBuilder()
        var index = 0
        fun flushBytes() {
            if (bytes.isNotEmpty()) {
                output.append(bytes.toByteArray().decodeToString())
                bytes.clear()
            }
        }
        while (index < length) {
            when (val char = this[index]) {
                '%' -> {
                    val hex = substring(index + 1, index + 3)
                    bytes += hex.toInt(16).toByte()
                    index += 3
                }
                '+' -> {
                    flushBytes()
                    output.append(' ')
                    index++
                }
                else -> {
                    flushBytes()
                    output.append(char)
                    index++
                }
            }
        }
        flushBytes()
        return output.toString()
    }

    private companion object {
        private const val MAX_CAS_OAUTH_REDIRECTS = 4
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
