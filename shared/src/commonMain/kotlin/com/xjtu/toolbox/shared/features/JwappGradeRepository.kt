package com.xjtu.toolbox.shared.features

import com.xjtu.toolbox.shared.auth.HttpSiteSession
import com.xjtu.toolbox.shared.auth.SiteKey
import com.xjtu.toolbox.shared.auth.SiteSession
import com.xjtu.toolbox.shared.network.HttpMethod
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.parsing.JwappGradeParser

class JwappGradeRepository(
    private val moduleBaseUrl: String = "https://jwxt.xjtu.edu.cn/jwapp/sys/cjcx",
    private val pageSize: Int = 100,
    private val maxPages: Int = 10,
) : GradeRepository {
    private var moduleInitialized = false

    override suspend fun grades(session: SiteSession, termCode: String?): List<GradeItem> {
        require(pageSize in 1..200) { "pageSize must be in 1..200" }
        require(maxPages in 1..50) { "maxPages must be in 1..50" }
        val httpSession = session.asHttpSiteSession()
        ensureModuleSession(httpSession)

        val grades = mutableListOf<GradeItem>()
        for (page in 1..maxPages) {
        val response = httpSession.execute(
            formPost(
                url = "$moduleBaseUrl/modules/cjcx/xscjcx.do",
                fields = listOf(
                        "querySetting" to VALID_SCORE_QUERY,
                        "pageSize" to pageSize.toString(),
                        "pageNumber" to page.toString(),
                ),
                referer = "$moduleBaseUrl/*default/index.do",
            ),
        )
            if (!response.isSuccessful) {
                error("成绩查询失败: HTTP ${response.code}")
            }
            val parsed = JwappGradeParser.parseCjcxPage(response.bodyText, termCode)
            grades += parsed.grades
            if (page * pageSize >= parsed.totalSize || parsed.rawRowCount < pageSize) break
        }
        return grades
    }

    private suspend fun ensureModuleSession(session: HttpSiteSession) {
        if (moduleInitialized) return
        session.execute(HttpRequest(url = "$moduleBaseUrl/*default/index.do"))
        moduleInitialized = true
    }

    private fun SiteSession.asHttpSiteSession(): HttpSiteSession =
        (this as? HttpSiteSession)
            ?.also { require(it.site == SiteKey.GRADE || it.site == SiteKey.JWAPP || it.site == SiteKey.JWXT) { "成绩 repository requires grade/JWAPP/JWXT session" } }
            ?: error("成绩 repository requires an HttpSiteSession")

    private fun formPost(
        url: String,
        fields: List<Pair<String, String>>,
        referer: String,
    ): HttpRequest =
        HttpRequest(
            url = url,
            method = HttpMethod.POST,
            headers = mapOf(
                "Accept" to AJAX_ACCEPT,
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to referer,
            ),
            body = fields.joinToString("&") { (name, value) -> "${formEncode(name)}=${formEncode(value)}" }
                .encodeToByteArray(),
        )

    private fun formEncode(value: String): String {
        val bytes = value.encodeToByteArray()
        val builder = StringBuilder()
        for (byte in bytes) {
            val int = byte.toInt() and 0xff
            val char = int.toChar()
            when {
                char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-_.~" -> builder.append(char)
                char == ' ' -> builder.append('+')
                else -> {
                    builder.append('%')
                    builder.append(HEX[int shr 4])
                    builder.append(HEX[int and 0x0f])
                }
            }
        }
        return builder.toString()
    }

    private companion object {
        private const val AJAX_ACCEPT = "application/json, text/javascript, */*; q=0.01"
        private const val HEX = "0123456789ABCDEF"
        private const val VALID_SCORE_QUERY =
            """[{"name":"SFYX","caption":"是否有效","linkOpt":"AND","builderList":"cbl_m_List","builder":"m_value_equal","value":"1","value_display":"是"}]"""
    }
}
