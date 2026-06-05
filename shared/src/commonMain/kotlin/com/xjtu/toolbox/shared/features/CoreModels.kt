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

data class EmptyRoom(
    val name: String,
    val campus: String,
    val building: String,
    val availableSections: List<Int>,
)

interface ScheduleRepository {
    suspend fun courses(session: SiteSession, termCode: String? = null): List<CourseItem>
    suspend fun exams(session: SiteSession, termCode: String? = null): List<ExamItem>
    suspend fun textbooks(session: SiteSession, termCode: String? = null): List<TextbookItem> = emptyList()
}

interface GradeRepository {
    suspend fun grades(session: SiteSession, termCode: String? = null): List<GradeItem>
}

interface CampusCardRepository {
    suspend fun cardInfo(session: SiteSession): CampusCardInfo
    suspend fun transactions(session: SiteSession, page: Int, pageSize: Int): List<CampusCardTransaction>
}

interface NoticeRepository {
    suspend fun notices(page: Int): List<NoticeItem>
}

interface EmptyRoomRepository {
    suspend fun rooms(campus: String, date: String, sections: IntRange): List<EmptyRoom>
}

interface CoreFeatureService {
    @Throws(Exception::class)
    suspend fun dashboard(dayOfWeek: Int, noticePageSize: Int = 5): CoreDashboardSnapshot

    @Throws(Exception::class)
    suspend fun schedule(termCode: String? = null): ScheduleSnapshot

    @Throws(Exception::class)
    suspend fun grades(termCode: String? = null): GradeSnapshot

    @Throws(Exception::class)
    suspend fun campusCard(page: Int = 1, pageSize: Int = 20): CampusCardSnapshot

    @Throws(Exception::class)
    suspend fun notices(page: Int = 1): List<NoticeItem>

    @Throws(Exception::class)
    suspend fun emptyRooms(campus: String, date: String, sections: IntRange): List<EmptyRoom>
}
