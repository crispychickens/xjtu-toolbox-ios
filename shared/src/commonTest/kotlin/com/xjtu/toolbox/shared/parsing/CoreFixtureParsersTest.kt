package com.xjtu.toolbox.shared.parsing

import com.xjtu.toolbox.shared.features.TransactionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CoreFixtureParsersTest {
    @Test
    fun parsesCoreFeatureFixtures() {
        val courses = CoreFixtureParsers.parseScheduleCsv("高等数学,张三,主楼101,1,1,2,1-3;5")
        assertEquals(listOf(1, 2, 3, 5), courses.single().weeks)

        val grades = CoreFixtureParsers.parseGradeRows("高等数学|95|5.0|4.0")
        assertEquals(4.0, grades.single().gradePoint)

        val tx = CoreFixtureParsers.parseCampusCardRows("2026-05-19 12:00|付款码消费|10.00|付款码")
        assertEquals(TransactionKind.EXPENSE, tx.single().kind)
        assertEquals(-10.0, tx.single().amountYuan)

        val notices = CoreFixtureParsers.parseNoticeAnchors(
            """<a href="/n/1">关于考试安排的通知 2026-05-19</a>""",
            source = "教务处",
        )
        assertEquals("教务处", notices.single().source)

        val rooms = CoreFixtureParsers.parseEmptyRoomRows("主楼101|兴庆|主楼|1;2;3")
        assertEquals(listOf(1, 2, 3), rooms.single().availableSections)
    }

    @Test
    fun rejectsCasHtmlInsteadOfParsingItAsBusinessData() {
        assertFailsWith<IllegalStateException> {
            CoreFixtureParsers.parseGradeRows("<html>login.xjtu.edu.cn/cas/login</html>")
        }
    }
}
