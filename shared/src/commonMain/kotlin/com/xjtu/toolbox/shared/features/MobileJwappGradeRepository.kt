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
                val scores = term["scoreList"]?.asArrayOrNull()
                    ?: error("mobile grade response scoreList missing")
                scores.mapNotNull { it.asObjectOrNull() }
                    .mapNotNull(::gradeItem)
            }
    }

    private fun gradeItem(row: Map<String, com.xjtu.toolbox.shared.parsing.JsonValue>): GradeItem? {
        val courseName = row["courseName"]?.asStringOrNull()?.trim().orEmpty()
        if (courseName.isBlank()) return null
        val score = row["score"]?.asStringOrNull()?.trim().orEmpty()
        return GradeItem(
            courseName = courseName,
            score = score,
            credit = row["coursePoint"]?.asDoubleOrNull() ?: 0.0,
            gradePoint = row["gpa"]?.asDoubleOrNull()?.takeIf { it > 0.0 }
                ?: fallbackGpa(score = score, passFlag = row["passFlag"]?.asBooleanOrNull()),
        )
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
}
