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
import com.xjtu.toolbox.shared.parsing.JsonValue
import com.xjtu.toolbox.shared.parsing.asObjectOrNull
import com.xjtu.toolbox.shared.parsing.asStringOrNull
import com.xjtu.toolbox.shared.webvpn.WebVpnUrlCodec

private const val COUPON_AUTHORIZE_URL =
    "https://login.xjtu.edu.cn/cas/oauth2.0/authorize?response_type=code&client_id=1596&redirect_uri=https%3A%2F%2Forg.xjtu.edu.cn%2Fopenplatform%2Foauth%2Fauthorizesw%3Fredirect_uri%3Dbase64aHR0cHM6Ly9lZ2MueGp0dS5lZHUuY24vcGFnZS9jYXMvcmVjZWl2ZUNhcy5odG1sP3ZlcnNpb249U0FGVF9WRVJTSU9O&state=1995"

data class CouponAuthConfig(
    val authorizeUrl: String = COUPON_AUTHORIZE_URL,
    val baseUrl: String = "https://egc.xjtu.edu.cn",
    val receiveUrl: String = "https://egc.xjtu.edu.cn/page/cas/receiveCas.html?version=SAFT_VERSION",
)

class CouponSessionAuthenticator(
    private val httpClient: HttpClient,
    private val config: CouponAuthConfig = CouponAuthConfig(),
    private val webVpnUrlCodec: WebVpnUrlCodec = WebVpnUrlCodec(),
) {
    suspend fun authenticate(context: AuthContext): Map<String, String> {
        require(context.username.isNotBlank()) { "username is required" }
        val callback = followCasOauthRedirects(executeBrowserRequest(config.authorizeUrl))
        if (callback.isAuthPage()) {
            throw SiteVerificationRequiredException(
                site = SiteKey.COUPON,
                message = "加餐券需要重新完成 CAS/Safety Verify 验证",
                verificationContext = SiteVerificationContext(callback.finalUrl, callback.bodyText),
            )
        }
        val params = extractCallbackParams(normalizedUrl(callback.finalUrl))
            ?: extractCallbackParams(callback.bodyText)
            ?: throw SiteVerificationRequiredException(
                site = SiteKey.COUPON,
                message = "加餐券 SSO 未返回授权码，需要重新登录",
                verificationContext = SiteVerificationContext(callback.finalUrl, callback.bodyText),
            )
        val token = exchangeCodeForToken(params)
        return mapOf(
            "Authorization" to token,
            "User-Agent" to BROWSER_UA,
        )
    }

    private suspend fun exchangeCodeForToken(params: CouponCallbackParams): String {
        val response = httpClient.execute(
            HttpRequest(
                url = "${config.baseUrl}/sso/login?code=${urlEncode(params.code)}" +
                    "&userType=${urlEncode(params.userType)}" +
                    "&employeeNo=${urlEncode(params.employeeNo)}",
                method = HttpMethod.POST,
                headers = mapOf(
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
                    "Content-Type" to "application/json;charset=UTF-8",
                    "Origin" to config.baseUrl,
                    "Referer" to config.receiveUrl,
                    "User-Agent" to BROWSER_UA,
                    "X-Requested-With" to "XMLHttpRequest",
                ),
                body = """{"json":true}""".encodeToByteArray(),
            ),
        )
        if (!response.isSuccessful) error("加餐券令牌交换失败: HTTP ${response.code}")
        return response.headers["Authorization"]?.normalizeToken()
            ?: extractToken(response.bodyText)
            ?: error("加餐券令牌交换响应缺少 Authorization")
    }

    private suspend fun executeBrowserRequest(url: String): HttpResponse =
        httpClient.execute(
            HttpRequest(
                url = url,
                headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
                    "User-Agent" to BROWSER_UA,
                ),
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

    private fun HttpResponse.isAuthPage(): Boolean {
        val final = normalizedUrl(finalUrl)
        return final.contains("login.xjtu.edu.cn/cas", ignoreCase = true) ||
            final.contains("/cas/login", ignoreCase = true) ||
            bodyText.contains("/cas/login", ignoreCase = true) ||
            bodyText.contains("login.xjtu.edu.cn/cas", ignoreCase = true) ||
            CasHtmlParser.isSafetyVerifyPage(bodyText)
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

    private fun extractCallbackParams(text: String): CouponCallbackParams? {
        if (text.isBlank()) return null
        val decoded = runCatching { text.urlDecode() }.getOrDefault(text)
        val code = decoded.findParam("code") ?: return null
        return CouponCallbackParams(
            code = code,
            userType = decoded.findParam("userType").orEmpty(),
            employeeNo = decoded.findParam("employeeNo").orEmpty(),
        )
    }

    private fun String.findParam(key: String): String? {
        val encoded = Regex("""[?&#]$key=([^&#"']*)""").find(this)?.groupValues?.getOrNull(1)
        if (!encoded.isNullOrBlank()) return encoded.urlDecode()
        return Regex(""""$key"\s*:\s*"([^"]+)"""").find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractToken(text: String): String? {
        val root = runCatching { JsonParser(text).parse() }.getOrNull()
        if (root != null) findTokenInJson(root)?.let { return it }
        return Regex("""eyJ[A-Za-z0-9_\-.]+""").find(text)?.value?.normalizeToken()
    }

    private fun findTokenInJson(value: JsonValue?): String? {
        if (value == null) return null
        val primitive = value.asStringOrNull()?.normalizeToken()
        if (!primitive.isNullOrBlank() && primitive.startsWith("eyJ")) return primitive
        value.asObjectOrNull()?.let { obj ->
            listOf("Authorization", "authorization", "token", "accessToken", "access_token", "jwt", "data").forEach { key ->
                findTokenInJson(obj[key])?.let { return it }
            }
            obj.values.forEach { nested -> findTokenInJson(nested)?.let { return it } }
        }
        return null
    }

    private data class CouponCallbackParams(
        val code: String,
        val userType: String,
        val employeeNo: String,
    )

    private companion object {
        private const val MAX_CAS_OAUTH_REDIRECTS = 4
        private const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36 Edg/136.0.0.0"
    }
}

private fun String.normalizeToken(): String =
    trim().removePrefix("Bearer ").removePrefix("bearer ")

private fun urlEncode(value: String): String {
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
                if (index + 2 >= length) {
                    output.append(char)
                    index++
                } else {
                    val hex = substring(index + 1, index + 3).toIntOrNull(16)
                    if (hex == null) {
                        output.append(char)
                        index++
                    } else {
                        bytes += hex.toByte()
                        index += 3
                    }
                }
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

private const val HEX = "0123456789ABCDEF"
