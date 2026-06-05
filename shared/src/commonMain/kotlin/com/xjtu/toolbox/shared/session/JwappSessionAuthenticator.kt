package com.xjtu.toolbox.shared.session

import com.xjtu.toolbox.shared.auth.AuthContext
import com.xjtu.toolbox.shared.auth.CasHtmlParser
import com.xjtu.toolbox.shared.auth.SiteVerificationContext
import com.xjtu.toolbox.shared.auth.SiteVerificationRequiredException
import com.xjtu.toolbox.shared.auth.SiteKey
import com.xjtu.toolbox.shared.network.HttpClient
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.HttpResponse
import com.xjtu.toolbox.shared.webvpn.WebVpnUrlCodec

data class JwappAuthConfig(
    val baseHost: String = "jwxt.xjtu.edu.cn",
    val baseUrl: String = "https://jwxt.xjtu.edu.cn",
    val homeEntryPath: String = "/jwapp/sys/homeapp/index.do",
    val scheduleEntryPath: String = "/jwapp/sys/wdkb/*default/index.do",
    val gradeEntryPath: String = "/jwapp/sys/cjcx/*default/index.do",
)

class JwappSessionAuthenticator(
    private val site: SiteKey,
    private val httpClient: HttpClient,
    private val config: JwappAuthConfig = JwappAuthConfig(),
    private val webVpnUrlCodec: WebVpnUrlCodec = WebVpnUrlCodec(),
) {
    suspend fun authenticate(context: AuthContext): Map<String, String> {
        require(context.username.isNotBlank()) { "username is required" }
        val response = httpClient.execute(
            HttpRequest(
                url = entryUrl(),
                headers = mapOf("Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"),
            ),
        )
        validateEntryResponse(response)
        response.extractSameHostLocationHref()?.let { followUrl ->
            val follow = httpClient.execute(
                HttpRequest(
                    url = followUrl,
                    headers = mapOf("Referer" to response.finalUrl),
                ),
            )
            validateEntryResponse(follow)
        }
        return emptyMap()
    }

    private fun entryUrl(): String =
        when (site) {
            SiteKey.SCHEDULE,
            SiteKey.JWXT,
            -> config.baseUrl + config.homeEntryPath
            SiteKey.JWAPP -> config.baseUrl + config.scheduleEntryPath
            SiteKey.GRADE -> config.baseUrl + config.gradeEntryPath
            else -> error("JWAPP authenticator does not support $site")
        }

    private fun validateEntryResponse(response: HttpResponse) {
        if (!response.isSuccessful) {
            error("JWAPP 入口请求失败: HTTP ${response.code}")
        }
        if (response.isCasOrSafetyPage()) {
            throw SiteVerificationRequiredException(
                site = site,
                message = "JWAPP 需要重新完成 CAS/Safety Verify 验证",
                verificationContext = response.verificationContext(),
            )
        }
        val finalUrl = response.normalizedFinalUrl()
        if (!finalUrl.contains(config.baseHost, ignoreCase = true)) {
            throw SiteVerificationRequiredException(
                site = site,
                message = "JWAPP SSO 未进入教务应用，需要重新登录",
                verificationContext = response.verificationContext(),
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

    private fun HttpResponse.normalizedFinalUrl(): String =
        if (webVpnUrlCodec.isVpnUrl(finalUrl)) webVpnUrlCodec.fromVpnUrl(finalUrl) ?: finalUrl else finalUrl

    private fun HttpResponse.verificationContext(): SiteVerificationContext =
        SiteVerificationContext(finalUrl = finalUrl, bodyText = bodyText)

    private fun HttpResponse.extractSameHostLocationHref(): String? {
        val href = LOCATION_HREF_REGEX.find(bodyText)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val resolved = when {
            href.startsWith("http://", ignoreCase = true) ||
                href.startsWith("https://", ignoreCase = true) -> href
            href.startsWith("/") -> config.baseUrl + href
            else -> return null
        }
        return resolved.takeIf { it.contains(config.baseHost, ignoreCase = true) }
    }

    private companion object {
        private val LOCATION_HREF_REGEX = Regex("""location\.href\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
    }
}
