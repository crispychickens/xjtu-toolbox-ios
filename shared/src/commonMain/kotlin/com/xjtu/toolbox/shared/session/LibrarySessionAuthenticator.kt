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

class LibrarySessionAuthenticator(
    private val httpClient: HttpClient,
    private val baseHost: String = "rg.lib.xjtu.edu.cn",
    private val baseUrl: String = "http://rg.lib.xjtu.edu.cn:8086",
    private val webVpnUrlCodec: WebVpnUrlCodec = WebVpnUrlCodec(),
) {
    suspend fun authenticate(context: AuthContext): Map<String, String> {
        require(context.username.isNotBlank()) { "username is required" }
        val response = httpClient.execute(
            HttpRequest(
                url = "$baseUrl/seat/",
                headers = mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"),
            ),
        )
        validateSeatEntry(response)
        return emptyMap()
    }

    private fun validateSeatEntry(response: HttpResponse) {
        if (!response.isSuccessful) error("图书馆座位入口请求失败: HTTP ${response.code}")
        if (response.isCasOrSafetyPage()) {
            throw SiteVerificationRequiredException(
                site = SiteKey.LIBRARY,
                message = "图书馆座位需要重新完成 CAS/Safety Verify 验证",
                verificationContext = SiteVerificationContext(response.finalUrl, response.bodyText),
            )
        }
        val finalUrl = if (webVpnUrlCodec.isVpnUrl(response.finalUrl)) {
            webVpnUrlCodec.fromVpnUrl(response.finalUrl) ?: response.finalUrl
        } else {
            response.finalUrl
        }
        val looksReady = response.bodyText.contains("btn-group", ignoreCase = true) ||
            response.bodyText.contains("tab-select", ignoreCase = true) ||
            response.bodyText.contains("seat", ignoreCase = true)
        if (!finalUrl.contains(baseHost, ignoreCase = true) || !looksReady) {
            throw SiteVerificationRequiredException(
                site = SiteKey.LIBRARY,
                message = "图书馆座位 SSO 未进入座位系统，需要重新登录",
                verificationContext = SiteVerificationContext(response.finalUrl, response.bodyText),
            )
        }
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
}
