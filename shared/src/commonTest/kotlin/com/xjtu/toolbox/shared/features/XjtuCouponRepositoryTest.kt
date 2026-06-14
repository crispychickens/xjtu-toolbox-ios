package com.xjtu.toolbox.shared.features

import com.xjtu.toolbox.shared.auth.AccessMode
import com.xjtu.toolbox.shared.auth.AuthContext
import com.xjtu.toolbox.shared.auth.InMemorySiteSession
import com.xjtu.toolbox.shared.auth.SiteKey
import com.xjtu.toolbox.shared.auth.SiteVerificationRequiredException
import com.xjtu.toolbox.shared.network.HttpClient
import com.xjtu.toolbox.shared.network.HttpMethod
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.HttpResponse
import com.xjtu.toolbox.shared.parsing.CouponJsonParser
import com.xjtu.toolbox.shared.session.BackendSiteSession
import com.xjtu.toolbox.shared.session.SessionBackend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class XjtuCouponRepositoryTest {
    @Test
    fun parserNormalizesMoneyAndImageUrl() {
        val page = CouponJsonParser.parsePage(couponJson, filter = CouponFilter.USABLE, imageBaseUrl = BASE)

        val coupon = page.records.single()
        assertEquals(CouponFilter.USABLE, page.filter)
        assertEquals(1, page.total)
        assertEquals("校庆加餐券", coupon.voucherName)
        assertEquals(500L, coupon.amountFen)
        assertEquals(500L, coupon.leftAmountFen)
        assertEquals(5.0, coupon.leftAmountYuan)
        assertEquals(1, coupon.leftCount)
        assertEquals("$BASE/voucher-bucket/2026/04/04/banner.png", coupon.imageUrl)
    }

    @Test
    fun repositoryPostsFilterBodyThroughCouponSession() = runTest {
        val client = QueueCouponHttpClient(
            HttpResponse(code = 200, finalUrl = "$BASE/app/voucher/query.page.list", bodyText = couponJson),
        )
        val repository = XjtuCouponRepository(baseUrl = BASE)

        val page = repository.coupons(couponSession(client), filter = CouponFilter.USABLE, page = 2, pageSize = 20)

        val request = client.requests.single()
        val body = request.body?.decodeToString().orEmpty()
        assertEquals("$BASE/app/voucher/query.page.list", request.url)
        assertEquals(HttpMethod.POST, request.method)
        assertEquals("application/json;charset=UTF-8", request.headers["Content-Type"])
        assertEquals("jwt-0", request.headers["Authorization"])
        assertTrue(body.contains("\"pageNum\": 2"))
        assertTrue(body.contains("\"pageSize\": 20"))
        assertTrue(body.contains("\"status\": \"1\""))
        assertTrue(body.contains("\"count\": \"1\""))
        assertEquals(1, page.total)
    }

    @Test
    fun repositoryRejectsAuthHtmlAsSiteVerificationRequired() = runTest {
        val repository = XjtuCouponRepository(baseUrl = BASE)

        assertFailsWith<SiteVerificationRequiredException> {
            repository.coupons(
                couponSession(
                    QueueCouponHttpClient(
                        HttpResponse(
                            code = 200,
                            finalUrl = "https://login.xjtu.edu.cn/cas/login",
                            bodyText = "<html><form id=\"loginForm\"></form></html>",
                        ),
                    ),
                ),
                filter = CouponFilter.USABLE,
                page = 1,
                pageSize = 20,
            )
        }
    }

    @Test
    fun rejectsNonHttpOrWrongSiteSession() = runTest {
        val repository = XjtuCouponRepository(baseUrl = BASE)

        assertFailsWith<IllegalStateException> {
            repository.coupons(InMemorySiteSession(SiteKey.COUPON, AccessMode.NORMAL), CouponFilter.USABLE, 1, 20)
        }
        assertFailsWith<IllegalArgumentException> {
            repository.coupons(httpSession(SiteKey.LIBRARY, QueueCouponHttpClient()), CouponFilter.USABLE, 1, 20)
        }
    }

    private suspend fun couponSession(client: QueueCouponHttpClient): BackendSiteSession =
        httpSession(SiteKey.COUPON, client)

    private suspend fun httpSession(site: SiteKey, client: QueueCouponHttpClient): BackendSiteSession =
        BackendSiteSession(
            site = site,
            backend = SessionBackend.normal(client),
            authenticate = { mapOf("Authorization" to "jwt-0") },
        ).also {
            it.ensureAuthenticated(AuthContext(username = "3124000000"))
        }

    private companion object {
        private const val BASE = "https://egc.xjtu.edu.cn"
    }
}

private class QueueCouponHttpClient(
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

private val couponJson = """
    {
      "code": 200,
      "data": {
        "records": [
          {
            "sendId": "2821777",
            "showCardId": "F14025FB-C3B9-438C-A356-C4C97802E39B",
            "startDate": "2026-04-04",
            "endDate": "2026-04-14 23:59",
            "pic": "/voucher-bucket/2026/04/04/banner.png",
            "tranamt": "500",
            "ltranamt": "500",
            "voucherName": "校庆加餐券",
            "typeName": "加餐券",
            "lknumber": "1"
          }
        ],
        "total": 1
      }
    }
""".trimIndent()
