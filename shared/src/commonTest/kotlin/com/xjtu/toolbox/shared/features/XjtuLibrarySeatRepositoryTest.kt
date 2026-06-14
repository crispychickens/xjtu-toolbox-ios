package com.xjtu.toolbox.shared.features

import com.xjtu.toolbox.shared.auth.AccessMode
import com.xjtu.toolbox.shared.auth.AuthContext
import com.xjtu.toolbox.shared.auth.InMemorySiteSession
import com.xjtu.toolbox.shared.auth.SiteKey
import com.xjtu.toolbox.shared.auth.SiteVerificationRequiredException
import com.xjtu.toolbox.shared.network.HttpClient
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.HttpResponse
import com.xjtu.toolbox.shared.parsing.LibrarySeatParser
import com.xjtu.toolbox.shared.session.BackendSiteSession
import com.xjtu.toolbox.shared.session.SessionBackend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class XjtuLibrarySeatRepositoryTest {
    @Test
    fun parserKeepsKnownAreasAndSortsRecommendedAreasByAvailabilityRate() {
        val snapshot = LibrarySeatParser.parseSeatSnapshot(
            json = qseatJson,
            selectedAreaCode = "north2east",
            bookingHtml = bookingHtml,
        )

        assertEquals("north2east", snapshot.selectedAreaCode)
        assertEquals(13, snapshot.areas.size)
        assertEquals(120, snapshot.areas.first { it.code == "north2east" }.total)
        assertEquals(42, snapshot.areas.first { it.code == "north2east" }.available)
        assertEquals(listOf("D002", "D021", "E101"), snapshot.seats.map { it.seatId })
        assertEquals(listOf(true, true, false), snapshot.seats.map { it.available })
        assertEquals("北楼四层中间", snapshot.recommendedAreas.first().name)
        assertEquals("D021", snapshot.myBooking?.seatId)
        assertEquals("已预约", snapshot.myBooking?.statusText)
    }

    @Test
    fun repositoryFetchesSnapshotThroughLibrarySession() = runTest {
        val client = QueueLibraryHttpClient(
            HttpResponse(code = 200, finalUrl = "$BASE/qseat?sp=north2east", bodyText = qseatJson),
            HttpResponse(code = 200, finalUrl = "$BASE/my/", bodyText = bookingHtml),
        )
        val repository = XjtuLibrarySeatRepository(baseUrl = BASE)

        val snapshot = repository.snapshot(librarySession(client), areaCode = "north2east")

        assertEquals(listOf("$BASE/qseat?sp=north2east", "$BASE/my/"), client.requests.map { it.url })
        assertEquals("XMLHttpRequest", client.requests.first().headers["X-Requested-With"])
        assertEquals("D021", snapshot.myBooking?.seatId)
    }

    @Test
    fun repositoryRejectsAuthRedirectAsSiteVerificationRequired() = runTest {
        val repository = XjtuLibrarySeatRepository(baseUrl = BASE)

        assertFailsWith<SiteVerificationRequiredException> {
            repository.snapshot(
                librarySession(
                    QueueLibraryHttpClient(
                        HttpResponse(
                            code = 200,
                            finalUrl = "https://login.xjtu.edu.cn/cas/login",
                            bodyText = "<form id=\"loginForm\"><input name=\"execution\"></form>",
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun bookSeatUsesSwapEndpointWhenExistingBookingIsDetected() = runTest {
        val client = QueueLibraryHttpClient(
            HttpResponse(code = 200, finalUrl = "$BASE/seat/?kid=D022&sp=north2east", bodyText = "<p>已有预约，是否换座</p>"),
            HttpResponse(code = 200, finalUrl = "$BASE/my/", bodyText = "<p>成功换座</p>"),
        )
        val repository = XjtuLibrarySeatRepository(baseUrl = BASE)

        val result = repository.bookSeat(librarySession(client), seatId = "d022", areaCode = "north2east")

        assertEquals(true, result.success)
        assertEquals(listOf("$BASE/seat/?kid=D022&sp=north2east", "$BASE/updateseat/?kid=D022&sp=north2east"), client.requests.map { it.url })
    }

    @Test
    fun rejectsNonHttpOrWrongSiteSession() = runTest {
        val repository = XjtuLibrarySeatRepository(baseUrl = BASE)

        assertFailsWith<IllegalStateException> {
            repository.snapshot(InMemorySiteSession(SiteKey.LIBRARY, AccessMode.NORMAL))
        }
        assertFailsWith<IllegalArgumentException> {
            repository.snapshot(httpSession(SiteKey.CAMPUS_CARD, QueueLibraryHttpClient()))
        }
    }

    private suspend fun librarySession(client: QueueLibraryHttpClient): BackendSiteSession =
        httpSession(SiteKey.LIBRARY, client)

    private suspend fun httpSession(site: SiteKey, client: QueueLibraryHttpClient): BackendSiteSession =
        BackendSiteSession(
            site = site,
            backend = SessionBackend.normal(client),
            authenticate = { emptyMap() },
        ).also {
            it.ensureAuthenticated(AuthContext(username = "3124000000"))
        }

    private companion object {
        private const val BASE = "http://rg.lib.xjtu.edu.cn:8086"
    }
}

private class QueueLibraryHttpClient(
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

private val qseatJson = """
    {
      "scount": {
        "north2east": [120, 42],
        "north4middle": [144, 56],
        "north4southeast": [136, 0],
        "unknown-area": [99, 99]
      },
      "seat": {
        "D021": 0,
        "E101": 1,
        "D002": 0,
        "bad": 0
      }
    }
""".trimIndent()

private val bookingHtml = """
    <html>
      <body>
        <div>座位号 D021</div>
        <div>区域 北楼二层外文库（东）</div>
        <div>预约状态：已预约</div>
      </body>
    </html>
""".trimIndent()
