package com.xjtu.toolbox.shared.parsing

import com.xjtu.toolbox.shared.features.LibraryAreaStats
import com.xjtu.toolbox.shared.features.LibraryBookingInfo
import com.xjtu.toolbox.shared.features.LibrarySeatItem
import com.xjtu.toolbox.shared.features.LibrarySeatSnapshot

object LibrarySeatAreas {
    const val DEFAULT_AREA_CODE: String = "north2east"

    val areas: List<LibraryAreaDefinition> = listOf(
        LibraryAreaDefinition("north2east", "北楼二层外文库（东）", "二楼"),
        LibraryAreaDefinition("north2elian", "二层连廊及流通大厅", "二楼"),
        LibraryAreaDefinition("north2west", "北楼二层外文库（西）", "二楼"),
        LibraryAreaDefinition("south2", "南楼二层大厅", "二楼"),
        LibraryAreaDefinition("west3B", "北楼三层ILibrary-B（西）", "三楼"),
        LibraryAreaDefinition("eastnorthda", "大屏辅学空间", "三楼"),
        LibraryAreaDefinition("south3middle", "南楼三层中段", "三楼"),
        LibraryAreaDefinition("east3A", "北楼三层ILibrary-A（东）", "三楼"),
        LibraryAreaDefinition("north4west", "北楼四层西侧", "四楼"),
        LibraryAreaDefinition("north4middle", "北楼四层中间", "四楼"),
        LibraryAreaDefinition("north4east", "北楼四层东侧", "四楼"),
        LibraryAreaDefinition("north4southwest", "北楼四层西南侧", "四楼"),
        LibraryAreaDefinition("north4southeast", "北楼四层东南侧", "四楼"),
    )

    val byCode: Map<String, LibraryAreaDefinition> = areas.associateBy { it.code }

    fun displayName(code: String): String? = byCode[code]?.name
}

data class LibraryAreaDefinition(
    val code: String,
    val name: String,
    val floor: String,
)

object LibrarySeatParser {
    fun parseSeatSnapshot(
        json: String,
        selectedAreaCode: String?,
        bookingHtml: String? = null,
    ): LibrarySeatSnapshot {
        val root = JsonParser(json).parse().asObjectOrNull()
            ?: error("图书馆座位数据不是 JSON 对象")
        val selected = selectedAreaCode
            ?.takeIf { it in LibrarySeatAreas.byCode }
            ?: LibrarySeatAreas.DEFAULT_AREA_CODE
        val areas = parseAreaStats(root)
        val seats = parseSeats(root)
        return LibrarySeatSnapshot(
            selectedAreaCode = selected,
            areas = areas,
            seats = seats,
            recommendedAreas = areas
                .filter { it.isOpen && it.available > 0 }
                .sortedWith(
                    compareByDescending<LibraryAreaStats> { it.availabilityRate }
                        .thenByDescending { it.available }
                        .thenBy { it.name },
                )
                .take(3),
            myBooking = bookingHtml?.let(::parseBooking),
        )
    }

    fun parseBooking(html: String): LibraryBookingInfo? {
        val bodyText = html.stripTags().normalizeSpaces()
        if (bodyText.isBlank()) return null
        if (NO_BOOKING_MARKERS.any { it in bodyText }) return null

        val statusRanges = BOOKING_STATUS_REGEX.findAll(bodyText).toList()
        if (statusRanges.isEmpty()) {
            val seatId = SEAT_ID_REGEX.find(bodyText)?.value ?: return null
            return LibraryBookingInfo(
                seatId = seatId,
                areaName = LibrarySeatAreas.areas.firstOrNull { it.name in bodyText }?.name,
                statusText = null,
            )
        }

        var blockStart = 0
        for (match in statusRanges) {
            val status = match.groupValues[1].trim()
            val blockEnd = match.range.last + 1
            val blockText = bodyText.substring(blockStart, blockEnd)
            blockStart = blockEnd
            if (status in INACTIVE_STATUSES) continue
            val seatId = SEAT_ID_REGEX.findAll(blockText).lastOrNull()?.value ?: continue
            return LibraryBookingInfo(
                seatId = seatId,
                areaName = LibrarySeatAreas.areas.firstOrNull { it.name in blockText }?.name,
                statusText = status,
            )
        }
        return null
    }

    fun parseBookingFailure(html: String): String {
        val text = html.stripTags().normalizeSpaces()
        return when {
            text.isBlank() -> "预约失败，服务器未返回原因"
            "30分钟" in text || "30 min" in text -> "30 分钟内不能重复预约"
            "已被预约" in text || "已被占" in text -> "该座位已被他人预约"
            "已有预约" in text || "已预约" in text || "已经预约" in text -> "你已有其他座位预约"
            "不在预约时间" in text || "未开放" in text -> "当前不在预约开放时间"
            "维护" in text -> "图书馆座位系统维护中"
            "cas/login" in text || "login.xjtu.edu.cn" in text -> "登录状态已失效"
            else -> text.take(80)
        }
    }

    private fun parseAreaStats(root: Map<String, JsonValue>): List<LibraryAreaStats> {
        val scount = root["scount"]?.asObjectOrNull().orEmpty()
        return LibrarySeatAreas.areas.map { definition ->
            val values = scount[definition.code]?.asArrayOrNull().orEmpty()
            LibraryAreaStats(
                code = definition.code,
                name = definition.name,
                floor = definition.floor,
                total = values.getOrNull(0)?.asIntOrNull() ?: 0,
                available = values.getOrNull(1)?.asIntOrNull() ?: 0,
            )
        }
    }

    private fun parseSeats(root: Map<String, JsonValue>): List<LibrarySeatItem> {
        val seatObject = root["seat"]?.asObjectOrNull().orEmpty()
        return seatObject.mapNotNull { (seatId, statusValue) ->
            if (!SEAT_ID_REGEX.matches(seatId)) return@mapNotNull null
            LibrarySeatItem(
                seatId = seatId,
                available = statusValue.asIntOrNull() == 0,
            )
        }.sortedWith(
            compareBy<LibrarySeatItem> { item -> item.seatId.takeWhile { it.isLetter() } }
                .thenBy { item -> item.seatId.dropWhile { it.isLetter() }.toIntOrNull() ?: 0 }
                .thenBy { it.seatId },
        )
    }

    private fun String.stripTags(): String =
        replace(Regex("""<script\b[^>]*>.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("""<style\b[^>]*>.*?</style>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("""<[^>]+>"""), " ")
            .htmlDecode()

    private fun String.htmlDecode(): String =
        replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")

    private fun String.normalizeSpaces(): String =
        replace(Regex("""\s+"""), " ").trim()

    private const val SEAT_ID_PATTERN = """(?:[A-Z]\d{2,4}|\b\d{3}\b)"""
    private val SEAT_ID_REGEX = Regex(SEAT_ID_PATTERN)
    private val BOOKING_STATUS_REGEX = Regex("""预约状态[:：]\s*(\S+)""")
    private val NO_BOOKING_MARKERS = listOf("暂无预约", "没有预约", "无预约")
    private val INACTIVE_STATUSES = setOf("已取消", "已完成", "已过期", "已失效", "已违约", "超时取消", "超时未入馆", "超时", "已离馆")
}
