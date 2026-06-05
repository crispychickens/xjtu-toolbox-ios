package com.xjtu.toolbox.shared.session

import com.xjtu.toolbox.shared.auth.AuthContext
import com.xjtu.toolbox.shared.auth.CasHtmlParser
import com.xjtu.toolbox.shared.auth.SiteKey
import com.xjtu.toolbox.shared.auth.SiteVerificationContext
import com.xjtu.toolbox.shared.auth.SiteVerificationRequiredException
import com.xjtu.toolbox.shared.network.HttpClient
import com.xjtu.toolbox.shared.network.HttpMethod
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.HttpResponse
import com.xjtu.toolbox.shared.parsing.JsonParser
import com.xjtu.toolbox.shared.parsing.asObjectOrNull
import com.xjtu.toolbox.shared.parsing.asStringOrNull
import com.xjtu.toolbox.shared.webvpn.WebVpnUrlCodec

data class NcardAuthConfig(
    val loginUrl: String = "https://ncard.xjtu.edu.cn/berserker-base/redirect?type=login&loginFrom=h5&synAccessSource=h5",
    val tokenUrl: String = "https://ncard.xjtu.edu.cn/berserker-auth/oauth/token",
    val baseHost: String = "ncard.xjtu.edu.cn",
    val tokenBasicAuth: String = "Basic bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm06bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm1fc2VjcmV0",
)

class NcardSessionAuthenticator(
    private val httpClient: HttpClient,
    private val config: NcardAuthConfig = NcardAuthConfig(),
    private val webVpnUrlCodec: WebVpnUrlCodec = WebVpnUrlCodec(),
) {
    suspend fun authenticate(context: AuthContext): Map<String, String> {
        require(context.username.isNotBlank()) { "username is required" }
        val ticket = requestTicket()
            ?: error("校园卡 SSO 未拿到 ticket，需要重新登录")
        val token = exchangeTicketForToken(ticket)
        return mapOf(
            "synjones-auth" to "bearer $token",
            "synAccessSource" to "h5",
        )
    }

    private suspend fun requestTicket(): String? {
        repeat(2) {
            val response = httpClient.execute(
                HttpRequest(
                    url = config.loginUrl,
                    headers = mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"),
                ),
            )
            extractNcardTicket(response.finalUrl)?.let { return it }
            if (response.isCasOrSafetyPage()) {
                throw SiteVerificationRequiredException(
                    site = SiteKey.CAMPUS_CARD,
                    message = "校园卡需要重新完成 CAS/Safety Verify 验证",
                    verificationContext = SiteVerificationContext(
                        finalUrl = response.finalUrl,
                        bodyText = response.bodyText,
                    ),
                )
            }
        }
        return null
    }

    private suspend fun exchangeTicketForToken(ticket: String): String {
        val response = httpClient.execute(
            formPost(
                url = config.tokenUrl,
                headers = mapOf("Authorization" to config.tokenBasicAuth),
                fields = listOf(
                    "username" to ticket,
                    "password" to ticket,
                    "grant_type" to "password",
                    "scope" to "all",
                    "loginFrom" to "h5",
                    "logintype" to "sso",
                    "device_token" to "h5",
                    "synAccessSource" to "h5",
                ),
            ),
        )
        if (!response.isSuccessful) {
            error("校园卡 token 请求失败: HTTP ${response.code}")
        }
        val root = JsonParser(response.bodyText).parse().asObjectOrNull()
            ?: error("校园卡 token 响应不是 JSON 对象")
        return root["access_token"]?.asStringOrNull()?.takeIf { it.isNotBlank() }
            ?: error("校园卡 token 响应缺少 access_token")
    }

    private fun extractNcardTicket(finalUrl: String): String? {
        val ncardUrl = when {
            finalUrl.contains(config.baseHost, ignoreCase = true) -> finalUrl
            webVpnUrlCodec.isVpnUrl(finalUrl) -> webVpnUrlCodec.fromVpnUrl(finalUrl)
            else -> null
        } ?: return null
        if (!ncardUrl.contains(config.baseHost, ignoreCase = true)) return null
        return queryParameters(ncardUrl)["ticket"]?.takeIf { it.isNotBlank() }
    }

    private fun HttpResponse.isCasOrSafetyPage(): Boolean {
        if (finalUrl.contains("/cas/login", ignoreCase = true) ||
            finalUrl.contains("login.xjtu.edu.cn/cas", ignoreCase = true)
        ) {
            return true
        }
        if (CasHtmlParser.isSafetyVerifyPage(bodyText)) return true
        return bodyText.contains("/cas/login", ignoreCase = true) ||
            bodyText.contains("login.xjtu.edu.cn/cas", ignoreCase = true)
    }

    private fun formPost(
        url: String,
        headers: Map<String, String>,
        fields: List<Pair<String, String>>,
    ): HttpRequest =
        HttpRequest(
            url = url,
            method = HttpMethod.POST,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded") + headers,
            body = fields.joinToString("&") { (name, value) -> "${formEncode(name)}=${formEncode(value)}" }
                .encodeToByteArray(),
        )

    private fun queryParameters(url: String): Map<String, String> {
        val queryStart = url.indexOf('?')
        if (queryStart == -1 || queryStart == url.lastIndex) return emptyMap()
        val fragmentStart = url.indexOf('#', startIndex = queryStart + 1)
        val query = if (fragmentStart == -1) {
            url.substring(queryStart + 1)
        } else {
            url.substring(queryStart + 1, fragmentStart)
        }
        return query.split('&')
            .filter { it.isNotBlank() }
            .associate { pair ->
                val key = pair.substringBefore('=').formDecode()
                val value = pair.substringAfter('=', missingDelimiterValue = "").formDecode()
                key to value
            }
    }

    private fun formEncode(value: String): String {
        val bytes = value.encodeToByteArray()
        val builder = StringBuilder()
        for (byte in bytes) {
            val int = byte.toInt() and 0xff
            val char = int.toChar()
            when {
                char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-_.~" -> builder.append(char)
                char == ' ' -> builder.append('+')
                else -> {
                    builder.append('%')
                    builder.append(HEX[int shr 4])
                    builder.append(HEX[int and 0x0f])
                }
            }
        }
        return builder.toString()
    }

    private fun String.formDecode(): String {
        val bytes = mutableListOf<Byte>()
        var index = 0
        while (index < length) {
            when (val char = this[index]) {
                '+' -> {
                    bytes += ' '.code.toByte()
                    index++
                }
                '%' -> {
                    val hex = substring(index + 1, (index + 3).coerceAtMost(length))
                    val decoded = if (hex.length == 2) hex.toIntOrNull(16) else null
                    if (decoded != null) {
                        bytes += decoded.toByte()
                        index += 3
                    } else {
                        bytes += char.code.toByte()
                        index++
                    }
                }
                else -> {
                    bytes += char.code.toByte()
                    index++
                }
            }
        }
        return bytes.toByteArray().decodeToString()
    }

    private companion object {
        private const val HEX = "0123456789ABCDEF"
    }
}
