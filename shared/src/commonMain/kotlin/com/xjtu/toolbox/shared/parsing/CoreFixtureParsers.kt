package com.xjtu.toolbox.shared.parsing

import com.xjtu.toolbox.shared.features.CampusCardTransaction
import com.xjtu.toolbox.shared.features.CourseItem
import com.xjtu.toolbox.shared.features.EmptyRoom
import com.xjtu.toolbox.shared.features.GradeItem
import com.xjtu.toolbox.shared.features.NoticeItem
import com.xjtu.toolbox.shared.features.TransactionKind

object WireGuards {
    fun isAuthHtml(body: String): Boolean {
        val lower = body.lowercase()
        return "<html" in lower && (
            "login.xjtu.edu.cn" in lower ||
                "cas/login" in lower ||
                "secstate" in lower ||
                "safety verify" in lower
            )
    }
}

object CoreFixtureParsers {
    fun parseScheduleCsv(csv: String): List<CourseItem> {
        rejectAuthHtml(csv)
        return csv.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val cols = line.split(",").map { it.trim() }
                require(cols.size >= 7) { "schedule row needs 7 columns" }
                CourseItem(
                    name = cols[0],
                    teacher = cols[1],
                    location = cols[2],
                    dayOfWeek = cols[3].toInt(),
                    startSection = cols[4].toInt(),
                    endSection = cols[5].toInt(),
                    weeks = parseWeeks(cols[6]),
                )
            }
            .toList()
    }

    fun parseGradeRows(rows: String): List<GradeItem> {
        rejectAuthHtml(rows)
        return rows.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val cols = line.split("|").map { it.trim() }
                require(cols.size >= 4) { "grade row needs 4 columns" }
                GradeItem(
                    courseName = cols[0],
                    score = cols[1],
                    credit = cols[2].toDouble(),
                    gradePoint = cols[3].toDouble(),
                )
            }
            .toList()
    }

    fun parseCampusCardRows(rows: String): List<CampusCardTransaction> {
        rejectAuthHtml(rows)
        return rows.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val cols = line.split("|").map { it.trim() }
                require(cols.size >= 4) { "campus card row needs 4 columns" }
                val declaredAmount = cols[2].toDouble()
                val kind = when {
                    cols[3].contains("收入") || cols[3].contains("充值") -> TransactionKind.INCOME
                    else -> TransactionKind.EXPENSE
                }
                CampusCardTransaction(
                    time = cols[0],
                    merchant = cols[1],
                    amountYuan = if (kind == TransactionKind.EXPENSE) -kotlin.math.abs(declaredAmount) else kotlin.math.abs(declaredAmount),
                    kind = kind,
                )
            }
            .toList()
    }

    fun parseNoticeAnchors(html: String, source: String): List<NoticeItem> {
        rejectAuthHtml(html)
        val pattern = Regex(
            """<a\s+[^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        return pattern.findAll(html)
            .map {
                NoticeItem(
                    title = it.groupValues[2].replace(Regex("<[^>]+>"), "").trim(),
                    link = it.groupValues[1],
                    source = source,
                    date = Regex("""\d{4}[-/]\d{1,2}[-/]\d{1,2}""").find(it.value)?.value,
                )
            }
            .filter { it.title.isNotBlank() }
            .toList()
    }

    fun parseEmptyRoomRows(rows: String): List<EmptyRoom> {
        rejectAuthHtml(rows)
        return rows.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val cols = line.split("|").map { it.trim() }
                require(cols.size >= 4) { "empty room row needs 4 columns" }
                EmptyRoom(
                    name = cols[0],
                    campus = cols[1],
                    building = cols[2],
                    availableSections = parseSections(cols[3]),
                )
            }
            .toList()
    }

    private fun rejectAuthHtml(body: String) {
        if (WireGuards.isAuthHtml(body)) {
            error("authenticated response expected, got CAS/Safety Verify HTML")
        }
    }

    private fun parseWeeks(value: String): List<Int> =
        value.split(";")
            .flatMap { part ->
                if ("-" in part) {
                    val start = part.substringBefore("-").toInt()
                    val end = part.substringAfter("-").toInt()
                    (start..end).toList()
                } else {
                    listOf(part.toInt())
                }
            }

    private fun parseSections(value: String): List<Int> =
        value.split(";").mapNotNull { it.toIntOrNull() }
}
