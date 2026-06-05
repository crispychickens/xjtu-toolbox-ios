package com.xjtu.toolbox.shared.parsing

import com.xjtu.toolbox.shared.features.CampusCardInfo
import com.xjtu.toolbox.shared.features.CampusCardTransaction
import com.xjtu.toolbox.shared.features.CampusCardTransactionPage
import com.xjtu.toolbox.shared.features.TransactionKind
import kotlin.math.abs

object CampusCardNcardParser {
    fun parseCardInfo(json: String, holderName: String): CampusCardInfo {
        val root = parseRoot(json, "campus card info")
        requireOk(root, "campus card info")
        val data = root["data"]?.asObjectOrNull()
            ?: error("campus card info response data missing")
        val card = data["card"]?.asArrayOrNull()
            ?.firstOrNull()
            ?.asObjectOrNull()
            ?: error("campus card info response has no card")

        return CampusCardInfo(
            balanceYuan = centsToYuan(card["elec_accamt"]?.asLongOrNull() ?: 0L),
            holderName = holderName.ifBlank {
                card["name"]?.asStringOrNull()?.trim().orEmpty()
            },
        )
    }

    fun parseTransactions(json: String): CampusCardTransactionPage {
        val root = parseRoot(json, "campus card transactions")
        requireOk(root, "campus card transactions")
        val data = root["data"]?.asObjectOrNull()
            ?: error("campus card transactions response data missing")
        val total = data["total"]?.asIntOrNull() ?: 0
        val records = data["records"]?.asArrayOrNull()
            ?: error("campus card transactions response records missing")
        val transactions = records
            .mapNotNull { it.asObjectOrNull() }
            .map { record ->
                val cents = record["tranamt"]?.asLongOrNull() ?: 0L
                val icon = record["icon"]?.asStringOrNull().orEmpty()
                val turnoverType = record["turnoverType"]?.asStringOrNull()?.trim().orEmpty()
                val kind = if (isIncome(icon, turnoverType)) TransactionKind.INCOME else TransactionKind.EXPENSE
                val amount = centsToYuan(abs(cents))
                CampusCardTransaction(
                    time = record["jndatetimeStr"]?.asStringOrNull().orEmpty(),
                    merchant = merchantName(record),
                    amountYuan = if (kind == TransactionKind.INCOME) amount else -amount,
                    kind = kind,
                )
            }

        return CampusCardTransactionPage(total = total, records = transactions)
    }

    private fun parseRoot(json: String, expectedPayload: String): Map<String, JsonValue> {
        if (WireGuards.isAuthHtml(json)) {
            error("$expectedPayload JSON expected, got CAS/Safety Verify HTML")
        }
        return JsonParser(json).parse().asObjectOrNull()
            ?: error("$expectedPayload payload must be a JSON object")
    }

    private fun requireOk(root: Map<String, JsonValue>, serviceName: String) {
        val code = root["code"]?.asIntOrNull()
            ?: error("$serviceName response code missing")
        when (code) {
            200 -> return
            401 -> error("$serviceName auth expired")
            else -> {
                val message = root["message"]?.asStringOrNull()?.trim().orEmpty()
                error(
                    if (message.isBlank()) {
                        "$serviceName returned code $code"
                    } else {
                        "$serviceName returned code $code: $message"
                    },
                )
            }
        }
    }

    private fun isIncome(icon: String, turnoverType: String): Boolean =
        icon.equals("recharge", ignoreCase = true) ||
            turnoverType.contains("充值") ||
            turnoverType.contains("圈存")

    private fun merchantName(record: Map<String, JsonValue>): String {
        val merchant = record["toMerchant"]?.asStringOrNull()?.trim()
        if (!merchant.isNullOrBlank()) return merchant
        return record["resume"]?.asStringOrNull()
            ?.substringBefore("-")
            ?.trim()
            .orEmpty()
    }

    private fun centsToYuan(cents: Long): Double =
        cents / 100.0
}
