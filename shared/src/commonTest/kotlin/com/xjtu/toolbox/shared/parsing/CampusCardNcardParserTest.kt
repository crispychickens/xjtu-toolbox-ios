package com.xjtu.toolbox.shared.parsing

import com.xjtu.toolbox.shared.features.TransactionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CampusCardNcardParserTest {
    @Test
    fun parsesCardInfoBalanceFromCentAmount() {
        val info = CampusCardNcardParser.parseCardInfo(
            json = cardInfoJson,
            holderName = "学生",
        )

        assertEquals(42.5, info.balanceYuan)
        assertEquals("学生", info.holderName)
    }

    @Test
    fun parsesTransactionDirectionWithoutTreatingPaymentCodeAsIncome() {
        val page = CampusCardNcardParser.parseTransactions(transactionJson)

        assertEquals(4, page.total)
        assertEquals(4, page.records.size)
        assertEquals(TransactionKind.EXPENSE, page.records[0].kind)
        assertEquals(-12.0, page.records[0].amountYuan)
        assertEquals("康桥苑", page.records[0].merchant)
        assertEquals(TransactionKind.EXPENSE, page.records[1].kind)
        assertEquals(-3.5, page.records[1].amountYuan)
        assertEquals(TransactionKind.INCOME, page.records[2].kind)
        assertEquals(100.0, page.records[2].amountYuan)
        assertEquals(TransactionKind.INCOME, page.records[3].kind)
        assertEquals(50.0, page.records[3].amountYuan)
    }

    @Test
    fun rejectsAuthHtml() {
        assertFailsWith<IllegalStateException> {
            CampusCardNcardParser.parseTransactions("<html>login.xjtu.edu.cn/cas/login</html>")
        }
    }

    @Test
    fun rejectsExpiredCampusCardToken() {
        assertFailsWith<IllegalStateException> {
            CampusCardNcardParser.parseTransactions("""{"code":401,"message":"unauthorized"}""")
        }
    }

    @Test
    fun rejectsMalformedTransactionPayloadInsteadOfReturningEmptyRecords() {
        assertFailsWith<IllegalStateException> {
            CampusCardNcardParser.parseTransactions("""{"code":200,"message":"ok"}""")
        }

        assertFailsWith<IllegalStateException> {
            CampusCardNcardParser.parseTransactions("""{"code":200,"message":"ok","data":{"total":0}}""")
        }
    }

    @Test
    fun acceptsEmptyTransactionRecordsAsLegitimateEmptyPage() {
        val page = CampusCardNcardParser.parseTransactions(
            """{"code":200,"message":"ok","data":{"total":0,"records":[]}}""",
        )

        assertEquals(0, page.total)
        assertEquals(emptyList(), page.records)
    }
}

private val cardInfoJson = """
    {
      "code": 200,
      "message": "ok",
      "data": {
        "card": [
          {
            "elec_accamt": 4250,
            "unsettle_amount": 300,
            "barflag": 0,
            "freezeflag": 0,
            "expdate": "20280101",
            "cardname": "学生卡"
          }
        ]
      }
    }
""".trimIndent()

private val transactionJson = """
    {
      "code": 200,
      "message": "ok",
      "data": {
        "total": 4,
        "records": [
          {
            "jndatetimeStr": "2026-05-20 12:00:00",
            "toMerchant": "康桥苑",
            "tranamt": 1200,
            "cardBalance": 3050,
            "icon": "consume",
            "turnoverType": "消费",
            "resume": "康桥苑-午餐"
          },
          {
            "jndatetimeStr": "2026-05-20 12:05:00",
            "tranamt": 350,
            "cardBalance": 2700,
            "icon": "qrcode",
            "turnoverType": "",
            "resume": "付款码消费-康桥苑"
          },
          {
            "jndatetimeStr": "2026-05-20 13:00:00",
            "toMerchant": "充值",
            "tranamt": 10000,
            "cardBalance": 12700,
            "icon": "recharge",
            "turnoverType": "充值",
            "resume": "线上充值"
          },
          {
            "jndatetimeStr": "2026-05-20 14:00:00",
            "toMerchant": "圈存",
            "tranamt": 5000,
            "cardBalance": 17700,
            "icon": "",
            "turnoverType": "圈存",
            "resume": "圈存入账"
          }
        ]
      }
    }
""".trimIndent()
