package com.xjtu.toolbox.shared.parsing

import com.xjtu.toolbox.shared.features.NoticeItem

object NoticeHtmlParser {
    fun parse(html: String, source: String, baseUrl: String): List<NoticeItem> {
        if (WireGuards.isAuthHtml(html)) {
            error("notice list HTML expected, got CAS/Safety Verify HTML")
        }

        val listItems = Regex(
            """<li\b[^>]*>(.*?)</li>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(html)
            .map { it.groupValues[1] }
            .toList()

        val parsedItems = listItems.mapNotNull { parseListItem(it, source, baseUrl) }
        if (parsedItems.isNotEmpty()) return parsedItems.distinctBy { "${it.source}|${it.title}|${it.link}" }

        return anchorPattern.findAll(html)
            .mapNotNull { match ->
                val href = match.groupValues[1]
                val inner = match.groupValues[2]
                noticeFromAnchor(
                    href = href,
                    innerHtml = inner,
                    containerHtml = match.value,
                    source = source,
                    baseUrl = baseUrl,
                )
            }
            .distinctBy { "${it.source}|${it.title}|${it.link}" }
            .toList()
    }

    private fun parseListItem(itemHtml: String, source: String, baseUrl: String): NoticeItem? {
        val match = anchorPattern.find(itemHtml) ?: return null
        return noticeFromAnchor(
            href = match.groupValues[1],
            innerHtml = match.groupValues[2],
            containerHtml = itemHtml,
            source = source,
            baseUrl = baseUrl,
        )
    }

    private fun noticeFromAnchor(
        href: String,
        innerHtml: String,
        containerHtml: String,
        source: String,
        baseUrl: String,
    ): NoticeItem? {
        if (href.isBlank() || href == "#" || href.startsWith("javascript", ignoreCase = true)) return null
        val normalizedHref = htmlDecode(href.trim())
        if (!looksLikeNoticeLink(normalizedHref) && !hasDate(containerHtml)) return null

        val title = titleFromAnchor(innerHtml, containerHtml)
        if (title.length < 4) return null

        return NoticeItem(
            title = title,
            link = resolveUrl(baseUrl, normalizedHref),
            source = source,
            date = extractDate(containerHtml),
        )
    }

    private fun titleFromAnchor(innerHtml: String, containerHtml: String): String {
        val explicitTitle = Regex(
            """\btitle\s*=\s*["']([^"']+)["']""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(containerHtml)?.groupValues?.getOrNull(1)
        if (!explicitTitle.isNullOrBlank()) return htmlDecode(explicitTitle).trim()

        return htmlDecode(innerHtml.stripTags())
            .replace(dateRegex, "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', '–', '—', '[', ']', '【', '】')
    }

    private fun extractDate(html: String): String? =
        dateRegex.find(htmlDecode(html.stripTags()))?.value?.replace('/', '-')?.replace('.', '-')

    private fun hasDate(html: String): Boolean =
        dateRegex.containsMatchIn(htmlDecode(html.stripTags()))

    private fun looksLikeNoticeLink(href: String): Boolean =
        href.contains("/info/", ignoreCase = true) ||
            href.contains("content.jsp", ignoreCase = true)

    private fun resolveUrl(baseUrl: String, href: String): String {
        if (href.startsWith("http://", ignoreCase = true) || href.startsWith("https://", ignoreCase = true)) {
            return href
        }
        val schemeEnd = baseUrl.indexOf("://")
        if (schemeEnd < 0) return href
        val scheme = baseUrl.substring(0, schemeEnd)
        val afterScheme = baseUrl.substring(schemeEnd + 3)
        val host = afterScheme.substringBefore("/")
        val origin = "$scheme://$host"
        if (href.startsWith("/")) return origin + href

        val basePath = afterScheme.substringAfter("/", missingDelimiterValue = "")
        val directory = basePath.substringBeforeLast("/", missingDelimiterValue = "")
        val combined = if (directory.isBlank()) href else "$directory/$href"
        return normalizePath(origin, combined)
    }

    private fun normalizePath(origin: String, path: String): String {
        val parts = mutableListOf<String>()
        for (part in path.split("/")) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return "$origin/${parts.joinToString("/")}"
    }

    private fun htmlDecode(value: String): String =
        value.replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")

    private fun String.stripTags(): String =
        replace(Regex("<[^>]+>"), "")

    private val anchorPattern = Regex(
        """<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    private val dateRegex = Regex("""\d{4}[-./]\d{1,2}[-./]\d{1,2}""")
}
