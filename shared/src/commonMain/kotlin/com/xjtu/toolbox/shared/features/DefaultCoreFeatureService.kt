package com.xjtu.toolbox.shared.features

import com.xjtu.toolbox.shared.auth.AuthManager
import com.xjtu.toolbox.shared.auth.SiteKey
import com.xjtu.toolbox.shared.auth.SiteVerificationRequiredException
import kotlinx.coroutines.CancellationException

class DefaultCoreFeatureService(
    private val authManager: AuthManager,
    private val scheduleRepository: ScheduleRepository,
    private val gradeRepository: GradeRepository,
    private val campusCardRepository: CampusCardRepository,
    private val noticeRepository: NoticeRepository,
    private val emptyRoomRepository: EmptyRoomRepository,
    private val librarySeatRepository: LibrarySeatRepository,
    private val couponRepository: CouponRepository,
    private val schoolCourseRepository: SchoolCourseRepository,
) : CoreFeatureService {
    override suspend fun dashboard(dayOfWeek: Int, noticePageSize: Int): CoreDashboardSnapshot {
        val scheduleSession = authManager.ensureSession(SiteKey.SCHEDULE)
        val todayCourses = scheduleRepository.courses(scheduleSession)
            .filter { it.dayOfWeek == dayOfWeek }
            .sortedWith(compareBy<CourseItem> { it.startSection }.thenBy { it.name })
        val campusCard = try {
            val cardSession = authManager.ensureSession(SiteKey.CAMPUS_CARD)
            campusCardRepository.cardInfo(cardSession)
        } catch (failure: SiteVerificationRequiredException) {
            if (failure.site != SiteKey.CAMPUS_CARD) throw failure
            null
        }
        return CoreDashboardSnapshot(
            todayCourses = todayCourses,
            campusCard = campusCard,
            notices = noticeRepository.notices(page = 1).take(noticePageSize),
        )
    }

    override suspend fun schedule(termCode: String?): ScheduleSnapshot {
        val session = authManager.ensureSession(SiteKey.SCHEDULE)
        return ScheduleSnapshot(
            courses = scheduleRepository.courses(session, termCode),
            exams = scheduleRepository.exams(session, termCode),
            textbooks = try {
                scheduleRepository.textbooks(session, termCode)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                emptyList()
            },
        )
    }

    override suspend fun grades(termCode: String?): GradeSnapshot {
        val session = authManager.ensureSession(SiteKey.GRADE)
        val grades = gradeRepository.grades(session, termCode)
        return GradeSnapshot(
            grades = grades,
            weightedGpa = grades.weightedGpa(),
            totalCredits = grades.sumOf { it.credit },
        )
    }

    override suspend fun gradeDetail(gradeId: String): GradeDetail {
        require(gradeId.isNotBlank()) { "成绩记录 id 不能为空" }
        val session = authManager.ensureSession(SiteKey.GRADE)
        return gradeRepository.gradeDetail(session, gradeId)
    }

    override suspend fun campusCard(page: Int, pageSize: Int): CampusCardSnapshot {
        val session = authManager.ensureSession(SiteKey.CAMPUS_CARD)
        val info = campusCardRepository.cardInfo(session)
        val transactionPage = campusCardRepository.transactionPage(session, page, pageSize)
        return CampusCardSnapshot(
            info = info,
            transactions = transactionPage.records,
            totalTransactions = transactionPage.total,
        )
    }

    override suspend fun notices(page: Int): List<NoticeItem> =
        noticeRepository.notices(page)

    override suspend fun noticePage(page: Int): NoticePage =
        noticeRepository.noticePage(page)

    override suspend fun emptyRooms(campus: String, date: String, sections: IntRange): List<EmptyRoom> =
        emptyRoomRepository.rooms(campus, date, sections)

    override suspend fun librarySeats(areaCode: String?): LibrarySeatSnapshot {
        val session = authManager.ensureSession(SiteKey.LIBRARY)
        return librarySeatRepository.snapshot(session, areaCode)
    }

    override suspend fun bookLibrarySeat(
        seatId: String,
        areaCode: String,
        allowSwap: Boolean,
    ): LibrarySeatBookingResult {
        require(seatId.isNotBlank()) { "座位号不能为空" }
        require(areaCode.isNotBlank()) { "图书馆区域不能为空" }
        val session = authManager.ensureSession(SiteKey.LIBRARY)
        return librarySeatRepository.bookSeat(
            session = session,
            seatId = seatId.trim().uppercase(),
            areaCode = areaCode,
            allowSwap = allowSwap,
        )
    }

    override suspend fun coupons(filter: CouponFilter, page: Int, pageSize: Int): CouponPage {
        require(page >= 1) { "加餐券页码必须从 1 开始" }
        require(pageSize in 1..100) { "加餐券 pageSize 超出范围" }
        val session = authManager.ensureSession(SiteKey.COUPON)
        return couponRepository.coupons(session, filter, page, pageSize)
    }

    override suspend fun schoolCourses(
        termCode: String?,
        courseName: String,
        teacher: String,
        campusCode: String,
        weekday: Int,
        page: Int,
        pageSize: Int,
    ): SchoolCoursePage {
        require(weekday in 0..7) { "课程查询星期必须在 0..7 之间" }
        require(page >= 1) { "课程查询页码必须从 1 开始" }
        require(pageSize in 1..100) { "课程查询 pageSize 超出范围" }
        val session = authManager.ensureSession(SiteKey.SCHEDULE)
        return schoolCourseRepository.courses(
            session = session,
            termCode = termCode,
            courseName = courseName,
            teacher = teacher,
            campusCode = campusCode,
            weekday = weekday,
            page = page,
            pageSize = pageSize,
        )
    }

    private fun List<GradeItem>.weightedGpa(): Double? {
        val credits = sumOf { it.credit }
        if (credits <= 0.0) return null
        return sumOf { it.gradePoint * it.credit } / credits
    }
}
