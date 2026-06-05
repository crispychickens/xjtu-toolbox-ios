package com.xjtu.toolbox.shared.session

import com.xjtu.toolbox.shared.auth.AccessMode
import com.xjtu.toolbox.shared.auth.AuthContext
import com.xjtu.toolbox.shared.auth.SiteKey
import com.xjtu.toolbox.shared.network.HttpClient
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.HttpResponse
import com.xjtu.toolbox.shared.webvpn.WebVpnUrlCodec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JwappSessionAuthenticatorTest {
    @Test
    fun acceptsExistingJwappSsoSession() = runTest {
        val client = QueueJwappHttpClient(
            HttpResponse(code = 200, finalUrl = "${CONFIG.baseUrl}${CONFIG.homeEntryPath}", bodyText = "<html>教务系统</html>"),
        )
        val authenticator = JwappSessionAuthenticator(SiteKey.SCHEDULE, client, CONFIG)

        val headers = authenticator.authenticate(AuthContext(username = "3124000000"))

        assertEquals(emptyMap(), headers)
        assertEquals(listOf("${CONFIG.baseUrl}${CONFIG.homeEntryPath}"), client.requests.map { it.url })
    }

    @Test
    fun followsJwappLocationHrefWarmup() = runTest {
        val warmupUrl = "${CONFIG.baseUrl}/jwapp/sys/homeapp/home/index.html?av=&contextPath=/jwapp"
        val client = QueueJwappHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "${CONFIG.baseUrl}${CONFIG.homeEntryPath}",
                bodyText = """<script>location.href = '/jwapp/sys/homeapp/home/index.html?av=&contextPath=/jwapp';</script>""",
            ),
            HttpResponse(code = 200, finalUrl = warmupUrl, bodyText = "<html>home</html>"),
        )
        val authenticator = JwappSessionAuthenticator(SiteKey.SCHEDULE, client, CONFIG)

        authenticator.authenticate(AuthContext(username = "3124000000"))

        assertEquals(
            listOf("${CONFIG.baseUrl}${CONFIG.homeEntryPath}", warmupUrl),
            client.requests.map { it.url },
        )
        assertEquals("${CONFIG.baseUrl}${CONFIG.homeEntryPath}", client.requests[1].headers["Referer"])
    }

    @Test
    fun usesGradeEntryForGradeSession() = runTest {
        val client = QueueJwappHttpClient(
            HttpResponse(code = 200, finalUrl = "${CONFIG.baseUrl}${CONFIG.gradeEntryPath}", bodyText = "<html>成绩</html>"),
        )
        val authenticator = JwappSessionAuthenticator(SiteKey.GRADE, client, CONFIG)

        authenticator.authenticate(AuthContext(username = "3124000000"))

        assertEquals("${CONFIG.baseUrl}${CONFIG.gradeEntryPath}", client.requests.single().url)
    }

    @Test
    fun acceptsWebVpnFinalUrlForExistingSsoSession() = runTest {
        val rawUrl = "${CONFIG.baseUrl}${CONFIG.homeEntryPath}"
        val client = QueueJwappHttpClient(
            HttpResponse(code = 200, finalUrl = WebVpnUrlCodec().toVpnUrl(rawUrl), bodyText = "<html>课表</html>"),
        )
        val authenticator = JwappSessionAuthenticator(SiteKey.SCHEDULE, client, CONFIG)

        authenticator.authenticate(AuthContext(username = "3124000000"))

        assertEquals(rawUrl, client.requests.single().url)
    }

    @Test
    fun rejectsCasLoginRedirectWithoutRetrying() = runTest {
        val client = QueueJwappHttpClient(
            HttpResponse(code = 200, finalUrl = "https://login.xjtu.edu.cn/cas/login?service=jwapp"),
        )
        val authenticator = JwappSessionAuthenticator(SiteKey.SCHEDULE, client, CONFIG)

        assertFailsWith<IllegalStateException> {
            authenticator.authenticate(AuthContext(username = "3124000000"))
        }
        assertEquals(1, client.requests.size)
    }

    @Test
    fun rejectsSafetyVerifyHtmlWithoutRetrying() = runTest {
        val client = QueueJwappHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/sec/index",
                bodyText = """<html><title>Safety Verify</title><input name="secState" value="state-1"></html>""",
            ),
        )
        val authenticator = JwappSessionAuthenticator(SiteKey.GRADE, client, CONFIG)

        assertFailsWith<IllegalStateException> {
            authenticator.authenticate(AuthContext(username = "3124000000"))
        }
        assertEquals(1, client.requests.size)
    }

    @Test
    fun failedJwappEntryDoesNotMarkBackendSessionAuthenticated() = runTest {
        val client = QueueJwappHttpClient(
            HttpResponse(code = 200, finalUrl = "https://login.xjtu.edu.cn/cas/login?service=jwapp"),
        )
        val authenticator = JwappSessionAuthenticator(SiteKey.SCHEDULE, client, CONFIG)
        val session = BackendSiteSession(
            site = SiteKey.SCHEDULE,
            backend = SessionBackend(AccessMode.NORMAL, client, InMemoryCookieStore()),
            authenticate = authenticator::authenticate,
        )

        assertFailsWith<IllegalStateException> {
            session.ensureAuthenticated(AuthContext(username = "3124000000"))
        }

        assertFalse(session.isAuthenticated)
        assertEquals(null, session.username)
    }

    @Test
    fun registryScheduleSessionFailsClosedOnCasHtml() = runTest {
        val client = QueueJwappHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://login.xjtu.edu.cn/cas/login",
                bodyText = """<html><form action="/cas/login"><input name="execution" value="e1"></form></html>""",
            ),
        )
        val registry = XjtuSessionRegistryFactory.firstReleaseSessionRegistry(
            directBackend = SessionBackend.normal(client),
        )
        val session = registry.session(SiteKey.SCHEDULE, AccessMode.NORMAL)

        assertFailsWith<IllegalStateException> {
            session.ensureAuthenticated(AuthContext(username = "3124000000"))
        }

        assertFalse(session.isAuthenticated)
        assertTrue(client.requests.single().url.endsWith(CONFIG.homeEntryPath))
    }

    private companion object {
        private val CONFIG = JwappAuthConfig()
    }
}

private class QueueJwappHttpClient(
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
