package com.xjtu.toolbox.shared.parsing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JwappAcademicParserTest {
    @Test
    fun parsesScheduleCoursesAndWeekBits() {
        val courses = JwappScheduleParser.parseCourses(scheduleJson)

        assertEquals(2, courses.size)
        assertEquals("高等数学", courses[0].name)
        assertEquals("王老师", courses[0].teacher)
        assertEquals("主楼-101", courses[0].location)
        assertEquals(listOf(1, 3, 5), courses[0].weeks)
    }

    @Test
    fun parsesExamDateAndTime() {
        val exams = JwappScheduleParser.parseExams(examJson)

        assertEquals(1, exams.size)
        assertEquals("高等数学", exams.single().courseName)
        assertEquals("2026-01-10 09:00-11:00", exams.single().time)
        assertEquals("2026-01-10", exams.single().examDate)
        assertEquals("09:00-11:00", exams.single().examTime)
        assertEquals("MATH1001", exams.single().courseCode)
        assertEquals("主楼-101", exams.single().location)
        assertEquals("12", exams.single().seatNumber)
    }

    @Test
    fun parsesPreciseGradeRowsAndFiltersTerm() {
        val page = JwappGradeParser.parseCjcxPage(gradeJson, termCode = "2025-2026-1")

        assertEquals(3, page.totalSize)
        assertEquals(3, page.rawRowCount)
        assertEquals(2, page.grades.size)
        assertEquals("高等数学", page.grades[0].courseName)
        assertEquals("92", page.grades[0].score)
        assertEquals(3.0, page.grades[0].credit)
        assertEquals(3.9, page.grades[0].gradePoint)
        assertEquals("2025-2026-1", page.grades[0].termCode)
        assertEquals("优秀", page.grades[1].score)
    }

    @Test
    fun parsesTextbookTableAndFineReportMetadata() {
        val textbooks = FineReportTextbookParser.parse(textbookTableHtml)

        assertEquals(2, textbooks.size)
        assertEquals("高等数学", textbooks[0].courseName)
        assertEquals("高等数学 第八版", textbooks[0].textbookName)
        assertEquals("9787040589812", textbooks[0].isbn)
        assertEquals("第八版", textbooks[0].edition)
        assertEquals("58.00 元", textbooks[0].price)
        assertEquals(true, textbooks[0].hasSubstantiveTextbook)
        assertEquals(false, textbooks[1].hasSubstantiveTextbook)
        assertEquals("123456", FineReportTextbookParser.extractSessionId(textbookSessionHtml))
        assertEquals(3, FineReportTextbookParser.extractTotalPages(textbookSessionHtml))
    }

    @Test
    fun parsesTextbookPositionedDivReport() {
        val textbooks = FineReportTextbookParser.parse(textbookDivHtml)

        assertEquals(1, textbooks.size)
        assertEquals("大学物理", textbooks.single().courseName)
        assertEquals("大学物理学", textbooks.single().textbookName)
        assertEquals("张三", textbooks.single().author)
    }

    @Test
    fun rejectsAuthHtml() {
        assertFailsWith<IllegalStateException> {
            JwappScheduleParser.parseCourses("<html>login.xjtu.edu.cn/cas/login</html>")
        }
        assertFailsWith<IllegalStateException> {
            JwappGradeParser.parseCjcxPage("<html>login.xjtu.edu.cn/cas/login</html>")
        }
        assertFailsWith<IllegalStateException> {
            FineReportTextbookParser.parse("<html>login.xjtu.edu.cn/cas/login</html>")
        }
    }

    @Test
    fun rejectsJwappServiceErrorAndMissingRowsInsteadOfReturningEmptyData() {
        val serviceError = """{"code":"1","msg":"service unavailable","datas":{}}"""
        val missingRows = """{"code":"0","datas":{"xskcb":{}}}"""

        assertFailsWith<IllegalStateException> {
            JwappScheduleParser.parseCourses(serviceError)
        }
        assertFailsWith<IllegalStateException> {
            JwappScheduleParser.parseCourses(missingRows)
        }
        assertFailsWith<IllegalStateException> {
            JwappGradeParser.parseCjcxPage("""{"code":"0","datas":{}}""")
        }
    }
}

internal val currentTermJson = """
    {
      "datas": {
        "dqxnxq": {
          "rows": [
            {"DM": "2025-2026-1"}
          ]
        }
      }
    }
""".trimIndent()

internal val scheduleJson = """
    {
      "datas": {
        "xskcb": {
          "rows": [
            {"KCM":"高等数学","SKJS":"王老师","JASMC":"主楼-101","SKZC":"10101","SKXQ":2,"KSJC":1,"JSJC":2},
            {"KCM":"大学物理","SKJS":"李老师","JASMC":"中二-3201","SKZC":"11100","SKXQ":3,"KSJC":3,"JSJC":4}
          ]
        }
      }
    }
""".trimIndent()

internal val examJson = """
    {
      "datas": {
        "wdksap": {
          "rows": [
            {"KCM":"高等数学","KCH":"MATH1001","KSRQ":"2026-01-10 00:00:00","KSSJMS":"2026-01-10 09:00-11:00","JASMC":"主楼-101","ZWH":"12"}
          ]
        }
      }
    }
""".trimIndent()

internal val gradeJson = """
    {
      "code": "0",
      "datas": {
        "xscjcx": {
          "totalSize": 3,
          "rows": [
            {"KCM":"高等数学","XNXQDM":"2025-2026-1","ZCJ":92.0,"XF":3.0,"XFJD":3.9,"DJCJMC":""},
            {"KCM":"大学英语","XNXQDM":"2025-2026-1","ZCJ":95.0,"XF":2.0,"XFJD":4.0,"DJCJMC":"优秀"},
            {"KCM":"线性代数","XNXQDM":"2024-2025-2","ZCJ":88.0,"XF":3.0,"XFJD":3.6,"DJCJMC":""}
          ]
        }
      }
    }
""".trimIndent()

internal val textbookTableHtml = """
    <html>
      <body>
        <table>
          <tr><th>课程名</th><th>书名</th><th>作者</th><th>出版社</th><th>ISBN</th><th>版次</th><th>定价</th></tr>
          <tr><td>高等数学</td><td>高等数学 第八版</td><td>同济大学数学系</td><td>高等教育出版社</td><td>9787040589812</td><td>第八版</td><td>58.00 元</td></tr>
          <tr><td>线性代数</td><td>无教材</td><td></td><td></td><td></td><td></td><td></td></tr>
        </table>
      </body>
    </html>
""".trimIndent()

internal val textbookSessionHtml = """
    <html>
      <script>
        FR.SessionMgr.register('123456', {});
        FR._p.reportTotalPage = 3;
      </script>
    </html>
""".trimIndent()

internal val textbookDivHtml = """
    <html><body>
      <div style="position:absolute;left:10px;top:10px">课程名</div>
      <div style="position:absolute;left:100px;top:10px">教材名</div>
      <div style="position:absolute;left:200px;top:10px">作者</div>
      <div style="position:absolute;left:10px;top:30px">大学物理</div>
      <div style="position:absolute;left:100px;top:30px">大学物理学</div>
      <div style="position:absolute;left:200px;top:30px">张三</div>
    </body></html>
""".trimIndent()
