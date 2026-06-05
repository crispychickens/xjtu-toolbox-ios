package com.xjtu.toolbox.shared.features

import com.xjtu.toolbox.shared.auth.AccessMode
import com.xjtu.toolbox.shared.auth.AuthContext
import com.xjtu.toolbox.shared.auth.InMemorySiteSession
import com.xjtu.toolbox.shared.auth.SiteKey
import com.xjtu.toolbox.shared.network.HttpClient
import com.xjtu.toolbox.shared.network.HttpMethod
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.HttpResponse
import com.xjtu.toolbox.shared.session.BackendSiteSession
import com.xjtu.toolbox.shared.session.SessionBackend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MobileJwappGradeRepositoryTest {
    @Test
    fun fetchesTermScoreWithMobileJwappTokenSession() = runTest {
        val client = QueueMobileGradeHttpClient(
            HttpResponse(code = 200, finalUrl = "$BASE/api/biz/v410/score/termScore", bodyText = termScoreJson),
        )
        val session = BackendSiteSession(
            site = SiteKey.GRADE,
            backend = SessionBackend.normal(client),
            authenticate = { mapOf("Authorization" to "token-1", "User-Agent" to "Mozilla/5.0") },
        ).also {
            it.ensureAuthenticated(AuthContext(username = "3124000000"))
        }
        val repository = MobileJwappGradeRepository(baseUrl = BASE)

        val grades = repository.grades(session, termCode = null)

        assertEquals(2, grades.size)
        assertEquals("高等数学", grades[0].courseName)
        assertEquals("92", grades[0].score)
        assertEquals(3.0, grades[0].credit)
        assertEquals(3.9, grades[0].gradePoint)
        assertEquals("大学体育", grades[1].courseName)
        assertEquals(4.0, grades[1].gradePoint)
        assertEquals("$BASE/api/biz/v410/score/termScore", client.requests.single().url)
        assertEquals(HttpMethod.POST, client.requests.single().method)
        assertEquals("token-1", client.requests.single().headers["Authorization"])
        assertEquals("Mozilla/5.0", client.requests.single().headers["User-Agent"])
        assertEquals("application/json", client.requests.single().headers["Content-Type"])
        assertEquals("""{"termCode":"*"}""", client.requests.single().body?.decodeToString())
    }

    @Test
    fun sendsSpecificTermCodeWhenProvided() = runTest {
        val client = QueueMobileGradeHttpClient(
            HttpResponse(code = 200, finalUrl = "$BASE/api/biz/v410/score/termScore", bodyText = termScoreJson),
        )
        val session = BackendSiteSession(
            site = SiteKey.GRADE,
            backend = SessionBackend.normal(client),
            authenticate = { mapOf("Authorization" to "token-1") },
        ).also {
            it.ensureAuthenticated(AuthContext(username = "3124000000"))
        }

        MobileJwappGradeRepository(baseUrl = BASE).grades(session, termCode = "2025-2026-1")

        assertEquals("""{"termCode":"2025-2026-1"}""", client.requests.single().body?.decodeToString())
    }

    @Test
    fun rejectsMobileJwappServiceErrorAndMissingTermList() = runTest {
        val repository = MobileJwappGradeRepository(baseUrl = BASE)

        assertFailsWith<IllegalStateException> {
            repository.grades(
                sessionFor("""{"code":401,"msg":"authentication error"}"""),
                termCode = null,
            )
        }

        assertFailsWith<IllegalStateException> {
            repository.grades(
                sessionFor("""{"code":200,"data":{}}"""),
                termCode = null,
            )
        }
    }

    @Test
    fun rejectsNonHttpSession() = runTest {
        assertFailsWith<IllegalStateException> {
            MobileJwappGradeRepository(baseUrl = BASE).grades(
                InMemorySiteSession(SiteKey.GRADE, AccessMode.NORMAL),
                termCode = null,
            )
        }
    }

    private suspend fun sessionFor(bodyText: String): BackendSiteSession {
        val client = QueueMobileGradeHttpClient(
            HttpResponse(code = 200, finalUrl = "$BASE/api/biz/v410/score/termScore", bodyText = bodyText),
        )
        return BackendSiteSession(
            site = SiteKey.GRADE,
            backend = SessionBackend.normal(client),
            authenticate = { mapOf("Authorization" to "token-1") },
        ).also {
            it.ensureAuthenticated(AuthContext(username = "3124000000"))
        }
    }

    private companion object {
        private const val BASE = "https://jwapp.xjtu.edu.cn"
    }
}

private class QueueMobileGradeHttpClient(
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

private val termScoreJson = """
    {
      "code": 200,
      "data": {
        "termScoreList": [
          {
            "termCode": "2025-2026-1",
            "scoreList": [
              {
                "courseName": "高等数学",
                "score": "92",
                "coursePoint": 3.0,
                "gpa": 3.9,
                "passFlag": true
              },
              {
                "courseName": "大学体育",
                "score": "优秀",
                "coursePoint": 1.0,
                "gpa": 0,
                "passFlag": true
              }
            ]
          }
        ]
      }
    }
""".trimIndent()
