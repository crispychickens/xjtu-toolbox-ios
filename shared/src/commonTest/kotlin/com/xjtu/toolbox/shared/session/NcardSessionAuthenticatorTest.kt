package com.xjtu.toolbox.shared.session

import com.xjtu.toolbox.shared.auth.AuthContext
import com.xjtu.toolbox.shared.auth.SiteKey
import com.xjtu.toolbox.shared.auth.SiteVerificationRequiredException
import com.xjtu.toolbox.shared.network.HttpClient
import com.xjtu.toolbox.shared.network.HttpMethod
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.HttpResponse
import com.xjtu.toolbox.shared.webvpn.WebVpnUrlCodec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NcardSessionAuthenticatorTest {
    @Test
    fun exchangesNcardTicketForBearerHeader() = runTest {
        val client = QueueAuthHttpClient(
            HttpResponse(code = 200, finalUrl = "$BASE/plat/?ticket=ST%2D1"),
            HttpResponse(code = 200, finalUrl = "$BASE/berserker-auth/oauth/token", bodyText = """{"access_token":"jwt-1"}"""),
        )
        val authenticator = NcardSessionAuthenticator(client)

        val headers = authenticator.authenticate(AuthContext(username = "3124000000"))

        assertEquals("bearer jwt-1", headers["synjones-auth"])
        assertEquals("h5", headers["synAccessSource"])
        assertEquals(listOf(NcardAuthConfig().loginUrl, NcardAuthConfig().tokenUrl), client.requests.map { it.url })
        assertEquals(HttpMethod.POST, client.requests[1].method)
        assertEquals(NcardAuthConfig().tokenBasicAuth, client.requests[1].headers["Authorization"])
        assertEquals(
            "username=ST-1&password=ST-1&grant_type=password&scope=all&loginFrom=h5&logintype=sso&device_token=h5&synAccessSource=h5",
            client.requests[1].body?.decodeToString(),
        )
    }

    @Test
    fun retriesLoginUrlOnceWhenFirstRedirectDoesNotContainTicket() = runTest {
        val client = QueueAuthHttpClient(
            HttpResponse(code = 200, finalUrl = "https://org.xjtu.edu.cn/openplatform"),
            HttpResponse(code = 200, finalUrl = "$BASE/plat/?ticket=ST-2"),
            HttpResponse(code = 200, finalUrl = "$BASE/berserker-auth/oauth/token", bodyText = """{"access_token":"jwt-2"}"""),
        )
        val authenticator = NcardSessionAuthenticator(client)

        val headers = authenticator.authenticate(AuthContext(username = "3124000000"))

        assertEquals("bearer jwt-2", headers["synjones-auth"])
        assertEquals(3, client.requests.size)
        assertEquals(NcardAuthConfig().loginUrl, client.requests[0].url)
        assertEquals(NcardAuthConfig().loginUrl, client.requests[1].url)
    }

    @Test
    fun extractsTicketFromWebVpnFinalUrl() = runTest {
        val vpnUrl = WebVpnUrlCodec().toVpnUrl("$BASE/plat/?ticket=ST-3")
        val client = QueueAuthHttpClient(
            HttpResponse(code = 200, finalUrl = vpnUrl),
            HttpResponse(code = 200, finalUrl = "$BASE/berserker-auth/oauth/token", bodyText = """{"access_token":"jwt-3"}"""),
        )
        val authenticator = NcardSessionAuthenticator(client)

        val headers = authenticator.authenticate(AuthContext(username = "3124000000"))

        assertEquals("bearer jwt-3", headers["synjones-auth"])
    }

    @Test
    fun stopsAfterBoundedTicketAttempts() = runTest {
        val client = QueueAuthHttpClient(
            HttpResponse(code = 200, finalUrl = "https://org.xjtu.edu.cn/openplatform"),
            HttpResponse(code = 200, finalUrl = "https://org.xjtu.edu.cn/openplatform"),
        )
        val authenticator = NcardSessionAuthenticator(client)

        assertFailsWith<IllegalStateException> {
            authenticator.authenticate(AuthContext(username = "3124000000"))
        }
        assertEquals(2, client.requests.size)
    }

    @Test
    fun surfacesCasLoginPageAsSiteVerificationRequired() = runTest {
        val client = QueueAuthHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/login?service=ncard",
                bodyText = """<html><form action="/cas/login"><input name="execution" value="e1"></form></html>""",
            ),
        )
        val authenticator = NcardSessionAuthenticator(client)

        val failure = assertFailsWith<SiteVerificationRequiredException> {
            authenticator.authenticate(AuthContext(username = "3124000000"))
        }

        assertEquals(SiteKey.CAMPUS_CARD, failure.site)
        assertEquals("https://login.xjtu.edu.cn/cas/login?service=ncard", failure.verificationContext?.finalUrl)
        assertEquals(1, client.requests.size)
    }

    @Test
    fun surfacesSafetyVerifyPageAsSiteVerificationRequired() = runTest {
        val client = QueueAuthHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/sec/index",
                bodyText = """<html><title>Safety Verify</title><input name="secState" value="state-1"></html>""",
            ),
        )
        val authenticator = NcardSessionAuthenticator(client)

        val failure = assertFailsWith<SiteVerificationRequiredException> {
            authenticator.authenticate(AuthContext(username = "3124000000"))
        }

        assertEquals(SiteKey.CAMPUS_CARD, failure.site)
        assertEquals(1, client.requests.size)
    }

    @Test
    fun rejectsTokenResponseWithoutAccessToken() = runTest {
        val client = QueueAuthHttpClient(
            HttpResponse(code = 200, finalUrl = "$BASE/plat/?ticket=ST-4"),
            HttpResponse(code = 200, finalUrl = "$BASE/berserker-auth/oauth/token", bodyText = """{"error":"invalid"}"""),
        )
        val authenticator = NcardSessionAuthenticator(client)

        assertFailsWith<IllegalStateException> {
            authenticator.authenticate(AuthContext(username = "3124000000"))
        }
        assertEquals(2, client.requests.size)
    }

    private companion object {
        private const val BASE = "https://ncard.xjtu.edu.cn"
    }
}

private class QueueAuthHttpClient(
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
