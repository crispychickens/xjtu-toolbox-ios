package com.xjtu.toolbox.shared.features

import com.xjtu.toolbox.shared.network.HttpClient
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.parsing.NoticeHtmlParser

data class NoticeSourceConfig(
    val name: String,
    val url: String,
)

object XjtuNoticeSources {
    val JWC = NoticeSourceConfig("教务处", "https://dean.xjtu.edu.cn/jxxx/jxtz2.htm")
    val GRADUATE_SCHOOL = NoticeSourceConfig("研究生院", "https://gs.xjtu.edu.cn/tzgg.htm")
    val STUDENT_AFFAIRS = NoticeSourceConfig("学生处", "https://xsc.xjtu.edu.cn/xgdt/tzgg.htm")

    val firstReleaseDefaults: List<NoticeSourceConfig> =
        listOf(JWC, GRADUATE_SCHOOL, STUDENT_AFFAIRS)
}

class XjtuNoticeRepository(
    private val httpClient: HttpClient,
    private val sources: List<NoticeSourceConfig> = XjtuNoticeSources.firstReleaseDefaults,
    private val maxItemsPerSource: Int = 20,
) : NoticeRepository {
    override suspend fun notices(page: Int): List<NoticeItem> {
        require(page >= 1) { "page must be >= 1" }
        if (sources.isEmpty()) return emptyList()

        return sources.flatMap { source ->
            runCatching { fetchSource(source).take(maxItemsPerSource) }.getOrDefault(emptyList())
        }
            .distinctBy { "${it.source}|${it.title}|${it.link}" }
            .sortedWith(compareByDescending<NoticeItem> { it.date ?: "" }.thenBy { it.source }.thenBy { it.title })
    }

    private suspend fun fetchSource(source: NoticeSourceConfig): List<NoticeItem> {
        val response = httpClient.execute(
            HttpRequest(
                url = source.url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                ),
            ),
        )
        if (response.code == 404 || response.code >= 500) {
            error("通知来源 ${source.name} 请求失败: HTTP ${response.code}")
        }
        if (!response.isSuccessful) return emptyList()
        return NoticeHtmlParser.parse(
            html = response.bodyText,
            source = source.name,
            baseUrl = response.finalUrl.ifBlank { source.url },
        )
    }

    private companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"
    }
}
