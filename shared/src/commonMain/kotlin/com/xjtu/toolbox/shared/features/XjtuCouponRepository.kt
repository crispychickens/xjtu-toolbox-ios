package com.xjtu.toolbox.shared.features

import com.xjtu.toolbox.shared.auth.CasHtmlParser
import com.xjtu.toolbox.shared.auth.HttpSiteSession
import com.xjtu.toolbox.shared.auth.SiteKey
import com.xjtu.toolbox.shared.auth.SiteVerificationContext
import com.xjtu.toolbox.shared.auth.SiteVerificationRequiredException
import com.xjtu.toolbox.shared.auth.SiteSession
import com.xjtu.toolbox.shared.network.HttpMethod
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.HttpResponse
import com.xjtu.toolbox.shared.parsing.CouponJsonParser
import com.xjtu.toolbox.shared.parsing.JsonParser
import com.xjtu.toolbox.shared.parsing.asIntOrNull
import com.xjtu.toolbox.shared.parsing.asObjectOrNull
import com.xjtu.toolbox.shared.parsing.asStringOrNull

class XjtuCouponRepository(
    private val baseUrl: String = "https://egc.xjtu.edu.cn",
) : CouponRepository {
    override suspend fun coupons(
        session: SiteSession,
        filter: CouponFilter,
        page: Int,
        pageSize: Int,
    ): CouponPage {
        require(page >= 1) { "page must be >= 1" }
        require(pageSize in 1..100) { "pageSize must be in 1..100" }
        val response = session.asCouponHttpSession().execute(
            couponRequest(
                url = "$baseUrl/app/voucher/query.page.list",
                body = couponPageBody(filter, page, pageSize),
            ),
        )
        validateResponse(response)
        return CouponJsonParser.parsePage(
            json = response.bodyText,
            filter = filter,
            imageBaseUrl = baseUrl,
        )
    }

    private fun couponPageBody(filter: CouponFilter, page: Int, pageSize: Int): String =
        """
        {
          "pageNum": $page,
          "pageSize": $pageSize,
          "obj": {
            "typeId": 4,
            "status": "${filter.status}",
            "count": "${filter.count}",
            "expired": "${filter.expired}"
          },
          "json": true
        }
        """.trimIndent()

    private fun couponRequest(url: String, body: String): HttpRequest =
        HttpRequest(
            url = url,
            method = HttpMethod.POST,
            headers = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Content-Type" to "application/json;charset=UTF-8",
                "Origin" to baseUrl,
                "Referer" to "$baseUrl/page/cas/receiveCas.html?version=SAFT_VERSION",
                "X-Requested-With" to "XMLHttpRequest",
            ),
            body = body.encodeToByteArray(),
        )

    private fun validateResponse(response: HttpResponse) {
        if (!response.isSuccessful) {
            if (response.code == 401 || response.code == 403) {
                throwAuthRequired(response.finalUrl, response.bodyText)
            }
            error("加餐券接口请求失败: HTTP ${response.code}")
        }
        if (response.bodyText.isBlank()) error("加餐券接口返回空数据")
        if (response.isAuthPage()) throwAuthRequired(response.finalUrl, response.bodyText)
        val root = runCatching { JsonParser(response.bodyText).parse().asObjectOrNull() }
            .getOrNull()
            ?: error("加餐券返回了非 JSON 数据: ${response.bodyText.take(80)}")
        val code = root["code"]?.asIntOrNull()
        if (code != null && code != 200) {
            val message = root["msg"]?.asStringOrNull()?.ifBlank { null } ?: "加餐券接口返回错误: $code"
            if (code == 401 || code == 403 || message.contains("令牌")) {
                throwAuthRequired(response.finalUrl, response.bodyText)
            }
            error(message)
        }
    }

    private fun HttpResponse.isAuthPage(): Boolean =
        finalUrl.contains("login.xjtu.edu.cn", ignoreCase = true) ||
            bodyText.contains("<html", ignoreCase = true) ||
            bodyText.contains("/cas/login", ignoreCase = true) ||
            bodyText.contains("login.xjtu.edu.cn/cas", ignoreCase = true) ||
            CasHtmlParser.isSafetyVerifyPage(bodyText)

    private fun throwAuthRequired(finalUrl: String, bodyText: String): Nothing =
        throw SiteVerificationRequiredException(
            site = SiteKey.COUPON,
            message = "加餐券需要补授权",
            verificationContext = SiteVerificationContext(finalUrl = finalUrl, bodyText = bodyText),
        )

    private fun SiteSession.asCouponHttpSession(): HttpSiteSession =
        (this as? HttpSiteSession)
            ?.also { require(it.site == SiteKey.COUPON) { "加餐券 repository requires COUPON session" } }
            ?: error("加餐券 repository requires an HttpSiteSession")
}
