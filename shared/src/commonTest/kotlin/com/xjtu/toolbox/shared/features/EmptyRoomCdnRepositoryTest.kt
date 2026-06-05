package com.xjtu.toolbox.shared.features

import com.xjtu.toolbox.shared.network.HttpClient
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.HttpResponse
import com.xjtu.toolbox.shared.parsing.EmptyRoomCdnParser
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EmptyRoomCdnRepositoryTest {
    @Test
    fun parserExtractsRoomsFreeForAllRequestedSections() {
        val rooms = EmptyRoomCdnParser.parse(
            json = emptyRoomFixture,
            campus = "兴庆校区",
            requestedSections = 1..2,
        )

        assertEquals(1, rooms.size)
        assertEquals("101", rooms.single().name)
        assertEquals("主楼A", rooms.single().building)
        assertEquals(listOf(1, 2, 4), rooms.single().availableSections)
    }

    @Test
    fun parserRejectsAuthHtml() {
        assertFailsWith<IllegalStateException> {
            EmptyRoomCdnParser.parse(
                json = "<html>login.xjtu.edu.cn/cas/login</html>",
                campus = "兴庆校区",
                requestedSections = 1..2,
            )
        }
    }

    @Test
    fun repositoryFetchesCdnDateJsonAndParsesRooms() = runTest {
        val client = RecordingHttpClient(
            HttpResponse(
                code = 200,
                finalUrl = "https://gh-release.xjtutoolbox.com/?file=static/empty_room/2026-05-20.json",
                bodyText = emptyRoomFixture,
            ),
        )
        val repository = EmptyRoomCdnRepository(client)

        val rooms = repository.rooms("兴庆校区", "2026-05-20", 4..4)

        assertEquals(
            "https://gh-release.xjtutoolbox.com/?file=static/empty_room/2026-05-20.json",
            client.requests.single().url,
        )
        assertEquals(listOf("101", "102"), rooms.map { it.name })
    }

    @Test
    fun repositoryMapsMissingDateToEmptyList() = runTest {
        val repository = EmptyRoomCdnRepository(
            RecordingHttpClient(
                HttpResponse(
                    code = 404,
                    finalUrl = "https://gh-release.xjtutoolbox.com/?file=static/empty_room/2026-05-20.json",
                ),
            ),
        )

        val rooms = repository.rooms("兴庆校区", "2026-05-20", 1..2)

        assertEquals(emptyList(), rooms)
    }
}

private class RecordingHttpClient(
    private val response: HttpResponse,
) : HttpClient {
    val requests = mutableListOf<HttpRequest>()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return response
    }
}

private val emptyRoomFixture = """
    {
      "兴庆校区": {
        "主楼A": {
          "101": {"size": 80, "status": [0, 0, 1, 0]},
          "102": {"size": 60, "status": [1, 0, 1, 0]},
          "null": null,
          "": {"size": 1, "status": [0, 0, 0, 0]}
        },
        "中2": {
          "201": {"size": 100, "status": [0, 1, 1, 1]}
        }
      }
    }
""".trimIndent()
