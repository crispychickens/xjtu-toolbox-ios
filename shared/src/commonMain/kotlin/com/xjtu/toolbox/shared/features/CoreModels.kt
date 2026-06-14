package com.xjtu.toolbox.shared.features

import com.xjtu.toolbox.shared.auth.SiteSession

data class CoreDashboardSnapshot(
    val todayCourses: List<CourseItem>,
    val campusCard: CampusCardInfo?,
    val notices: List<NoticeItem>,
)

data class ScheduleSnapshot(
    val courses: List<CourseItem>,
    val exams: List<ExamItem>,
    val textbooks: List<TextbookItem> = emptyList(),
)

data class GradeSnapshot(
    val grades: List<GradeItem>,
    val weightedGpa: Double?,
    val totalCredits: Double,
)

data class CampusCardSnapshot(
    val info: CampusCardInfo,
    val transactions: List<CampusCardTransaction>,
    val totalTransactions: Int = transactions.size,
)

data class CourseItem(
    val name: String,
    val teacher: String,
    val location: String,
    val dayOfWeek: Int,
    val startSection: Int,
    val endSection: Int,
    val weeks: List<Int>,
)

data class ExamItem(
    val courseName: String,
    val time: String,
    val location: String,
    val courseCode: String = "",
    val examDate: String = "",
    val examTime: String = "",
    val seatNumber: String = "",
)

data class TextbookItem(
    val courseName: String,
    val textbookName: String,
    val author: String = "",
    val publisher: String = "",
    val isbn: String = "",
    val price: String = "",
    val edition: String = "",
) {
    val hasSubstantiveTextbook: Boolean
        get() = textbookName.trim() != "无教材" &&
            (textbookName.trim().length >= 2 || isbn.any { it.isDigit() } || author.trim().length >= 2)
}

data class GradeItem(
    val courseName: String,
    val score: String,
    val credit: Double,
    val gradePoint: Double,
    val id: String = "",
    val termCode: String = "",
)

data class GradeDetailItem(
    val name: String,
    val percent: Double,
    val score: String,
)

data class GradeDetail(
    val courseName: String,
    val score: String,
    val credit: Double,
    val gradePoint: Double,
    val examType: String,
    val courseProperty: String?,
    val examProperty: String,
    val isReplacement: Boolean,
    val isPassed: Boolean,
    val specificReason: String?,
    val items: List<GradeDetailItem>,
)

data class CampusCardInfo(
    val balanceYuan: Double,
    val holderName: String,
)

data class CampusCardTransaction(
    val time: String,
    val merchant: String,
    val amountYuan: Double,
    val kind: TransactionKind,
    val balanceAfterYuan: Double? = null,
)

data class CampusCardTransactionPage(
    val total: Int,
    val records: List<CampusCardTransaction>,
)

enum class TransactionKind {
    EXPENSE,
    INCOME,
}

data class NoticeItem(
    val title: String,
    val link: String,
    val source: String,
    val date: String?,
)

data class NoticePage(
    val total: Int,
    val records: List<NoticeItem>,
)

data class EmptyRoom(
    val name: String,
    val campus: String,
    val building: String,
    val availableSections: List<Int>,
    val capacity: Int = 0,
)

data class LibraryAreaStats(
    val code: String,
    val name: String,
    val floor: String,
    val available: Int,
    val total: Int,
) {
    val isOpen: Boolean
        get() = total > 0

    val availabilityRate: Double
        get() = if (total > 0) available.toDouble() / total else 0.0
}

data class LibrarySeatItem(
    val seatId: String,
    val available: Boolean,
)

data class LibraryBookingInfo(
    val seatId: String,
    val areaName: String?,
    val statusText: String?,
)

data class LibrarySeatSnapshot(
    val selectedAreaCode: String,
    val areas: List<LibraryAreaStats>,
    val seats: List<LibrarySeatItem>,
    val recommendedAreas: List<LibraryAreaStats>,
    val myBooking: LibraryBookingInfo?,
)

data class LibrarySeatBookingResult(
    val success: Boolean,
    val message: String,
    val finalUrl: String = "",
)

enum class CouponFilter(
    val label: String,
    val status: String,
    val count: String,
    val expired: String,
) {
    AVAILABLE("待使用", "0", "", "3"),
    USABLE("可使用", "1", "1", "3"),
    USED_UP("已用完", "", "0", ""),
    EXPIRED("已过期", "", "1", "2"),
}

data class CouponRecord(
    val sendId: String,
    val showCardId: String,
    val voucherName: String,
    val typeName: String,
    val amountFen: Long,
    val leftAmountFen: Long,
    val leftCount: Int,
    val startDate: String,
    val endDate: String,
    val imageUrl: String = "",
) {
    val amountYuan: Double
        get() = amountFen / 100.0

    val leftAmountYuan: Double
        get() = leftAmountFen / 100.0
}

data class CouponPage(
    val filter: CouponFilter,
    val total: Int,
    val records: List<CouponRecord>,
)

data class SchoolCourseItem(
    val courseCode: String,
    val courseName: String,
    val sectionNumber: String,
    val teacher: String,
    val department: String,
    val credit: Double,
    val enrollCount: Int,
    val capacity: Int,
    val scheduleLocation: String,
    val campus: String,
    val teachingClassId: String,
    val termCode: String,
)

data class SchoolCoursePage(
    val termCode: String,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val records: List<SchoolCourseItem>,
) {
    val totalPages: Int
        get() = if (pageSize > 0) (total + pageSize - 1) / pageSize else 0
}

interface ScheduleRepository {
    suspend fun courses(session: SiteSession, termCode: String? = null): List<CourseItem>
    suspend fun exams(session: SiteSession, termCode: String? = null): List<ExamItem>
    suspend fun textbooks(session: SiteSession, termCode: String? = null): List<TextbookItem> = emptyList()
}

interface GradeRepository {
    suspend fun grades(session: SiteSession, termCode: String? = null): List<GradeItem>
    suspend fun gradeDetail(session: SiteSession, gradeId: String): GradeDetail =
        error("当前成绩来源不支持分项详情")
}

interface CampusCardRepository {
    suspend fun cardInfo(session: SiteSession): CampusCardInfo
    suspend fun transactions(session: SiteSession, page: Int, pageSize: Int): List<CampusCardTransaction>
    suspend fun transactionPage(session: SiteSession, page: Int, pageSize: Int): CampusCardTransactionPage {
        val records = transactions(session, page, pageSize)
        return CampusCardTransactionPage(total = records.size, records = records)
    }
}

interface NoticeRepository {
    suspend fun notices(page: Int): List<NoticeItem>
    suspend fun noticePage(page: Int): NoticePage {
        val records = notices(page)
        return NoticePage(total = records.size, records = records)
    }
}

interface EmptyRoomRepository {
    suspend fun rooms(campus: String, date: String, sections: IntRange): List<EmptyRoom>
}

interface LibrarySeatRepository {
    suspend fun snapshot(session: SiteSession, areaCode: String? = null): LibrarySeatSnapshot
    suspend fun bookSeat(
        session: SiteSession,
        seatId: String,
        areaCode: String,
        allowSwap: Boolean = true,
    ): LibrarySeatBookingResult
}

interface CouponRepository {
    suspend fun coupons(
        session: SiteSession,
        filter: CouponFilter,
        page: Int,
        pageSize: Int,
    ): CouponPage
}

interface SchoolCourseRepository {
    suspend fun courses(
        session: SiteSession,
        termCode: String?,
        courseName: String,
        teacher: String,
        campusCode: String,
        weekday: Int,
        page: Int,
        pageSize: Int,
    ): SchoolCoursePage
}

interface CoreFeatureService {
    @Throws(Exception::class)
    suspend fun dashboard(dayOfWeek: Int, noticePageSize: Int = 5): CoreDashboardSnapshot

    @Throws(Exception::class)
    suspend fun schedule(termCode: String? = null): ScheduleSnapshot

    @Throws(Exception::class)
    suspend fun grades(termCode: String? = null): GradeSnapshot

    @Throws(Exception::class)
    suspend fun gradeDetail(gradeId: String): GradeDetail

    @Throws(Exception::class)
    suspend fun campusCard(page: Int = 1, pageSize: Int = 20): CampusCardSnapshot

    @Throws(Exception::class)
    suspend fun notices(page: Int = 1): List<NoticeItem>

    @Throws(Exception::class)
    suspend fun noticePage(page: Int = 1): NoticePage {
        val records = notices(page)
        return NoticePage(total = records.size, records = records)
    }

    @Throws(Exception::class)
    suspend fun emptyRooms(campus: String, date: String, sections: IntRange): List<EmptyRoom>

    @Throws(Exception::class)
    suspend fun librarySeats(areaCode: String? = null): LibrarySeatSnapshot

    @Throws(Exception::class)
    suspend fun bookLibrarySeat(
        seatId: String,
        areaCode: String,
        allowSwap: Boolean = true,
    ): LibrarySeatBookingResult

    @Throws(Exception::class)
    suspend fun coupons(
        filter: CouponFilter = CouponFilter.USABLE,
        page: Int = 1,
        pageSize: Int = 20,
    ): CouponPage

    @Throws(Exception::class)
    suspend fun schoolCourses(
        termCode: String? = null,
        courseName: String = "",
        teacher: String = "",
        campusCode: String = "",
        weekday: Int = 0,
        page: Int = 1,
        pageSize: Int = 20,
    ): SchoolCoursePage
}
