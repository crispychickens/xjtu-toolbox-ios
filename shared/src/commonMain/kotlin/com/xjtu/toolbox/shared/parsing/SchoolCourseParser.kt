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
        val datas = root["datas"]?.asObjectOrNull()
        val data = datas
            ?.get("qxfbkccx")
            ?.asObjectOrNull()
            ?: error("school-course response missing qxfbkccx ${root.shapeDiagnostics(datas)}")
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

    private fun Map<String, JsonValue>.shapeDiagnostics(datas: Map<String, JsonValue>?): String {
        val code = this["code"]?.asStringOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: "absent"
        val topKeys = keys.sorted().joinToString(",").ifBlank { "none" }
        val datasKeys = datas?.keys?.sorted()?.joinToString(",")?.ifBlank { "none" } ?: "absent"
        val url = this["url"]?.asStringOrNull()?.trim()?.takeIf { it.isNotBlank() }
        return "shape=code:$code;top:$topKeys;datas:$datasKeys;url:${url.safeUrlDiagnostics()}"
    }

    private fun String?.safeUrlDiagnostics(): String {
        val url = this ?: return "absent"
        val normalized = url.lowercase()
        val host = normalized.safeHostDiagnostic()
        val tags = buildList {
            if ("kcbcx" in normalized) add("kcbcx")
            if ("wdkb" in normalized) add("wdkb")
            if ("homeapp" in normalized) add("homeapp")
            if ("cas" in normalized) add("cas")
            if ("*default" in normalized || "%2adefault" in normalized) add("default")
            if ("modules" in normalized) add("modules")
            if ("qxfbkccx" in normalized) add("qxfbkccx")
        }.joinToString(",").ifBlank { "none" }
        return "$host:$tags;path:${url.safePathDiagnostics()};query:${url.safeQueryDiagnostics()}"
    }

    private fun String.safeHostDiagnostic(): String {
        if (startsWith("/")) return "relative"
        val host = substringAfter("://", missingDelimiterValue = "")
            .substringBefore("/")
            .substringBefore("?")
            .substringBefore("#")
            .substringBefore(":")
        return when {
            host.equals("login.xjtu.edu.cn", ignoreCase = true) -> "login"
            host.equals("jwxt.xjtu.edu.cn", ignoreCase = true) -> "jwxt"
            host.equals("webvpn.xjtu.edu.cn", ignoreCase = true) -> "webvpn"
            else -> "other"
        }
    }

    private fun String.safePathDiagnostics(): String {
        val path = pathOnly()
        if (path.isBlank() || path == "/") return "root"
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        val first = segments.firstOrNull()?.safePathSegmentLabel() ?: "root"
        return "segments:${segments.size},first:$first"
    }

    private fun String.pathOnly(): String {
        val noQueryOrFragment = substringBefore("#").substringBefore("?")
        if (noQueryOrFragment.startsWith("/")) return noQueryOrFragment
        val afterScheme = noQueryOrFragment.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isBlank()) return ""
        val afterHost = afterScheme.substringAfter("/", missingDelimiterValue = "")
        return afterHost.takeIf { it.isNotBlank() }?.let { "/$it" }.orEmpty()
    }

    private fun String.safePathSegmentLabel(): String {
        val segment = lowercase()
        return when (segment) {
            "auth",
            "cas",
            "connect",
            "homeapp",
            "http",
            "https",
            "jwapp",
            "kcbcx",
            "login",
            "portal",
            "user",
            "users",
            "vpn",
            "wdkb",
            "wengine-vpn",
            -> segment
            else -> "unknown"
        }
    }

    private fun String.safeQueryDiagnostics(): String {
        val query = substringAfter("?", missingDelimiterValue = "")
            .substringBefore("#")
            .takeIf { it.isNotBlank() }
            ?: return "none"
        return query.split("&")
            .mapNotNull { parameter ->
                parameter.substringBefore("=")
                    .takeIf { it.isNotBlank() }
                    ?.lowercase()
                    ?.safeQueryName()
            }
            .distinct()
            .sorted()
            .joinToString(",")
            .ifBlank { "none" }
    }

    private fun String.safeQueryName(): String =
        takeIf { SAFE_QUERY_NAME.matches(it) } ?: "other"

    private val SAFE_QUERY_NAME = Regex("[a-z0-9_.-]{1,40}")
}
