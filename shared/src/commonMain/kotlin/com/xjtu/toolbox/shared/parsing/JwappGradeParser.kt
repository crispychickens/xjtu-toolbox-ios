package com.xjtu.toolbox.shared.parsing

import com.xjtu.toolbox.shared.features.GradeItem

data class JwappGradePage(
    val totalSize: Int,
    val rawRowCount: Int,
    val grades: List<GradeItem>,
)

object JwappGradeParser {
    fun parseCjcxPage(json: String, termCode: String? = null): JwappGradePage {
        if (WireGuards.isAuthHtml(json)) {
            error("grade JSON expected, got CAS/Safety Verify HTML")
        }
        val root = JsonParser(json).parse().asObjectOrNull()
            ?: error("grade payload must be a JSON object")
        val code = root["code"]?.asStringOrNull()
        if (code != null && code != "0") {
            error("grade service returned code $code")
        }
        val xscjcx = root["datas"]?.asObjectOrNull()
            ?.get("xscjcx")
            ?.asObjectOrNull()
            ?: error("grade response missing xscjcx rows")
        val rows = xscjcx["rows"]?.asArrayOrNull()
            ?: error("grade response missing xscjcx rows")
        val grades = rows.mapNotNull { it.asObjectOrNull() }
            .filter { row ->
                termCode.isNullOrBlank() || row["XNXQDM"]?.asStringOrNull() == termCode
            }
            .mapNotNull { row ->
                val courseName = row["KCM"]?.asStringOrNull()?.trim().orEmpty()
                if (courseName.isBlank()) return@mapNotNull null
                GradeItem(
                    courseName = courseName,
                    score = scoreText(row),
                    credit = row["XF"]?.asDoubleOrNull() ?: 0.0,
                    gradePoint = row["XFJD"]?.asDoubleOrNull() ?: 0.0,
                    termCode = row["XNXQDM"]?.asStringOrNull()?.trim().orEmpty(),
                )
            }
        return JwappGradePage(
            totalSize = xscjcx["totalSize"]?.asIntOrNull() ?: rows.size,
            rawRowCount = rows.size,
            grades = grades,
        )
    }

    private fun scoreText(row: Map<String, JsonValue>): String {
        val levelScore = row["DJCJMC"]?.asStringOrNull()?.trim()
        if (!levelScore.isNullOrBlank()) return levelScore
        val raw = row["ZCJ"]?.asStringOrNull()?.trim().orEmpty()
        return raw.removeSuffix(".0")
    }
}
