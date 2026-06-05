package com.xjtu.toolbox.shared.features

import com.xjtu.toolbox.shared.network.HttpClient
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.HttpResponse
import com.xjtu.toolbox.shared.parsing.NoticeHtmlParser
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class XjtuNoticeRepositoryTest {
    @Test
    fun parserExtractsXjtuCmsNoticeItems() {
        val notices = NoticeHtmlParser.parse(
            html = noticeHtml,
            source = "教务处",
            baseUrl = "https://dean.xjtu.edu.cn/jxxx/jxtz2.htm",
        )

        assertEquals(2, notices.size)
        assertEquals("关于做好期末考试安排的通知", notices.first().title)
        assertEquals("https://dean.xjtu.edu.cn/info/1033/12345.htm", notices.first().link)
        assertEquals("2026-05-20", notices.first().date)
    }

    @Test
    fun parserRejectsAuthHtml() {
        assertFailsWith<IllegalStateException> {
            NoticeHtmlParser.parse(
                html = "<html>login.xjtu.edu.cn/cas/login</html>",
                source = "教务处",
                baseUrl = "https://dean.xjtu.edu.cn/jxxx/jxtz2.htm",
            )
        }
    }

    @Test
    fun repositoryFetchesConfiguredSourcesSequentiallyAndSortsByDate() = runTest {
        val client = QueueHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://dean.xjtu.edu.cn/jxxx/jxtz2.htm",
                bodyText = noticeHtml,
            ),
            HttpResponse(
                code = 200,
                finalUrl = "https://gs.xjtu.edu.cn/tzgg.htm",
                bodyText = """
                    <ul>
                      <li><span>2026-05-21</span><a href="/info/1001/999.htm">研究生培养通知</a></li>
                    </ul>
                """.trimIndent(),
            ),
        )
        val repository = XjtuNoticeRepository(
            httpClient = client,
            sources = listOf(
                NoticeSourceConfig("教务处", "https://dean.xjtu.edu.cn/jxxx/jxtz2.htm"),
                NoticeSourceConfig("研究生院", "https://gs.xjtu.edu.cn/tzgg.htm"),
            ),
        )

        val notices = repository.notices(page = 1)

        assertEquals(
            listOf(
                "https://dean.xjtu.edu.cn/jxxx/jxtz2.htm",
                "https://gs.xjtu.edu.cn/tzgg.htm",
            ),
            client.requests.map { it.url },
        )
        assertEquals("研究生培养通知", notices.first().title)
        assertEquals("关于做好期末考试安排的通知", notices[1].title)
    }

    @Test
    fun repositorySkipsFailedSourceAndReturnsReachableSources() = runTest {
        val repository = XjtuNoticeRepository(
            httpClient = QueueHttpClient(
                HttpResponse(code = 500, finalUrl = "https://bad.example.edu"),
                HttpResponse(code = 200, finalUrl = "https://dean.xjtu.edu.cn/jxxx/jxtz2.htm", bodyText = noticeHtml),
            ),
            sources = listOf(
                NoticeSourceConfig("坏来源", "https://bad.example.edu"),
                NoticeSourceConfig("教务处", "https://dean.xjtu.edu.cn/jxxx/jxtz2.htm"),
            ),
        )

        val notices = repository.notices(page = 1)

        assertEquals(2, notices.size)
        assertEquals(setOf("教务处"), notices.map { it.source }.toSet())
    }
}

private class QueueHttpClient(
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

private val noticeHtml = """
    <html>
      <body>
        <ul class="news_list">
          <li>
            <span class="time">2026-05-20</span>
            <a href="../info/1033/12345.htm" title="关于做好期末考试安排的通知">关于做好期末考试安排的通知</a>
          </li>
          <li>
            <span class="time">2026/05/19</span>
            <a href="/info/1033/12344.htm">本科生选课通知</a>
          </li>
          <li><a href="/jxxx/index.htm">导航链接</a></li>
        </ul>
      </body>
    </html>
""".trimIndent()
