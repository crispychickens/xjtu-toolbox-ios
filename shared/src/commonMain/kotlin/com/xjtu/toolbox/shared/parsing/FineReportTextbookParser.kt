package com.xjtu.toolbox.shared.parsing

import com.xjtu.toolbox.shared.features.TextbookItem

object FineReportTextbookParser {
    fun parse(html: String): List<TextbookItem> {
        if (WireGuards.isAuthHtml(html)) {
            error("textbook report HTML expected, got CAS/Safety Verify HTML")
        }
        if (html.contains("FR-Engine_Error", ignoreCase = true) || html.contains("出错页面")) {
            error("FineReport textbook report returned an error page")
        }
        return parseTable(html).ifEmpty { parsePositionedDivs(html) }
            .distinctBy { "${it.courseName}|${it.textbookName}|${it.isbn}" }
    }

    fun extractSessionId(html: String): String? =
        Regex("""FR\.SessionMgr\.register\(\s*['"](\d+)['"]""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""sessionID=(\d+)""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""currentSessionID\s*=\s*['"](\d+)['"]""")
                .find(html)?.groupValues?.getOrNull(1)

    fun extractTotalPages(html: String): Int =
        Regex("""FR\._p\.reportTotalPage\s*=\s*(\d+)""")
            .find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1

    private fun parseTable(html: String): List<TextbookItem> {
        val rows = tableRowRegex.findAll(html)
            .map { rowMatch ->
                tableCellRegex.findAll(rowMatch.groupValues[1])
                    .map { it.groupValues[1].stripTags().htmlDecode().trim() }
                    .toList()
            }
            .filter { it.any(String::isNotBlank) }
            .toList()
        return parseRows(rows)
    }

    private fun parsePositionedDivs(html: String): List<TextbookItem> {
        val cells = divRegex.findAll(html)
            .mapNotNull { match ->
                val attributes = match.groupValues[1]
                val text = match.groupValues[2].stripTags().htmlDecode().trim()
                val left = Regex("""left\s*:\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
                    .find(attributes)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                val top = Regex("""top\s*:\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
                    .find(attributes)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                if (left == null || top == null || text.isBlank()) null else PositionedCell(left, top, text)
            }
            .sortedWith(compareBy<PositionedCell> { it.top }.thenBy { it.left })
            .toList()
        if (cells.isEmpty()) return emptyList()

        val rows = mutableListOf<MutableList<PositionedCell>>()
        for (cell in cells) {
            val row = rows.lastOrNull()
            if (row == null || kotlin.math.abs(row.first().top - cell.top) > 3.0) {
                rows += mutableListOf(cell)
            } else {
                row += cell
            }
        }
        return parseRows(rows.map { row -> row.sortedBy { it.left }.map { it.text } })
    }

    private fun parseRows(rows: List<List<String>>): List<TextbookItem> {
        val headerIndex = rows.indexOfFirst { cells ->
            cells.size >= 2 && cells.any { "课程" in it } && cells.any { "书名" in it || "教材" in it || "ISBN" in it.uppercase() }
        }
        if (headerIndex < 0) return emptyList()
        val colMap = buildColumnMap(rows[headerIndex])
        return rows.drop(headerIndex + 1)
            .mapNotNull { cells ->
                fun col(key: String): String = colMap[key]?.let(cells::getOrNull).orEmpty()
                val courseName = col("course")
                val textbookName = col("textbook")
                if (courseName.isBlank() && textbookName.isBlank()) return@mapNotNull null
                TextbookItem(
                    courseName = courseName,
                    textbookName = textbookName,
                    author = col("author"),
                    publisher = col("publisher"),
                    isbn = col("isbn"),
                    price = col("price"),
                    edition = col("edition"),
                )
            }
    }

    private fun buildColumnMap(headers: List<String>): Map<String, Int> =
        buildMap {
            headers.forEachIndexed { index, header ->
                when {
                    header == "课程名" || ("课程" in header && "名" in header && "号" !in header) -> put("course", index)
                    header == "书名" || "教材名" in header || ("教材" in header && "名" in header) -> put("textbook", index)
                    "主编" in header || "作者" in header || "编者" in header -> put("author", index)
                    "出版社" in header || ("出版" in header && "社" in header) -> put("publisher", index)
                    "ISBN" in header.uppercase() || "书号" in header -> put("isbn", index)
                    "价" in header || "定价" in header -> put("price", index)
                    "版次" in header || "版本" in header -> put("edition", index)
                }
            }
        }

    private fun String.stripTags(): String =
        replace(Regex("<[^>]+>"), "")

    private fun String.htmlDecode(): String =
        replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")

    private data class PositionedCell(
        val left: Double,
        val top: Double,
        val text: String,
    )

    private val tableRowRegex = Regex(
        """<tr\b[^>]*>(.*?)</tr>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val tableCellRegex = Regex(
        """<t[dh]\b[^>]*>(.*?)</t[dh]>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val divRegex = Regex(
        """<div\b([^>]*)>(.*?)</div>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
}
