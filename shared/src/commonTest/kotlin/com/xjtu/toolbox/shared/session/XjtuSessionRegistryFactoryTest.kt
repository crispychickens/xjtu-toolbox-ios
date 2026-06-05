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
import kotlin.test.assertIs
import kotlin.test.assertTrue

class XjtuSessionRegistryFactoryTest {
    @Test
    fun firstReleaseRegistryProvidesScheduleGradeAndCampusCardSessions() = runTest {
        val directClient = QueueRegistryHttpClient(
            HttpResponse(code = 200, finalUrl = "$JWAPP_BASE/jwapp/sys/wdkb/*default/index.do"),
            HttpResponse(code = 200, finalUrl = "$MOBILE_JWAPP_BASE/app/index?token=token-0"),
            HttpResponse(code = 200, finalUrl = "$NCARD_BASE/plat/?ticket=ST-0"),
            HttpResponse(code = 200, finalUrl = "$NCARD_BASE/token", bodyText = """{"access_token":"jwt-0"}"""),
        )
        val registry = XjtuSessionRegistryFactory.firstReleaseSessionRegistry(
            directBackend = SessionBackend.normal(directClient),
        )

        val schedule = registry.session(SiteKey.SCHEDULE, AccessMode.NORMAL)
        val grade = registry.session(SiteKey.GRADE, AccessMode.NORMAL)
        val campusCard = registry.session(SiteKey.CAMPUS_CARD, AccessMode.NORMAL)
        schedule.ensureAuthenticated(AuthContext(username = "3124000000"))
        grade.ensureAuthenticated(AuthContext(username = "3124000000"))
        campusCard.ensureAuthenticated(AuthContext(username = "3124000000"))

        assertEquals(SiteKey.SCHEDULE, schedule.site)
        assertEquals(SiteKey.GRADE, grade.site)
        assertEquals(SiteKey.CAMPUS_CARD, campusCard.site)
        assertEquals(4, directClient.requests.size)
    }

    @Test
    fun campusCardRegistryBuildsDirectSessionForAutoAndNormalModes() = runTest {
        val directClient = QueueRegistryHttpClient(
            HttpResponse(code = 200, finalUrl = "$NCARD_BASE/plat/?ticket=ST-1"),
            HttpResponse(code = 200, finalUrl = "$NCARD_BASE/token", bodyText = """{"access_token":"jwt-1"}"""),
        )
        val registry = XjtuSessionRegistryFactory.campusCardSessionRegistry(
            directBackend = SessionBackend.normal(directClient),
        )

        val session = registry.session(SiteKey.CAMPUS_CARD, AccessMode.AUTO)
        session.ensureAuthenticated(AuthContext(username = "3124000000"))

        assertIs<BackendSiteSession>(session)
        assertEquals(AccessMode.NORMAL, session.accessMode)
        assertEquals(NcardAuthConfig().loginUrl, directClient.requests.first().url)
    }

    @Test
    fun campusCardRegistryUsesWebVpnBackendWhenModeIsWebVpn() = runTest {
        val directClient = QueueRegistryHttpClient()
        val webVpnClient = QueueRegistryHttpClient(
            HttpResponse(code = 200, finalUrl = "$NCARD_BASE/plat/?ticket=ST-2"),
            HttpResponse(code = 200, finalUrl = "$NCARD_BASE/token", bodyText = """{"access_token":"jwt-2"}"""),
        )
        val registry = XjtuSessionRegistryFactory.campusCardSessionRegistry(
            directBackend = SessionBackend.normal(directClient),
            webVpnBackend = SessionBackend.webvpn(webVpnClient),
        )

        val session = registry.session(SiteKey.CAMPUS_CARD, AccessMode.WEBVPN)
        session.ensureAuthenticated(AuthContext(username = "3124000000"))

        assertEquals(AccessMode.WEBVPN, session.accessMode)
        assertTrue(WebVpnUrlCodec().isVpnUrl(webVpnClient.requests.first().url))
        assertEquals(0, directClient.requests.size)
    }

    private companion object {
        private const val JWAPP_BASE = "https://jwxt.xjtu.edu.cn"
        private const val MOBILE_JWAPP_BASE = "https://jwapp.xjtu.edu.cn"
        private const val NCARD_BASE = "https://ncard.xjtu.edu.cn"
    }
}

private class QueueRegistryHttpClient(
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
