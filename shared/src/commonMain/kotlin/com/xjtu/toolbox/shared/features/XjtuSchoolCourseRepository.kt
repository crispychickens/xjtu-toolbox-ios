package com.xjtu.toolbox.shared.features

import com.xjtu.toolbox.shared.auth.HttpSiteSession
import com.xjtu.toolbox.shared.auth.SiteKey
import com.xjtu.toolbox.shared.auth.SiteSession
import com.xjtu.toolbox.shared.auth.SiteVerificationContext
import com.xjtu.toolbox.shared.auth.SiteVerificationRequiredException
import com.xjtu.toolbox.shared.network.HttpMethod
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.network.HttpResponse
import com.xjtu.toolbox.shared.parsing.JwappScheduleParser
import com.xjtu.toolbox.shared.parsing.SchoolCourseParser
import com.xjtu.toolbox.shared.parsing.WireGuards

class XjtuSchoolCourseRepository(
    private val baseUrl: String = "https://jwxt.xjtu.edu.cn",
) : SchoolCourseRepository {
    private val appBase = "$baseUrl/jwapp/sys/kcbcx"
    private var cachedCurrentTerm: String? = null

    override suspend fun courses(
        session: SiteSession,
        termCode: String?,
        courseName: String,
        teacher: String,
        campusCode: String,
        weekday: Int,
        page: Int,
        pageSize: Int,
    ): SchoolCoursePage {
        require(weekday in 0..7) { "weekday must be in 0..7" }
        require(page >= 1) { "page must be >= 1" }
        require(pageSize in 1..100) { "pageSize must be in 1..100" }
        val httpSession = session.asHttpSiteSession()
        initializeApp(httpSession)
        val selectedTerm = termCode?.trim()?.takeIf { it.isNotBlank() } ?: currentTerm(httpSession)
        val response = httpSession.execute(
            formPost(
                url = "$appBase/modules/qxkcb/qxfbkccx.do",
                fields = listOf(
                    "querySetting" to querySetting(
                        termCode = selectedTerm,
                        courseName = courseName,
                        teacher = teacher,
                        campusCode = campusCode,
                    ),
                    "*order" to "+KKDWDM,+KCH,+KXH",
                    "SKXQ" to weekday.takeIf { it > 0 }?.toString().orEmpty(),
                    "KSJC" to "",
                    "JSJC" to "",
                    "pageSize" to pageSize.toString(),
                    "pageNumber" to page.toString(),
                ),
                referer = "$appBase/*default/index.do",
            ),
        )
        validateResponse(response, "全校课程查询")
        return SchoolCourseParser.parsePage(response.bodyText, selectedTerm, page, pageSize)
    }

    private suspend fun initializeApp(session: HttpSiteSession) {
        val response = session.execute(
            HttpRequest(
                url = "$appBase/*default/index.do",
                headers = mapOf("Accept" to "text/html"),
            ),
        )
        validateResponse(response, "全校课程查询初始化")
    }

    private suspend fun currentTerm(session: HttpSiteSession): String {
        cachedCurrentTerm?.let { return it }
        val response = session.execute(
            formPost(
                url = "$appBase/modules/bjkcb/dqxnxq.do",
                fields = emptyList(),
                referer = "$appBase/*default/index.do",
            ),
        )
        validateResponse(response, "全校课程当前学期查询")
        return JwappScheduleParser.parseCurrentTerm(response.bodyText).also {
            cachedCurrentTerm = it
        }
    }

    private fun querySetting(
        termCode: String,
        courseName: String,
        teacher: String,
        campusCode: String,
    ): String {
        val conditions = mutableListOf<String>()
        courseName.trim().takeIf { it.isNotBlank() }?.let {
            conditions += stringCondition("KCM", "课程名", "include", it)
        }
        teacher.trim().takeIf { it.isNotBlank() }?.let {
            conditions += stringCondition("SKJS", "上课教师", "include", it)
        }
        campusCode.trim().takeIf { it.isNotBlank() }?.let {
            conditions += stringCondition("XXXQDM", "学校校区", "equal", it)
        }
        conditions += """[{"name":"XNXQDM","value":${jsonString(termCode)},"linkOpt":"and","builder":"equal"},[{"name":"RWZTDM","value":"1","linkOpt":"and","builder":"equal"},{"name":"RWZTDM","linkOpt":"or","builder":"isNull"}]]"""
        conditions += """{"name":"*order","value":"+KKDWDM,+KCH,+KXH","linkOpt":"AND","builder":"m_value_equal"}"""
        return conditions.joinToString(prefix = "[", postfix = "]")
    }

    private fun stringCondition(name: String, caption: String, builder: String, value: String): String =
        """{"name":${jsonString(name)},"caption":${jsonString(caption)},"linkOpt":"AND","builderList":"cbl_String","builder":${jsonString(builder)},"value":${jsonString(value)}}"""

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

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

    private fun validateResponse(response: HttpResponse, operation: String) {
        if (response.code == 401 || response.code == 403 || response.isAuthPage()) {
            throw SiteVerificationRequiredException(
                site = SiteKey.SCHEDULE,
                message = "课程查询需要补授权",
                verificationContext = SiteVerificationContext(
                    finalUrl = response.finalUrl,
                    bodyText = response.bodyText,
                ),
            )
        }
        if (!response.isSuccessful) error("$operation 失败: HTTP ${response.code}")
        if (response.bodyText.isBlank()) error("$operation 返回空数据")
    }

    private fun HttpResponse.isAuthPage(): Boolean =
        finalUrl.contains("login.xjtu.edu.cn", ignoreCase = true) ||
            WireGuards.isAuthHtml(bodyText)

    private fun SiteSession.asHttpSiteSession(): HttpSiteSession =
        (this as? HttpSiteSession)
            ?.also {
                require(it.site == SiteKey.SCHEDULE || it.site == SiteKey.JWAPP || it.site == SiteKey.JWXT) {
                    "school-course repository requires schedule/JWAPP/JWXT session"
                }
            }
            ?: error("school-course repository requires an HttpSiteSession")

    private fun formEncode(value: String): String {
        val builder = StringBuilder()
        for (byte in value.encodeToByteArray()) {
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
