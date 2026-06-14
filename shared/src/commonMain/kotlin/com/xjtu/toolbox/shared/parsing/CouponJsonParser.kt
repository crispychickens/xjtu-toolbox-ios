package com.xjtu.toolbox.shared.parsing

import com.xjtu.toolbox.shared.features.CouponFilter
import com.xjtu.toolbox.shared.features.CouponPage
import com.xjtu.toolbox.shared.features.CouponRecord

object CouponJsonParser {
    fun parsePage(
        json: String,
        filter: CouponFilter,
        imageBaseUrl: String = "https://egc.xjtu.edu.cn",
    ): CouponPage {
        val root = JsonParser(json).parse().asObjectOrNull()
            ?: error("加餐券数据不是 JSON 对象")
        val data = root["data"]?.asObjectOrNull()
            ?: return CouponPage(filter = filter, total = 0, records = emptyList())
        val records = data["records"]?.asArrayOrNull()
            ?.mapNotNull { it.asObjectOrNull()?.let { record -> parseRecord(record, imageBaseUrl) } }
            .orEmpty()
        return CouponPage(
            filter = filter,
            total = data["total"].safeInt(),
            records = records,
        )
    }

    private fun parseRecord(record: Map<String, JsonValue>, imageBaseUrl: String): CouponRecord =
        CouponRecord(
            sendId = record["sendId"].safeString(),
            showCardId = record["showCardId"].safeString(),
            voucherName = record["voucherName"].safeString(default = "加餐券"),
            typeName = record["typeName"].safeString(default = "加餐券"),
            amountFen = record["tranamt"].safeLong(),
            leftAmountFen = record["ltranamt"].safeLong(),
            leftCount = record["lknumber"].safeInt(),
            startDate = record["startDate"].safeString(),
            endDate = record["endDate"].safeString(),
            imageUrl = normalizeImageUrl(record["pic"].safeString(), imageBaseUrl),
        )

    private fun normalizeImageUrl(pic: String, imageBaseUrl: String): String {
        if (pic.isBlank()) return ""
        return when {
            pic.startsWith("http://", ignoreCase = true) ||
                pic.startsWith("https://", ignoreCase = true) -> pic
            pic.startsWith("/") -> imageBaseUrl.trimEnd('/') + pic
            else -> imageBaseUrl.trimEnd('/') + "/" + pic
        }
    }

    private fun JsonValue?.safeString(default: String = ""): String =
        this?.asStringOrNull()?.trim().orEmpty().ifBlank { default }

    private fun JsonValue?.safeInt(default: Int = 0): Int =
        this?.asIntOrNull()
            ?: this?.asStringOrNull()?.toDoubleOrNull()?.toInt()
            ?: default

    private fun JsonValue?.safeLong(default: Long = 0L): Long =
        this?.asLongOrNull()
            ?: this?.asStringOrNull()?.toDoubleOrNull()?.toLong()
            ?: default
}
