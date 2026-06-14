package com.xjtu.toolbox.shared.session

import com.xjtu.toolbox.shared.auth.AuthContext
import com.xjtu.toolbox.shared.auth.SiteVerificationRequiredException
import com.xjtu.toolbox.shared.network.HttpClient
import com.xjtu.toolbox.shared.network.HttpMethod
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.HttpResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CouponSessionAuthenticatorTest {
    @Test
    fun exchangesCouponCallbackForAuthorizationHeader() = runTest {
        val client = QueueCouponAuthHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "$BASE/page/cas/receiveCas.html?code=CODE-1&userType=student&employeeNo=3124000000",
            ),
            HttpResponse(
                code = 200,
                finalUrl = "$BASE/sso/login",
                headers = mapOf("Authorization" to "Bearer eyJcoupon.token"),
                bodyText = """{"code":200}""",
            ),
        )
        val authenticator = CouponSessionAuthenticator(client, config = CouponAuthConfig(authorizeUrl = AUTHORIZE, baseUrl = BASE))

        val headers = authenticator.authenticate(AuthContext(username = "3124000000"))

        assertEquals("eyJcoupon.token", headers["Authorization"])
        assertEquals(AUTHORIZE, client.requests.first().url)
        val exchange = client.requests.last()
        assertEquals(HttpMethod.POST, exchange.method)
        assertTrue(exchange.url.contains("code=CODE-1"))
        assertTrue(exchange.url.contains("userType=student"))
        assertTrue(exchange.url.contains("employeeNo=3124000000"))
    }

    @Test
    fun extractsCouponCallbackParamsFromHtmlBodyAndTokenFromJson() = runTest {
        val client = QueueCouponAuthHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "$BASE/page/cas/receiveCas.html",
                bodyText = """{"code":"CODE-2","userType":"student","employeeNo":"3124000001"}""",
            ),
            HttpResponse(
                code = 200,
                finalUrl = "$BASE/sso/login",
                bodyText = """{"data":{"token":"eyJbody.token"}}""",
            ),
        )
        val authenticator = CouponSessionAuthenticator(client, config = CouponAuthConfig(authorizeUrl = AUTHORIZE, baseUrl = BASE))

        val headers = authenticator.authenticate(AuthContext(username = "3124000001"))

        assertEquals("eyJbody.token", headers["Authorization"])
    }

    @Test
    fun authHtmlRequiresSiteVerification() = runTest {
        val authenticator = CouponSessionAuthenticator(
            QueueCouponAuthHttpClient(
                HttpResponse(
                    code = 200,
                    finalUrl = "https://login.xjtu.edu.cn/cas/login",
                    bodyText = "<form id=\"loginForm\"><input name=\"execution\"></form>",
                ),
            ),
            config = CouponAuthConfig(authorizeUrl = AUTHORIZE, baseUrl = BASE),
        )

        assertFailsWith<SiteVerificationRequiredException> {
            authenticator.authenticate(AuthContext(username = "3124000000"))
        }
    }

    private companion object {
        private const val BASE = "https://egc.xjtu.edu.cn"
        private const val AUTHORIZE = "https://login.xjtu.edu.cn/cas/oauth2.0/authorize?response_type=code"
    }
}

private class QueueCouponAuthHttpClient(
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
