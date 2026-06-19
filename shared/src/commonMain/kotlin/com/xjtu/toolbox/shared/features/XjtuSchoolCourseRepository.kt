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
import com.xjtu.toolbox.shared.parsing.JsonParser
import com.xjtu.toolbox.shared.parsing.asObjectOrNull
import com.xjtu.toolbox.shared.parsing.asStringOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

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
        val request = formPost(
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
        )
        val response = executeWithWebVpnWarmupRetry(httpSession, request, operation = "全校课程查询")
        validateResponse(response, "全校课程查询")
        return SchoolCourseParser.parsePage(response.bodyText, selectedTerm, page, pageSize)
    }

    private suspend fun initializeApp(session: HttpSiteSession) {
        val request = HttpRequest(
            url = "$appBase/*default/index.do",
            headers = mapOf("Accept" to "text/html"),
        )
        val response = followWebVpnEnvelopeIfPresent(session, session.execute(request), request)
        validateResponse(response, "全校课程查询初始化")
    }

    private suspend fun executeWithWebVpnWarmupRetry(
        session: HttpSiteSession,
        request: HttpRequest,
        operation: String,
    ): HttpResponse {
        val response = session.execute(request)
        val warmed = followWebVpnEnvelopeIfPresent(session, response, request)
        return if (warmed === response) {
            response
        } else {
            validateResponse(warmed, "$operation WebVPN 应用入口")
            session.execute(request)
        }
    }

    private suspend fun followWebVpnEnvelopeIfPresent(
        session: HttpSiteSession,
        response: HttpResponse,
        request: HttpRequest,
    ): HttpResponse {
        val followUrl = response.webVpnEnvelopeUrl() ?: return response
        return session.execute(
            HttpRequest(
                url = followUrl,
                headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to request.url,
                ),
            ),
        )
    }

    private suspend fun currentTerm(session: HttpSiteSession): String {
        cachedCurrentTerm?.let { return it }
        return runCatching {
            currentTermFrom(
                session = session,
                url = "$appBase/modules/bjkcb/dqxnxq.do",
                referer = "$appBase/*default/index.do",
                operation = "全校课程当前学期查询",
            )
        }.getOrElse { failure ->
            failure.throwIfFatal()
            runCatching {
                currentTermFrom(
                    session = session,
                    url = "$baseUrl/jwapp/sys/wdkb/modules/jshkcb/dqxnxq.do",
                    referer = "$baseUrl/jwapp/sys/wdkb/*default/index.do",
                    operation = "课表当前学期查询",
                )
            }.getOrElse { fallbackFailure ->
                fallbackFailure.throwIfFatal()
                inferCurrentAcademicTerm()
            }
        }.also {
            cachedCurrentTerm = it
        }
    }

    private suspend fun currentTermFrom(
        session: HttpSiteSession,
        url: String,
        referer: String,
        operation: String,
    ): String {
        val response = session.execute(
            formPost(
                url = url,
                fields = emptyList(),
                referer = referer,
            ),
        )
        validateResponse(response, operation)
        return JwappScheduleParser.parseCurrentTerm(response.bodyText)
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

    private fun HttpResponse.webVpnEnvelopeUrl(): String? {
        val root = runCatching { JsonParser(bodyText).parse().asObjectOrNull() }
            .getOrNull()
            ?: return null
        if ("datas" in root || "code" in root || "url" !in root || "success" !in root) return null
        val rawUrl = root["url"]?.asStringOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return resolveSafeFollowUrl(rawUrl)
    }

    private fun HttpResponse.resolveSafeFollowUrl(rawUrl: String): String? {
        val absolute = when {
            rawUrl.startsWith("https://", ignoreCase = true) ||
                rawUrl.startsWith("http://", ignoreCase = true) -> rawUrl
            rawUrl.startsWith("/") &&
                finalUrl.contains("webvpn.xjtu.edu.cn", ignoreCase = true) &&
                !rawUrl.startsWith("/jwapp/", ignoreCase = true) ->
                "https://webvpn.xjtu.edu.cn$rawUrl"
            rawUrl.startsWith("/") -> "$baseUrl$rawUrl"
            else -> return null
        }
        return absolute.takeIf {
            it.contains("jwxt.xjtu.edu.cn", ignoreCase = true) ||
                it.contains("webvpn.xjtu.edu.cn", ignoreCase = true) ||
                it.contains("login.xjtu.edu.cn", ignoreCase = true)
        }
    }

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

private fun Throwable.throwIfFatal() {
    if (this is SiteVerificationRequiredException || this is CancellationException) throw this
}

@OptIn(ExperimentalTime::class)
internal fun inferCurrentAcademicTerm(): String {
    val epochMillisInShanghai = Clock.System.now().toEpochMilliseconds() + SHANGHAI_OFFSET_MILLIS
    val epochDay = floorDiv(epochMillisInShanghai, MILLIS_PER_DAY)
    val yearMonth = gregorianYearMonthFromEpochDay(epochDay)
    return inferAcademicTerm(yearMonth.year, yearMonth.month)
}

internal fun inferAcademicTerm(year: Int, month: Int): String {
    require(month in 1..12) { "month must be in 1..12" }
    return when (month) {
        in 9..12 -> "$year-${year + 1}-1"
        1 -> "${year - 1}-$year-1"
        else -> "${year - 1}-$year-2"
    }
}

private fun gregorianYearMonthFromEpochDay(epochDay: Long): YearMonth {
    val adjusted = epochDay + DAYS_FROM_CIVIL_1970_01_01
    val era = floorDiv(adjusted, DAYS_PER_ERA)
    val dayOfEra = adjusted - era * DAYS_PER_ERA
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    var year = yearOfEra + era * 400
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthPrime = (5 * dayOfYear + 2) / 153
    val month = monthPrime + if (monthPrime < 10) 3 else -9
    if (month <= 2) year += 1
    return YearMonth(year.toInt(), month.toInt())
}

private fun floorDiv(value: Long, divisor: Long): Long {
    var quotient = value / divisor
    if ((value xor divisor) < 0 && quotient * divisor != value) {
        quotient -= 1
    }
    return quotient
}

private data class YearMonth(
    val year: Int,
    val month: Int,
)

private const val MILLIS_PER_DAY = 86_400_000L
private const val SHANGHAI_OFFSET_MILLIS = 8 * 60 * 60 * 1_000L
private const val DAYS_PER_ERA = 146_097L
private const val DAYS_FROM_CIVIL_1970_01_01 = 719_468L
