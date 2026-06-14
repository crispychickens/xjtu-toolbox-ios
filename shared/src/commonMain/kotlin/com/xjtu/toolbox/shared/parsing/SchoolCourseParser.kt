package com.xjtu.toolbox.shared.parsing

import com.xjtu.toolbox.shared.features.SchoolCourseItem
import com.xjtu.toolbox.shared.features.SchoolCoursePage

object SchoolCourseParser {
    fun parsePage(
        json: String,
        termCode: String,
        page: Int,
        pageSize: Int,
    ): SchoolCoursePage {
        if (WireGuards.isAuthHtml(json)) {
            error("school-course JSON expected, got CAS/Safety Verify HTML")
        }
        val root = JsonParser(json).parse().asObjectOrNull()
            ?: error("school-course payload must be a JSON object")
        requireJwappOk(root)
        val data = root["datas"]?.asObjectOrNull()
            ?.get("qxfbkccx")
            ?.asObjectOrNull()
            ?: error("school-course response missing qxfbkccx")
        val total = data["totalSize"]?.asIntOrNull()
            ?: error("school-course response missing totalSize")
        val rows = data["rows"]?.asArrayOrNull()
            ?: error("school-course response missing rows")
        return SchoolCoursePage(
            termCode = termCode,
            total = total,
            page = page,
            pageSize = pageSize,
            records = rows.mapNotNull { it.asObjectOrNull() }
                .mapNotNull { row ->
                    val courseName = row["KCM"]?.asStringOrNull()?.trim().orEmpty()
                    if (courseName.isBlank()) return@mapNotNull null
                    SchoolCourseItem(
                        courseCode = row["KCH"]?.asStringOrNull()?.trim().orEmpty(),
                        courseName = courseName,
                        sectionNumber = row["KXH"]?.asStringOrNull()?.trim().orEmpty(),
                        teacher = row["SKJS"]?.asStringOrNull()?.trim().orEmpty(),
                        department = row["KKDWDM_DISPLAY"]?.asStringOrNull()?.trim().orEmpty(),
                        credit = row["XF"]?.asDoubleOrNull() ?: 0.0,
                        enrollCount = row["XKZRS"]?.asIntOrNull() ?: 0,
                        capacity = row["KRL"]?.asIntOrNull() ?: 0,
                        scheduleLocation = row["YPSJDD"]?.asStringOrNull()?.trim().orEmpty(),
                        campus = row["XXXQDM_DISPLAY"]?.asStringOrNull()?.trim().orEmpty(),
                        teachingClassId = row["JXBID"]?.asStringOrNull()?.trim().orEmpty(),
                        termCode = row["XNXQDM"]?.asStringOrNull()?.trim().orEmpty().ifBlank { termCode },
                    )
                },
        )
    }

    private fun requireJwappOk(root: Map<String, JsonValue>) {
        val code = root["code"]?.asStringOrNull()?.trim() ?: return
        if (code == "0") return
        val message = root["message"]?.asStringOrNull()?.trim()
            ?: root["msg"]?.asStringOrNull()?.trim()
            ?: root["errmsg"]?.asStringOrNull()?.trim()
            ?: ""
        error(
            if (message.isBlank()) {
                "school-course service returned code $code"
            } else {
                "school-course service returned code $code: $message"
            },
        )
    }
}
