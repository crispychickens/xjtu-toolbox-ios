package com.xjtu.toolbox.shared.features

import com.xjtu.toolbox.shared.auth.HttpSiteSession
import com.xjtu.toolbox.shared.auth.SiteKey
import com.xjtu.toolbox.shared.auth.SiteSession
import com.xjtu.toolbox.shared.network.HttpMethod
import com.xjtu.toolbox.shared.network.HttpRequest
import com.xjtu.toolbox.shared.parsing.JsonParser
import com.xjtu.toolbox.shared.parsing.asArrayOrNull
import com.xjtu.toolbox.shared.parsing.asBooleanOrNull
import com.xjtu.toolbox.shared.parsing.asDoubleOrNull
import com.xjtu.toolbox.shared.parsing.asIntOrNull
import com.xjtu.toolbox.shared.parsing.asObjectOrNull
import com.xjtu.toolbox.shared.parsing.asStringOrNull
import com.xjtu.toolbox.shared.parsing.JsonValue

class MobileJwappGradeRepository(
    private val baseUrl: String = "https://jwapp.xjtu.edu.cn",
) : GradeRepository {
    override suspend fun grades(session: SiteSession, termCode: String?): List<GradeItem> {
        val httpSession = session.asHttpSiteSession()
        val response = httpSession.execute(
            HttpRequest(
                url = "$baseUrl/api/biz/v410/score/termScore",
                method = HttpMethod.POST,
                headers = mapOf("Content-Type" to "application/json"),
                body = """{"termCode":"${termCode?.takeIf { it.isNotBlank() } ?: "*"}"}""".encodeToByteArray(),
            ),
        )
        if (!response.isSuccessful) error("成绩查询失败: HTTP ${response.code}")
        return parseTermScore(response.bodyText, termCode)
    }

    override suspend fun gradeDetail(session: SiteSession, gradeId: String): GradeDetail {
        require(gradeId.isNotBlank()) { "成绩记录 id 不能为空" }
        val httpSession = session.asHttpSiteSession()
        val response = httpSession.execute(
            HttpRequest(
                url = "$baseUrl/api/biz/v410/score/scoreDetail",
                method = HttpMethod.POST,
                headers = mapOf("Content-Type" to "application/json"),
                body = """{"id":"${gradeId.jsonEscaped()}"}""".encodeToByteArray(),
            ),
        )
        if (!response.isSuccessful) error("成绩详情查询失败: HTTP ${response.code}")
        return parseScoreDetail(response.bodyText)
    }

    private fun parseTermScore(json: String, termCode: String?): List<GradeItem> {
        val root = JsonParser(json).parse().asObjectOrNull()
            ?: error("mobile grade payload must be a JSON object")
        val code = root["code"]?.asIntOrNull()
            ?: error("mobile grade response code missing")
        if (code != 200) {
            val message = root["msg"]?.asStringOrNull()?.trim()
                ?: root["message"]?.asStringOrNull()?.trim()
                ?: "mobile grade returned code $code"
            error(message)
        }
        val data = root["data"]?.asObjectOrNull()
            ?: error("mobile grade response data missing")
        val terms = data["termScoreList"]?.asArrayOrNull()
            ?: error("mobile grade response termScoreList missing")
        return terms
            .mapNotNull { it.asObjectOrNull() }
            .filter { term ->
                termCode.isNullOrBlank() || term["termCode"]?.asStringOrNull() == termCode
            }
            .flatMap { term ->
                val resolvedTermCode = term["termCode"]?.asStringOrNull()?.trim().orEmpty()
                val scores = term["scoreList"]?.asArrayOrNull()
                    ?: error("mobile grade response scoreList missing")
                scores.mapNotNull { it.asObjectOrNull() }
                    .mapNotNull { gradeItem(it, resolvedTermCode) }
            }
    }

    private fun gradeItem(
        row: Map<String, com.xjtu.toolbox.shared.parsing.JsonValue>,
        termCode: String,
    ): GradeItem? {
        val courseName = row["courseName"]?.asStringOrNull()?.trim().orEmpty()
        if (courseName.isBlank()) return null
        val score = row["score"]?.asStringOrNull()?.trim().orEmpty()
        return GradeItem(
            courseName = courseName,
            score = score,
            credit = row["coursePoint"]?.asDoubleOrNull() ?: 0.0,
            gradePoint = row["gpa"]?.asDoubleOrNull()?.takeIf { it > 0.0 }
                ?: fallbackGpa(score = score, passFlag = row["passFlag"]?.asBooleanOrNull()),
            id = row["id"]?.asStringOrNull()?.trim().orEmpty(),
            termCode = termCode,
        )
    }

    private fun parseScoreDetail(json: String): GradeDetail {
        val root = JsonParser(json).parse().asObjectOrNull()
            ?: error("mobile grade detail payload must be a JSON object")
        val code = root["code"]?.asIntOrNull()
            ?: error("mobile grade detail response code missing")
        if (code != 200) {
            val message = root["msg"]?.asStringOrNull()?.trim()
                ?: root["message"]?.asStringOrNull()?.trim()
                ?: "mobile grade detail returned code $code"
            error(message)
        }
        val data = root["data"]?.asObjectOrNull()
            ?: error("mobile grade detail response data missing")
        val courseName = data["courseName"]?.asStringOrNull()?.trim().orEmpty()
        if (courseName.isBlank()) error("mobile grade detail courseName missing")
        val score = data["score"]?.asStringOrNull()?.trim().orEmpty()
        val items = data["itemList"]?.asArrayOrNull()
            ?.mapNotNull { it.asObjectOrNull() }
            ?.mapNotNull(::gradeDetailItem)
            .orEmpty()
        return GradeDetail(
            courseName = courseName,
            score = score,
            credit = data["coursePoint"]?.asDoubleOrNull() ?: 0.0,
            gradePoint = data["gpa"]?.asDoubleOrNull()?.takeIf { it > 0.0 }
                ?: fallbackGpa(score = score, passFlag = data["passFlag"]?.asBooleanOrNull()),
            examType = data["examType"]?.asStringOrNull()?.trim().orEmpty(),
            courseProperty = data["majorFlag"]?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            examProperty = data["examProp"]?.asStringOrNull()?.trim().orEmpty(),
            isReplacement = data["replaceFlag"]?.asBooleanOrNull() ?: false,
            isPassed = data["passFlag"]?.asBooleanOrNull() ?: false,
            specificReason = data["specificReason"]?.asStringOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            items = items,
        )
    }

    private fun gradeDetailItem(row: Map<String, JsonValue>): GradeDetailItem? {
        val name = row["itemName"]?.asStringOrNull()?.trim().orEmpty()
        if (name.isBlank()) return null
        return GradeDetailItem(
            name = name,
            percent = parsePercent(row["itemPercent"]?.asStringOrNull()),
            score = row["itemScore"]?.asStringOrNull()?.trim().orEmpty(),
        )
    }

    private fun parsePercent(value: String?): Double {
        val normalized = value?.trim().orEmpty()
        if (normalized.isEmpty()) return 0.0
        return normalized.removeSuffix("%").toDoubleOrNull()?.div(100.0) ?: 0.0
    }

    private fun fallbackGpa(score: String, passFlag: Boolean?): Double {
        val numeric = score.toDoubleOrNull()
        if (numeric != null) {
            return when {
                numeric >= 95 -> 4.3
                numeric >= 90 -> 4.0
                numeric >= 85 -> 3.7
                numeric >= 80 -> 3.3
                numeric >= 75 -> 3.0
                numeric >= 70 -> 2.7
                numeric >= 67 -> 2.3
                numeric >= 65 -> 2.0
                numeric >= 62 -> 1.7
                numeric >= 60 -> 1.0
                else -> 0.0
            }
        }
        return when {
            score.contains("优秀") -> 4.0
            score.contains("良好") -> 3.0
            score.contains("中等") -> 2.0
            score.contains("及格") || passFlag == true -> 1.0
            else -> 0.0
        }
    }

    private fun SiteSession.asHttpSiteSession(): HttpSiteSession =
        (this as? HttpSiteSession)
            ?.also { require(it.site == SiteKey.GRADE || it.site == SiteKey.JWAPP) { "移动教务成绩 repository requires GRADE/JWAPP session" } }
            ?: error("移动教务成绩 repository requires an HttpSiteSession")

    private fun String.jsonEscaped(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")
}
