package com.xjtu.toolbox.shared.parsing

import com.xjtu.toolbox.shared.features.CourseItem
import com.xjtu.toolbox.shared.features.ExamItem

object JwappScheduleParser {
    fun parseCurrentTerm(json: String): String {
        if (WireGuards.isAuthHtml(json)) {
            error("current-term JSON expected, got CAS/Safety Verify HTML")
        }
        val root = JsonParser(json).parse().asObjectOrNull()
            ?: error("current-term payload must be a JSON object")
        requireJwappOk(root, "current-term")
        return root["datas"]?.asObjectOrNull()
            ?.get("dqxnxq")
            ?.asObjectOrNull()
            ?.get("rows")
            ?.asArrayOrNull()
            ?.firstOrNull()
            ?.asObjectOrNull()
            ?.get("DM")
            ?.asStringOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: error("current-term response missing term code")
    }

    fun parseCourses(json: String): List<CourseItem> {
        if (WireGuards.isAuthHtml(json)) {
            error("schedule JSON expected, got CAS/Safety Verify HTML")
        }
        val rows = moduleRows(json, "xskcb")
        return rows.mapNotNull { it.asObjectOrNull() }
            .mapNotNull { row ->
                val name = row["KCM"]?.asStringOrNull()?.trim().orEmpty()
                if (name.isBlank()) return@mapNotNull null
                CourseItem(
                    name = name,
                    teacher = row["SKJS"]?.asStringOrNull()?.trim().orEmpty(),
                    location = row["JASMC"]?.asStringOrNull()?.trim().orEmpty(),
                    dayOfWeek = row["SKXQ"]?.asIntOrNull() ?: 1,
                    startSection = row["KSJC"]?.asIntOrNull() ?: 1,
                    endSection = row["JSJC"]?.asIntOrNull() ?: 1,
                    weeks = parseWeekBits(row["SKZC"]?.asStringOrNull().orEmpty()),
                )
            }
    }

    fun parseExams(json: String): List<ExamItem> {
        if (WireGuards.isAuthHtml(json)) {
            error("exam JSON expected, got CAS/Safety Verify HTML")
        }
        val rows = moduleRows(json, "wdksap")
        return rows.mapNotNull { it.asObjectOrNull() }
            .mapNotNull { row ->
                val courseName = row["KCM"]?.asStringOrNull()?.trim()
                    ?: row["KCMC"]?.asStringOrNull()?.trim()
                    ?: row["KCH"]?.asStringOrNull()?.trim()
                    ?: ""
                if (courseName.isBlank()) return@mapNotNull null
                val rawDate = row["KSRQ"]?.asStringOrNull()?.trim().orEmpty()
                val examDate = rawDate.substringBefore(" ")
                val rawTime = row["KSSJMS"]?.asStringOrNull()?.trim().orEmpty()
                val cleanedTime = rawTime
                    .replace(examDate, "")
                    .replace(rawDate, "")
                    .trim()
                    .trimStart('-', ' ')
                    .ifBlank { rawTime }
                ExamItem(
                    courseName = courseName,
                    time = listOf(examDate, cleanedTime).filter { it.isNotBlank() }.joinToString(" "),
                    location = row["JASMC"]?.asStringOrNull()?.trim().orEmpty(),
                )
            }
    }

    private fun moduleRows(json: String, moduleName: String): List<JsonValue> {
        val root = JsonParser(json).parse().asObjectOrNull()
            ?: error("$moduleName payload must be a JSON object")
        requireJwappOk(root, moduleName)
        return root["datas"]?.asObjectOrNull()
            ?.get(moduleName)
            ?.asObjectOrNull()
            ?.get("rows")
            ?.asArrayOrNull()
            ?: error("$moduleName response missing rows")
    }

    private fun requireJwappOk(root: Map<String, JsonValue>, serviceName: String) {
        val code = root["code"]?.asStringOrNull()?.trim() ?: return
        if (code == "0") return
        val message = root["message"]?.asStringOrNull()?.trim()
            ?: root["msg"]?.asStringOrNull()?.trim()
            ?: root["errmsg"]?.asStringOrNull()?.trim()
            ?: ""
        error(
            if (message.isBlank()) {
                "$serviceName service returned code $code"
            } else {
                "$serviceName service returned code $code: $message"
            },
        )
    }

    private fun parseWeekBits(bits: String): List<Int> =
        bits.mapIndexedNotNull { index, char ->
            if (char == '1') index + 1 else null
        }
}
