package com.xjtu.toolbox.shared.features

import com.xjtu.toolbox.shared.auth.AccessMode
import com.xjtu.toolbox.shared.auth.AuthContext
import com.xjtu.toolbox.shared.auth.InMemorySiteSession
import com.xjtu.toolbox.shared.auth.SiteKey
import com.xjtu.toolbox.shared.network.HttpClient
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.HttpResponse
import com.xjtu.toolbox.shared.session.BackendSiteSession
import com.xjtu.toolbox.shared.session.SessionBackend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NcardCampusCardRepositoryTest {
    @Test
    fun fetchesCardInfoThroughHttpSiteSession() = runTest {
        val client = QueueNcardHttpClient(
            HttpResponse(code = 200, finalUrl = "$BASE/berserker-app/ykt/tsm/queryCard", bodyText = cardInfoJson),
        )
        val session = campusCardSession(client)
        val repository = NcardCampusCardRepository(baseUrl = BASE)

        val info = repository.cardInfo(session)

        assertEquals(42.5, info.balanceYuan)
        assertEquals("$BASE/berserker-app/ykt/tsm/queryCard?synAccessSource=h5", client.requests.single().url)
        assertEquals("bearer token-1", client.requests.single().headers["synjones-auth"])
        assertEquals("h5", client.requests.single().headers["synAccessSource"])
    }

    @Test
    fun fetchesOneTransactionPageWithoutEagerParallelPagination() = runTest {
        val client = QueueNcardHttpClient(
            HttpResponse(code = 200, finalUrl = "$BASE/turnover", bodyText = transactionJson),
        )
        val session = campusCardSession(client)
        val repository = NcardCampusCardRepository(
            baseUrl = BASE,
            timeFrom = "2026-05-01",
            timeTo = "2026-05-20",
        )

        val transactions = repository.transactions(session, page = 2, pageSize = 20)

        assertEquals(2, transactions.size)
        assertEquals(TransactionKind.EXPENSE, transactions[0].kind)
        assertEquals(TransactionKind.INCOME, transactions[1].kind)
        assertEquals(1, client.requests.size)
        assertEquals(
            "$BASE/berserker-search/search/personal/turnover?size=20&current=2&synAccessSource=h5&timeFrom=2026-05-01&timeTo=2026-05-20",
            client.requests.single().url,
        )
    }

    @Test
    fun rejectsNonHttpSiteSession() = runTest {
        val repository = NcardCampusCardRepository(baseUrl = BASE)

        assertFailsWith<IllegalStateException> {
            repository.cardInfo(InMemorySiteSession(SiteKey.CAMPUS_CARD, AccessMode.NORMAL))
        }
    }

    @Test
    fun rejectsWrongSiteSession() = runTest {
        val repository = NcardCampusCardRepository(baseUrl = BASE)

        assertFailsWith<IllegalArgumentException> {
            repository.cardInfo(httpSession(SiteKey.JWXT, QueueNcardHttpClient()))
        }
    }

    private suspend fun campusCardSession(client: QueueNcardHttpClient): BackendSiteSession =
        httpSession(SiteKey.CAMPUS_CARD, client)

    private suspend fun httpSession(site: SiteKey, client: QueueNcardHttpClient): BackendSiteSession =
        BackendSiteSession(
            site = site,
            backend = SessionBackend.normal(client),
            authenticate = {
                mapOf("synjones-auth" to "bearer token-1")
            },
        ).also {
            it.ensureAuthenticated(AuthContext(username = "3124000000"))
        }

    private companion object {
        private const val BASE = "https://ncard.xjtu.edu.cn"
    }
}

private class QueueNcardHttpClient(
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

private val cardInfoJson = """
    {
      "code": 200,
      "message": "ok",
      "data": {
        "card": [
          {
            "elec_accamt": 4250,
            "unsettle_amount": 0,
            "barflag": 0,
            "freezeflag": 0
          }
        ]
      }
    }
""".trimIndent()

private val transactionJson = """
    {
      "code": 200,
      "message": "ok",
      "data": {
        "total": 2,
        "records": [
          {
            "jndatetimeStr": "2026-05-20 12:00:00",
            "toMerchant": "康桥苑",
            "tranamt": 1200,
            "icon": "qrcode",
            "turnoverType": "",
            "resume": "付款码消费-康桥苑"
          },
          {
            "jndatetimeStr": "2026-05-20 13:00:00",
            "toMerchant": "充值",
            "tranamt": 10000,
            "icon": "recharge",
            "turnoverType": "充值",
            "resume": "线上充值"
          }
        ]
      }
    }
""".trimIndent()
