package com.xjtu.toolbox.shared.features

import com.xjtu.toolbox.shared.auth.AccessMode
import com.xjtu.toolbox.shared.auth.AuthContext
import com.xjtu.toolbox.shared.auth.InMemorySiteSession
import com.xjtu.toolbox.shared.auth.SiteKey
import com.xjtu.toolbox.shared.network.HttpClient
import com.xjtu.toolbox.shared.network.HttpMethod
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.HttpResponse
import com.xjtu.toolbox.shared.parsing.currentTermJson
import com.xjtu.toolbox.shared.parsing.examJson
import com.xjtu.toolbox.shared.parsing.gradeJson
import com.xjtu.toolbox.shared.parsing.scheduleJson
import com.xjtu.toolbox.shared.parsing.textbookSessionHtml
import com.xjtu.toolbox.shared.parsing.textbookTableHtml
import com.xjtu.toolbox.shared.session.BackendSiteSession
import com.xjtu.toolbox.shared.session.SessionBackend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JwappAcademicRepositoryTest {
    @Test
    fun scheduleLoadsCurrentTermThenCoursesAndCachesTerm() = runTest {
        val client = QueueAcademicHttpClient(
            HttpResponse(code = 200, finalUrl = "$BASE/dqxnxq.do", bodyText = currentTermJson),
            HttpResponse(code = 200, finalUrl = "$BASE/xskcb.do", bodyText = scheduleJson),
            HttpResponse(code = 200, finalUrl = "$BASE/wdksap.do", bodyText = examJson),
        )
        val session = httpSession(SiteKey.SCHEDULE, client)
        val repository = JwappScheduleRepository(baseUrl = BASE)

        val courses = repository.courses(session, termCode = null)
        val exams = repository.exams(session, termCode = null)

        assertEquals(2, courses.size)
        assertEquals(1, exams.size)
        assertEquals(
            listOf(
                "$BASE/jwapp/sys/wdkb/modules/jshkcb/dqxnxq.do",
                "$BASE/jwapp/sys/wdkb/modules/xskcb/xskcb.do",
                "$BASE/jwapp/sys/studentWdksapApp/modules/wdksap/wdksap.do",
            ),
            client.requests.map { it.url },
        )
        assertEquals(HttpMethod.POST, client.requests[0].method)
        assertEquals("application/json, text/javascript, */*; q=0.01", client.requests[0].headers["Accept"])
        assertEquals(null, client.requests[0].headers["Origin"])
        assertEquals("XMLHttpRequest", client.requests[0].headers["X-Requested-With"])
        assertEquals("$BASE/jwapp/sys/wdkb/*default/index.do", client.requests[0].headers["Referer"])
        assertEquals("application/json, text/javascript, */*; q=0.01", client.requests[1].headers["Accept"])
        assertEquals(null, client.requests[1].headers["Origin"])
        assertEquals("XMLHttpRequest", client.requests[1].headers["X-Requested-With"])
        assertEquals("$BASE/jwapp/sys/wdkb/*default/index.do", client.requests[1].headers["Referer"])
        assertEquals("XNXQDM=2025-2026-1", client.requests[1].body?.decodeToString())
    }

    @Test
    fun scheduleLoadsTextbooksFromInlineReportHtml() = runTest {
        val client = QueueAcademicHttpClient(
            HttpResponse(code = 200, finalUrl = "$BASE/jwapp/sys/frReport2/show.do", bodyText = textbookTableHtml),
        )
        val repository = JwappScheduleRepository(baseUrl = BASE)

        val textbooks = repository.textbooks(httpSession(SiteKey.SCHEDULE, client), termCode = "2025-2026-1")

        assertEquals(2, textbooks.size)
        assertEquals("高等数学", textbooks.first().courseName)
        assertEquals("$BASE/jwapp/sys/frReport2/show.do", client.requests.single().url)
        assertEquals(true, client.requests.single().body?.decodeToString()?.contains("xh"))
        assertEquals(true, client.requests.single().body?.decodeToString()?.contains("2025-2026-1"))
    }

    @Test
    fun scheduleLoadsTextbooksFromBoundedPageContent() = runTest {
        val manyPages = textbookSessionHtml.replace("FR._p.reportTotalPage = 3", "FR._p.reportTotalPage = 99")
        val client = QueueAcademicHttpClient(
            HttpResponse(code = 200, finalUrl = "$BASE/jwapp/sys/frReport2/show.do", bodyText = manyPages),
            HttpResponse(code = 200, finalUrl = "$BASE/page_content", bodyText = textbookTableHtml),
            HttpResponse(code = 200, finalUrl = "$BASE/page_content", bodyText = textbookTableHtml),
        )
        val repository = JwappScheduleRepository(
            baseUrl = BASE,
            maxTextbookPages = 2,
        )

        val textbooks = repository.textbooks(httpSession(SiteKey.SCHEDULE, client), termCode = "2025-2026-1")

        assertEquals(2, textbooks.size)
        assertEquals(3, client.requests.size)
        assertEquals(true, client.requests[1].url.contains("op=page_content"))
        assertEquals(true, client.requests[2].url.contains("pn=2"))
    }

    @Test
    fun scheduleUsesProvidedTermWithoutCurrentTermLookup() = runTest {
        val client = QueueAcademicHttpClient(
            HttpResponse(code = 200, finalUrl = "$BASE/xskcb.do", bodyText = scheduleJson),
        )
        val repository = JwappScheduleRepository(baseUrl = BASE)

        repository.courses(httpSession(SiteKey.SCHEDULE, client), termCode = "2025-2026-1")

        assertEquals(1, client.requests.size)
        assertEquals("XNXQDM=2025-2026-1", client.requests.single().body?.decodeToString())
    }

    @Test
    fun gradeInitializesModuleAndLoadsBoundedPages() = runTest {
        val client = QueueAcademicHttpClient(
            HttpResponse(code = 200, finalUrl = "$GRADE_BASE/*default/index.do"),
            HttpResponse(code = 200, finalUrl = "$GRADE_BASE/modules/cjcx/xscjcx.do", bodyText = gradeJson),
        )
        val repository = JwappGradeRepository(
            moduleBaseUrl = GRADE_BASE,
            pageSize = 100,
            maxPages = 3,
        )

        val grades = repository.grades(httpSession(SiteKey.GRADE, client), termCode = "2025-2026-1")

        assertEquals(2, grades.size)
        assertEquals(
            listOf(
                "$GRADE_BASE/*default/index.do",
                "$GRADE_BASE/modules/cjcx/xscjcx.do",
            ),
            client.requests.map { it.url },
        )
        assertEquals("application/json, text/javascript, */*; q=0.01", client.requests[1].headers["Accept"])
        assertEquals(null, client.requests[1].headers["Origin"])
        assertEquals("$GRADE_BASE/*default/index.do", client.requests[1].headers["Referer"])
        assertEquals("XMLHttpRequest", client.requests[1].headers["X-Requested-With"])
        assertEquals(true, client.requests[1].body?.decodeToString()?.contains("pageNumber=1"))
    }

    @Test
    fun gradeDoesNotLoopBeyondMaxPages() = runTest {
        val pageWithLargeTotal = gradeJson.replace("\"totalSize\": 3", "\"totalSize\": 999")
        val client = QueueAcademicHttpClient(
            HttpResponse(code = 200, finalUrl = "$GRADE_BASE/*default/index.do"),
            HttpResponse(code = 200, finalUrl = "$GRADE_BASE/modules/cjcx/xscjcx.do", bodyText = pageWithLargeTotal),
            HttpResponse(code = 200, finalUrl = "$GRADE_BASE/modules/cjcx/xscjcx.do", bodyText = pageWithLargeTotal),
        )
        val repository = JwappGradeRepository(
            moduleBaseUrl = GRADE_BASE,
            pageSize = 2,
            maxPages = 2,
        )

        repository.grades(httpSession(SiteKey.GRADE, client), termCode = null)

        assertEquals(3, client.requests.size)
        assertEquals(true, client.requests[1].body?.decodeToString()?.contains("pageNumber=1"))
        assertEquals(true, client.requests[2].body?.decodeToString()?.contains("pageNumber=2"))
    }

    @Test
    fun repositoriesRejectNonHttpSession() = runTest {
        assertFailsWith<IllegalStateException> {
            JwappScheduleRepository(baseUrl = BASE).courses(InMemorySiteSession(SiteKey.SCHEDULE, AccessMode.NORMAL), null)
        }
        assertFailsWith<IllegalStateException> {
            JwappGradeRepository(moduleBaseUrl = GRADE_BASE).grades(InMemorySiteSession(SiteKey.GRADE, AccessMode.NORMAL), null)
        }
    }

    private suspend fun httpSession(site: SiteKey, client: QueueAcademicHttpClient): BackendSiteSession =
        BackendSiteSession(
            site = site,
            backend = SessionBackend.normal(client),
        ).also {
            it.ensureAuthenticated(AuthContext(username = "3124000000"))
        }

    private companion object {
        private const val BASE = "https://jwxt.xjtu.edu.cn"
        private const val GRADE_BASE = "https://jwxt.xjtu.edu.cn/jwapp/sys/cjcx"
    }
}

private class QueueAcademicHttpClient(
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
