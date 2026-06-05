package com.xjtu.toolbox.shared.features

import com.xjtu.toolbox.shared.network.HttpClient
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.parsing.EmptyRoomCdnParser

class EmptyRoomCdnRepository(
    private val httpClient: HttpClient,
    private val baseUrl: String = "https://gh-release.xjtutoolbox.com/",
) : EmptyRoomRepository {
    override suspend fun rooms(campus: String, date: String, sections: IntRange): List<EmptyRoom> {
        require(campus.isNotBlank()) { "campus is required" }
        require(date.isNotBlank()) { "date is required" }
        require(sections.first <= sections.last) { "at least one section is required" }

        val response = httpClient.execute(
            HttpRequest(url = emptyRoomUrl(date)),
        )
        if (response.code == 404) {
            return emptyList()
        }
        if (!response.isSuccessful) {
            error("空闲教室 CDN 请求失败: HTTP ${response.code}")
        }
        return EmptyRoomCdnParser.parse(response.bodyText, campus, sections)
    }

    private fun emptyRoomUrl(date: String): String =
        "${baseUrl.trimEnd('/')}/?file=static/empty_room/$date.json"
}
