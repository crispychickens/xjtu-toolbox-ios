package com.xjtu.toolbox.shared.features

import com.xjtu.toolbox.shared.auth.HttpSiteSession
import com.xjtu.toolbox.shared.auth.SiteKey
import com.xjtu.toolbox.shared.auth.SiteSession
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.parsing.CampusCardNcardParser

class NcardCampusCardRepository(
    private val baseUrl: String = "https://ncard.xjtu.edu.cn",
    private val timeFrom: String? = null,
    private val timeTo: String? = null,
) : CampusCardRepository {
    override suspend fun cardInfo(session: SiteSession): CampusCardInfo {
        val httpSession = session.asHttpSiteSession()
        val response = httpSession.execute(
            HttpRequest(
                url = "$baseUrl/berserker-app/ykt/tsm/queryCard?synAccessSource=h5",
                headers = ncardHeaders(),
            ),
        )
        if (!response.isSuccessful) {
            error("校园卡信息请求失败: HTTP ${response.code}")
        }
        return CampusCardNcardParser.parseCardInfo(
            json = response.bodyText,
            holderName = "",
        )
    }

    override suspend fun transactions(
        session: SiteSession,
        page: Int,
        pageSize: Int,
    ): List<CampusCardTransaction> {
        require(page >= 1) { "page must be >= 1" }
        require(pageSize in 1..50) { "pageSize must be in 1..50" }

        val httpSession = session.asHttpSiteSession()
        val response = httpSession.execute(
            HttpRequest(
                url = transactionUrl(page = page, pageSize = pageSize),
                headers = ncardHeaders(),
            ),
        )
        if (!response.isSuccessful) {
            error("校园卡流水请求失败: HTTP ${response.code}")
        }
        return CampusCardNcardParser.parseTransactions(response.bodyText).records
    }

    private fun transactionUrl(page: Int, pageSize: Int): String {
        val params = mutableListOf(
            "size=$pageSize",
            "current=$page",
            "synAccessSource=h5",
        )
        if (!timeFrom.isNullOrBlank()) params += "timeFrom=$timeFrom"
        if (!timeTo.isNullOrBlank()) params += "timeTo=$timeTo"
        return "$baseUrl/berserker-search/search/personal/turnover?${params.joinToString("&")}"
    }

    private fun ncardHeaders(): Map<String, String> =
        mapOf(
            "Accept" to "application/json, text/plain, */*",
            "synAccessSource" to "h5",
        )

    private fun SiteSession.asHttpSiteSession(): HttpSiteSession =
        (this as? HttpSiteSession)
            ?.also { require(it.site == SiteKey.CAMPUS_CARD) { "校园卡 repository requires CAMPUS_CARD session" } }
            ?: error("校园卡 repository requires an HttpSiteSession")
}
