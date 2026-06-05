package com.xjtu.toolbox.shared.features

import com.xjtu.toolbox.shared.auth.HttpSiteSession
import com.xjtu.toolbox.shared.auth.SiteKey
import com.xjtu.toolbox.shared.auth.SiteSession
import com.xjtu.toolbox.shared.network.HttpMethod
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.parsing.FineReportTextbookParser
import com.xjtu.toolbox.shared.parsing.JwappScheduleParser

class JwappScheduleRepository(
    private val baseUrl: String = "https://jwxt.xjtu.edu.cn",
    private val maxTextbookPages: Int = 5,
) : ScheduleRepository {
    private var cachedCurrentTerm: String? = null

    override suspend fun courses(session: SiteSession, termCode: String?): List<CourseItem> {
        val httpSession = session.asHttpSiteSession()
        val term = termCode ?: currentTerm(httpSession)
        val response = httpSession.execute(
            formPost(
                url = "$baseUrl/jwapp/sys/wdkb/modules/xskcb/xskcb.do",
                fields = listOf("XNXQDM" to term),
                headers = emapAjaxHeaders("$baseUrl/jwapp/sys/wdkb/*default/index.do"),
            ),
        )
        if (!response.isSuccessful) {
            error("课表查询失败: HTTP ${response.code}")
        }
        return JwappScheduleParser.parseCourses(response.bodyText)
    }

    override suspend fun exams(session: SiteSession, termCode: String?): List<ExamItem> {
        val httpSession = session.asHttpSiteSession()
        val term = termCode ?: currentTerm(httpSession)
        val response = httpSession.execute(
            formPost(
                url = "$baseUrl/jwapp/sys/studentWdksapApp/modules/wdksap/wdksap.do",
                fields = listOf(
                    "XNXQDM" to term,
                    "*order" to "-KSRQ,-KSSJMS",
                ),
                headers = emapAjaxHeaders("$baseUrl/jwapp/sys/studentWdksapApp/*default/index.do"),
            ),
        )
        if (!response.isSuccessful) {
            error("考试安排查询失败: HTTP ${response.code}")
        }
        return JwappScheduleParser.parseExams(response.bodyText)
    }

    override suspend fun textbooks(session: SiteSession, termCode: String?): List<TextbookItem> {
        require(maxTextbookPages in 1..20) { "maxTextbookPages must be in 1..20" }
        val httpSession = session.asHttpSiteSession()
        val studentId = httpSession.username?.takeIf { it.isNotBlank() }
            ?: error("教材查询需要已认证学号")
        val term = termCode ?: currentTerm(httpSession)
        val frUrl = "$baseUrl/jwapp/sys/frReport2/show.do"
        val init = httpSession.execute(
            formPost(
                url = frUrl,
                fields = listOf(
                    "reportlets" to "[{'xh':'$studentId','xnxqdm':'$term','reportlet':'jcgl/wdjc.cpt'}]",
                    "__cumulatepagenumber__" to "false",
                ),
                headers = mapOf("Referer" to "$frUrl?__cumulatepagenumber__=false"),
            ),
        )
        if (!init.isSuccessful) error("教材报表初始化失败: HTTP ${init.code}")
        val inline = FineReportTextbookParser.parse(init.bodyText)
        if (inline.isNotEmpty()) return inline.sortedBy { if (it.hasSubstantiveTextbook) 0 else 1 }

        val sessionId = FineReportTextbookParser.extractSessionId(init.bodyText)
            ?: error("教材报表初始化失败，未获取到 sessionID")
        val firstPage = fetchTextbookPage(httpSession, frUrl, sessionId, page = 1)
        val totalPages = maxOf(
            FineReportTextbookParser.extractTotalPages(init.bodyText),
            FineReportTextbookParser.extractTotalPages(firstPage),
        ).coerceAtMost(maxTextbookPages)
        val all = mutableListOf<TextbookItem>()
        all += FineReportTextbookParser.parse(firstPage)
        for (page in 2..totalPages) {
            all += FineReportTextbookParser.parse(fetchTextbookPage(httpSession, frUrl, sessionId, page))
        }
        return all.distinctBy { "${it.courseName}|${it.textbookName}|${it.isbn}" }
            .sortedBy { if (it.hasSubstantiveTextbook) 0 else 1 }
    }

    private suspend fun currentTerm(session: HttpSiteSession): String {
        cachedCurrentTerm?.let { return it }
        val response = session.execute(
            formPost(
                url = "$baseUrl/jwapp/sys/wdkb/modules/jshkcb/dqxnxq.do",
                fields = emptyList(),
                headers = emapAjaxHeaders("$baseUrl/jwapp/sys/wdkb/*default/index.do"),
            ),
        )
        if (!response.isSuccessful) {
            error("当前学期查询失败: HTTP ${response.code}")
        }
        return JwappScheduleParser.parseCurrentTerm(response.bodyText).also {
            cachedCurrentTerm = it
        }
    }

    private fun SiteSession.asHttpSiteSession(): HttpSiteSession =
        (this as? HttpSiteSession)
            ?.also { require(it.site == SiteKey.SCHEDULE || it.site == SiteKey.JWAPP || it.site == SiteKey.JWXT) { "课表 repository requires schedule/JWAPP/JWXT session" } }
            ?: error("课表 repository requires an HttpSiteSession")

    private suspend fun fetchTextbookPage(
        session: HttpSiteSession,
        frUrl: String,
        sessionId: String,
        page: Int,
    ): String {
        val response = session.execute(
            HttpRequest(
                url = "$frUrl?__boxModel__=true&op=page_content&sessionID=$sessionId&pn=$page",
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to frUrl,
                ),
            ),
        )
        if (!response.isSuccessful) error("教材报表内容页请求失败: HTTP ${response.code}")
        return response.bodyText
    }

    private fun formPost(
        url: String,
        fields: List<Pair<String, String>>,
        headers: Map<String, String> = emptyMap(),
    ): HttpRequest =
        HttpRequest(
            url = url,
            method = HttpMethod.POST,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8") + headers,
            body = fields.joinToString("&") { (name, value) -> "${formEncode(name)}=${formEncode(value)}" }
                .encodeToByteArray(),
        )

    private fun emapAjaxHeaders(referer: String): Map<String, String> =
        mapOf(
            "Accept" to AJAX_ACCEPT,
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to referer,
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
        private const val HEX = "0123456789ABCDEF"
        private const val AJAX_ACCEPT = "application/json, text/javascript, */*; q=0.01"
    }
}
