package com.xjtu.toolbox.shared.features

import com.xjtu.toolbox.shared.auth.AuthContext
import com.xjtu.toolbox.shared.auth.SiteKey
import com.xjtu.toolbox.shared.auth.SiteVerificationRequiredException
import com.xjtu.toolbox.shared.network.HttpClient
import com.xjtu.toolbox.shared.network.HttpMethod
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.HttpResponse
import com.xjtu.toolbox.shared.parsing.SchoolCourseParser
import com.xjtu.toolbox.shared.session.BackendSiteSession
import com.xjtu.toolbox.shared.session.SessionBackend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class XjtuSchoolCourseRepositoryTest {
    @Test
    fun parserPreservesCourseSearchFieldsAndPaging() {
        val page = SchoolCourseParser.parsePage(courseJson, "2025-2026-2", page = 2, pageSize = 20)

        val course = page.records.single()
        assertEquals(41, page.total)
        assertEquals(3, page.totalPages)
        assertEquals("MATH1001", course.courseCode)
        assertEquals("高等数学", course.courseName)
        assertEquals("王老师", course.teacher)
        assertEquals(86, course.enrollCount)
        assertEquals(100, course.capacity)
        assertEquals("兴庆校区", course.campus)
    }

    @Test
    fun parserFailsClosedOnServiceErrorsAndMissingRows() {
        assertFailsWith<IllegalStateException> {
            SchoolCourseParser.parsePage(
                """{"code":"1","message":"module changed"}""",
                "2025-2026-2",
                page = 1,
                pageSize = 20,
            )
        }
        assertFailsWith<IllegalStateException> {
            SchoolCourseParser.parsePage(
                """{"code":"0","datas":{"qxfbkccx":{"totalSize":0}}}""",
                "2025-2026-2",
                page = 1,
                pageSize = 20,
            )
        }
    }

    @Test
    fun repositoryInitializesKcbcxAndPostsBoundedSearch() = runTest {
        val client = QueueSchoolCourseHttpClient(
            HttpResponse(code = 200, finalUrl = "$BASE/jwapp/sys/kcbcx/*default/index.do", bodyText = "<html>ok</html>"),
            HttpResponse(code = 200, finalUrl = "$BASE/jwapp/sys/kcbcx/modules/bjkcb/dqxnxq.do", bodyText = currentTermJson),
            HttpResponse(code = 200, finalUrl = "$BASE/jwapp/sys/kcbcx/modules/qxkcb/qxfbkccx.do", bodyText = courseJson),
        )
        val repository = XjtuSchoolCourseRepository(baseUrl = BASE)

        val page = repository.courses(
            session = httpSession(client),
            termCode = null,
            courseName = "高等数学",
            teacher = "王老师",
            campusCode = "1",
            weekday = 3,
            page = 2,
            pageSize = 20,
        )

        assertEquals(3, client.requests.size)
        val request = client.requests.last()
        val body = request.body?.decodeToString().orEmpty()
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("XMLHttpRequest", request.headers["X-Requested-With"])
        assertTrue(body.contains("pageNumber=2"))
        assertTrue(body.contains("pageSize=20"))
        assertTrue(body.contains("SKXQ=3"))
        assertTrue(body.contains("XXXQDM"))
        assertTrue(body.contains("2025-2026-2"))
        assertTrue(body.contains("%E9%AB%98%E7%AD%89%E6%95%B0%E5%AD%A6"))
        assertTrue(body.contains("%E7%8E%8B%E8%80%81%E5%B8%88"))
        assertEquals("2025-2026-2", page.termCode)
    }

    @Test
    fun repositorySurfacesAuthHtmlAsScheduleVerification() = runTest {
        val repository = XjtuSchoolCourseRepository(baseUrl = BASE)
        val failure = assertFailsWith<SiteVerificationRequiredException> {
            repository.courses(
                session = httpSession(
                    QueueSchoolCourseHttpClient(
                        HttpResponse(
                            code = 200,
                            finalUrl = "https://login.xjtu.edu.cn/cas/login",
                            bodyText = "<html>login.xjtu.edu.cn/cas/login</html>",
                        ),
                    ),
                ),
                termCode = null,
                courseName = "",
                teacher = "",
                campusCode = "",
                weekday = 0,
                page = 1,
                pageSize = 20,
            )
        }

        assertEquals(SiteKey.SCHEDULE, failure.site)
    }

    private suspend fun httpSession(client: QueueSchoolCourseHttpClient): BackendSiteSession =
        BackendSiteSession(
            site = SiteKey.SCHEDULE,
            backend = SessionBackend.normal(client),
        ).also {
            it.ensureAuthenticated(AuthContext(username = "3124000000"))
        }

    private companion object {
        private const val BASE = "https://jwxt.xjtu.edu.cn"
    }
}

private class QueueSchoolCourseHttpClient(
    vararg responses: HttpResponse,
) : HttpClient {
    private val responses = ArrayDeque(responses.toList())
    val requests = mutableListOf<HttpRequest>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return responses.removeFirstOrNull() ?: error("No response queued for ${request.url}")
    }
}

private val currentTermJson = """
    {
      "code": "0",
      "datas": {
        "dqxnxq": {
          "rows": [{"DM": "2025-2026-2"}]
        }
      }
    }
""".trimIndent()

private val courseJson = """
    {
      "code": "0",
      "datas": {
        "qxfbkccx": {
          "totalSize": 41,
          "rows": [
            {
              "KCH": "MATH1001",
              "KCM": "高等数学",
              "KXH": "01",
              "SKJS": "王老师",
              "KKDWDM_DISPLAY": "数学与统计学院",
              "XF": "3.0",
              "XKZRS": "86",
              "KRL": "100",
              "YPSJDD": "周三 1-2 节 主楼-101",
              "XXXQDM_DISPLAY": "兴庆校区",
              "JXBID": "class-1",
              "XNXQDM": "2025-2026-2"
            }
          ]
        }
      }
    }
""".trimIndent()
