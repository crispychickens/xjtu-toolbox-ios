package com.xjtu.toolbox.shared.session

import com.xjtu.toolbox.shared.auth.AuthContext
import com.xjtu.toolbox.shared.auth.SiteKey
import com.xjtu.toolbox.shared.auth.SiteVerificationRequiredException
import com.xjtu.toolbox.shared.network.HttpClient
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.HttpResponse
import com.xjtu.toolbox.shared.webvpn.WebVpnUrlCodec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MobileJwappSessionAuthenticatorTest {
    @Test
    fun extractsMobileJwappTokenAsAuthorizationHeader() = runTest {
        val client = QueueMobileJwappAuthHttpClient(
            HttpResponse(code = 200, finalUrl = "$BASE/app/index?token=token%2D1&state=1234"),
        )
        val authenticator = MobileJwappSessionAuthenticator(client)

        val headers = authenticator.authenticate(AuthContext(username = "3124000000"))

        assertEquals("token-1", headers["Authorization"])
        assertEquals(true, headers["User-Agent"]?.contains("Mozilla") == true)
        assertEquals(MobileJwappAuthConfig().authorizeUrl, client.requests.single().url)
        assertEquals(true, client.requests.single().url.contains("redirectUri=http://jwapp.xjtu.edu.cn/app/index"))
    }

    @Test
    fun extractsMobileJwappTokenFromWebVpnFinalUrl() = runTest {
        val vpnUrl = WebVpnUrlCodec().toVpnUrl("$BASE/app/index?token=token-2")
        val client = QueueMobileJwappAuthHttpClient(
            HttpResponse(code = 200, finalUrl = vpnUrl),
        )
        val authenticator = MobileJwappSessionAuthenticator(client)

        val headers = authenticator.authenticate(AuthContext(username = "3124000000"))

        assertEquals("token-2", headers["Authorization"])
    }

    @Test
    fun extractsMobileJwappTokenFromFragmentStyleFinalUrl() = runTest {
        val client = QueueMobileJwappAuthHttpClient(
            HttpResponse(code = 302, finalUrl = "http://jwapp.xjtu.edu.cn/app/index#/home?token=token%2Dfragment&state=1234"),
        )
        val authenticator = MobileJwappSessionAuthenticator(client)

        val headers = authenticator.authenticate(AuthContext(username = "3124000000"))

        assertEquals("token-fragment", headers["Authorization"])
    }

    @Test
    fun followsHttpsCasCallbackWithoutRequestingRegisteredHttpRedirect() = runTest {
        val callbackUrl = "https://login.xjtu.edu.cn/cas/oauth2.0/callbackAuthorize?ticket=ST-1"
        val casAuthorizeUrl = "https://login.xjtu.edu.cn/cas/oauth2.0/authorize?response_type=code&client_id=1370"
        val client = QueueMobileJwappAuthHttpClient(
            HttpResponse(code = 302, finalUrl = callbackUrl),
            HttpResponse(code = 302, finalUrl = casAuthorizeUrl),
            HttpResponse(code = 302, finalUrl = "http://jwapp.xjtu.edu.cn/app/index?token=token%2D3&state=1234"),
        )
        val authenticator = MobileJwappSessionAuthenticator(client)

        val headers = authenticator.authenticate(AuthContext(username = "3124000000"))

        assertEquals("token-3", headers["Authorization"])
        assertEquals(MobileJwappAuthConfig().authorizeUrl, client.requests[0].url)
        assertEquals(callbackUrl, client.requests[1].url)
        assertEquals(casAuthorizeUrl, client.requests[2].url)
        assertEquals(3, client.requests.size)
    }

    @Test
    fun exchangesRegisteredHttpCodeCallbackThroughHttps() = runTest {
        val registeredCallback = "http://jwapp.xjtu.edu.cn/app/index?code=code-1&state=1234"
        val client = QueueMobileJwappAuthHttpClient(
            HttpResponse(code = 302, finalUrl = registeredCallback),
            HttpResponse(code = 200, finalUrl = "https://jwapp.xjtu.edu.cn/app/index?token=token%2D4&state=1234"),
        )
        val authenticator = MobileJwappSessionAuthenticator(client)

        val headers = authenticator.authenticate(AuthContext(username = "3124000000"))

        assertEquals("token-4", headers["Authorization"])
        assertEquals(MobileJwappAuthConfig().authorizeUrl, client.requests[0].url)
        assertEquals("https://jwapp.xjtu.edu.cn/app/index?code=code-1&state=1234", client.requests[1].url)
        assertEquals(2, client.requests.size)
    }

    @Test
    fun surfacesCasLoginAsGradeSiteVerification() = runTest {
        val client = QueueMobileJwappAuthHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/login?service=jwapp",
                bodyText = """<html><form action="/cas/login"><input name="execution" value="e1"></form></html>""",
            ),
        )
        val authenticator = MobileJwappSessionAuthenticator(client)

        val failure = assertFailsWith<SiteVerificationRequiredException> {
            authenticator.authenticate(AuthContext(username = "3124000000"))
        }

        assertEquals(SiteKey.GRADE, failure.site)
        assertEquals("https://login.xjtu.edu.cn/cas/login?service=jwapp", failure.verificationContext?.finalUrl)
    }

    @Test
    fun rejectsOauthResponseWithoutToken() = runTest {
        val client = QueueMobileJwappAuthHttpClient(
            HttpResponse(code = 200, finalUrl = "$BASE/app/index"),
        )
        val authenticator = MobileJwappSessionAuthenticator(client)

        assertFailsWith<IllegalStateException> {
            authenticator.authenticate(AuthContext(username = "3124000000"))
        }
    }

    private companion object {
        private const val BASE = "https://jwapp.xjtu.edu.cn"
    }
}

private class QueueMobileJwappAuthHttpClient(
    vararg responses: HttpResponse,
) : HttpClient {
    private val responses = ArrayDeque(responses.toList())
    val requests = mutableListOf<HttpRequest>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return responses.removeFirstOrNull()
            ?: error("No response queued for ${request.url}")
    }
}
