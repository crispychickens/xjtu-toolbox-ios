package com.xjtu.toolbox.shared.features

import com.xjtu.toolbox.shared.auth.AccessMode
import com.xjtu.toolbox.shared.auth.AuthManager
import com.xjtu.toolbox.shared.auth.AuthState
import com.xjtu.toolbox.shared.auth.InMemorySiteSession
import com.xjtu.toolbox.shared.auth.LoginOutcome
import com.xjtu.toolbox.shared.auth.SiteKey
import com.xjtu.toolbox.shared.auth.SiteSession
import com.xjtu.toolbox.shared.auth.SiteVerificationRequiredException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DefaultCoreFeatureServiceTest {
    @Test
    fun dashboardRoutesThroughScheduleAndCampusCardSessions() = runTest {
        val auth = RecordingAuthManager()
        val service = service(auth = auth)

        val dashboard = service.dashboard(dayOfWeek = 2, noticePageSize = 1)

        assertEquals(listOf(SiteKey.SCHEDULE, SiteKey.CAMPUS_CARD), auth.requestedSites)
        assertEquals(listOf("高等数学", "大学物理"), dashboard.todayCourses.map { it.name })
        assertEquals(42.5, dashboard.campusCard?.balanceYuan)
        assertEquals(1, dashboard.notices.size)
    }

    @Test
    fun dashboardKeepsScheduleAndNoticesWhenCampusCardNeedsSiteVerification() = runTest {
        val auth = RecordingAuthManager(siteVerificationRequiredFor = SiteKey.CAMPUS_CARD)
        val service = service(auth = auth)

        val dashboard = service.dashboard(dayOfWeek = 2, noticePageSize = 1)

        assertEquals(listOf(SiteKey.SCHEDULE, SiteKey.CAMPUS_CARD), auth.requestedSites)
        assertEquals(listOf("高等数学", "大学物理"), dashboard.todayCourses.map { it.name })
        assertNull(dashboard.campusCard)
        assertEquals(1, dashboard.notices.size)
        val state = assertIs<AuthState.SiteVerificationRequired>(auth.authState.value)
        assertEquals(SiteKey.CAMPUS_CARD, state.site)
    }

    @Test
    fun gradesComputesWeightedGpaBehindServiceInterface() = runTest {
        val service = service()

        val snapshot = service.grades()

        assertEquals(5.0, snapshot.totalCredits)
        assertEquals(3.64, round2(snapshot.weightedGpa ?: 0.0))
    }

    @Test
    fun gradeDetailRoutesThroughGradeSession() = runTest {
        val auth = RecordingAuthManager()
        val service = service(auth = auth)

        val detail = service.gradeDetail("grade-1")

        assertEquals(listOf(SiteKey.GRADE), auth.requestedSites)
        assertEquals("高等数学", detail.courseName)
        assertEquals(2, detail.items.size)
    }

    @Test
    fun scheduleAndCampusCardHideSiteSessionFromCallers() = runTest {
        val auth = RecordingAuthManager()
        val service = service(auth = auth)

        service.schedule(termCode = "2025-2026-1")
        val campusCard = service.campusCard(page = 1, pageSize = 10)

        assertEquals(
            listOf(SiteKey.SCHEDULE, SiteKey.CAMPUS_CARD),
            auth.requestedSites,
        )
        assertEquals(37, campusCard.totalTransactions)
    }

    @Test
    fun librarySeatsRoutesThroughLibrarySession() = runTest {
        val auth = RecordingAuthManager()
        val service = service(auth = auth)

        val snapshot = service.librarySeats(areaCode = "north4middle")

        assertEquals(listOf(SiteKey.LIBRARY), auth.requestedSites)
        assertEquals("north4middle", snapshot.selectedAreaCode)
        assertEquals("北楼四层中间", snapshot.recommendedAreas.first().name)
    }

    @Test
    fun couponsRouteThroughCouponSession() = runTest {
        val auth = RecordingAuthManager()
        val service = service(auth = auth)

        val page = service.coupons(filter = CouponFilter.USABLE, page = 1, pageSize = 10)

        assertEquals(listOf(SiteKey.COUPON), auth.requestedSites)
        assertEquals(CouponFilter.USABLE, page.filter)
        assertEquals(1, page.total)
        assertEquals("康桥苑加餐券", page.records.single().voucherName)
    }

    @Test
    fun couponsRejectInvalidPagingBeforeOpeningSession() = runTest {
        val auth = RecordingAuthManager()
        val service = service(auth = auth)

        kotlin.test.assertFailsWith<IllegalArgumentException> {
            service.coupons(filter = CouponFilter.USABLE, page = 0, pageSize = 10)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            service.coupons(filter = CouponFilter.USABLE, page = 1, pageSize = 101)
        }
        assertEquals(emptyList(), auth.requestedSites)
    }

    @Test
    fun schoolCoursesRouteThroughScheduleSession() = runTest {
        val auth = RecordingAuthManager()
        val service = service(auth = auth)

        val page = service.schoolCourses(
            courseName = "高等数学",
            teacher = "",
            campusCode = "1",
            weekday = 3,
            page = 1,
            pageSize = 20,
        )

        assertEquals(listOf(SiteKey.SCHEDULE), auth.requestedSites)
        assertEquals("高等数学", page.records.single().courseName)
        assertEquals(1, page.total)
    }

    @Test
    fun scheduleKeepsCoursesAndExamsWhenTextbooksFail() = runTest {
        val service = service(scheduleRepository = TextbookFailingScheduleRepository())

        val snapshot = service.schedule(termCode = "2025-2026-1")

        assertEquals(listOf("高等数学"), snapshot.courses.map { it.name })
        assertEquals(listOf("高等数学"), snapshot.exams.map { it.courseName })
        assertEquals(emptyList(), snapshot.textbooks)
    }

    @Test
    fun noticePagePreservesRepositoryTotal() = runTest {
        val page = service().noticePage(page = 1)

        assertEquals(23, page.total)
        assertEquals(2, page.records.size)
    }

    private fun service(
        auth: RecordingAuthManager = RecordingAuthManager(),
        scheduleRepository: ScheduleRepository = FakeScheduleRepository(),
    ): DefaultCoreFeatureService = DefaultCoreFeatureService(
        authManager = auth,
        scheduleRepository = scheduleRepository,
        gradeRepository = FakeGradeRepository(),
        campusCardRepository = FakeCampusCardRepository(),
        noticeRepository = FakeNoticeRepository(),
        emptyRoomRepository = FakeEmptyRoomRepository(),
        librarySeatRepository = FakeLibrarySeatRepository(),
        couponRepository = FakeCouponRepository(),
        schoolCourseRepository = FakeSchoolCourseRepository(),
    )

    private fun round2(value: Double): Double =
        kotlin.math.round(value * 100) / 100
}

private class RecordingAuthManager(
    private val siteVerificationRequiredFor: SiteKey? = null,
) : AuthManager {
    private val mode = MutableStateFlow(AccessMode.AUTO)
    private val state = MutableStateFlow<AuthState>(AuthState.Anonymous)
    val requestedSites = mutableListOf<SiteKey>()

    override val currentAccessMode: StateFlow<AccessMode> = mode
    override val authState: StateFlow<AuthState> = state

    override suspend fun setAccessMode(mode: AccessMode) {
        this.mode.value = mode
    }

    override suspend fun restoreSavedCredentials(): AuthState = state.value

    override suspend fun login(username: String, password: String): LoginOutcome =
        error("not used")

    override suspend fun beginBrowserAuth(site: SiteKey?): LoginOutcome =
        error("not used")

    override suspend fun beginSiteVerification(site: SiteKey): LoginOutcome =
        error("not used")

    override suspend fun ensureSession(site: SiteKey): SiteSession {
        requestedSites += site
        if (site == siteVerificationRequiredFor) {
            state.value = AuthState.SiteVerificationRequired(
                username = "3124000000",
                site = site,
                message = "需要补授权",
            )
            throw SiteVerificationRequiredException(site, "需要补授权")
        }
        return InMemorySiteSession(site, AccessMode.AUTO)
    }

    override suspend fun submitCaptcha(code: String): LoginOutcome =
        error("not used")

    override suspend fun submitMfa(code: String): LoginOutcome =
        error("not used")

    override suspend fun submitAccountChoice(choiceId: String): LoginOutcome =
        error("not used")

    override suspend fun resumeBrowserAuth(callbackUrl: String): LoginOutcome =
        error("not used")

    override suspend fun logout() = Unit
}

private class FakeScheduleRepository : ScheduleRepository {
    override suspend fun courses(session: SiteSession, termCode: String?): List<CourseItem> = listOf(
        CourseItem("大学物理", "李老师", "中二-3201", 2, 3, 4, listOf(1, 2, 3)),
        CourseItem("高等数学", "王老师", "主楼-101", 2, 1, 2, listOf(1, 2, 3)),
        CourseItem("线性代数", "张老师", "主楼-102", 3, 1, 2, listOf(1, 2, 3)),
    )

    override suspend fun exams(session: SiteSession, termCode: String?): List<ExamItem> = listOf(
        ExamItem("高等数学", "2026-01-10 09:00", "主楼-101"),
    )
}

private class TextbookFailingScheduleRepository : ScheduleRepository {
    override suspend fun courses(session: SiteSession, termCode: String?): List<CourseItem> = listOf(
        CourseItem("高等数学", "王老师", "主楼-101", 2, 1, 2, listOf(1, 2, 3)),
    )

    override suspend fun exams(session: SiteSession, termCode: String?): List<ExamItem> = listOf(
        ExamItem("高等数学", "2026-01-10 09:00", "主楼-101"),
    )

    override suspend fun textbooks(session: SiteSession, termCode: String?): List<TextbookItem> =
        error("教材报表初始化失败，未获取到 sessionID")
}

private class FakeGradeRepository : GradeRepository {
    override suspend fun grades(session: SiteSession, termCode: String?): List<GradeItem> = listOf(
        GradeItem("高等数学", "92", 3.0, 3.9),
        GradeItem("大学物理", "85", 2.0, 3.25),
    )

    override suspend fun gradeDetail(session: SiteSession, gradeId: String): GradeDetail =
        GradeDetail(
            courseName = "高等数学",
            score = "92",
            credit = 3.0,
            gradePoint = 3.9,
            examType = "正常考试",
            courseProperty = "专业基础课",
            examProperty = "正常",
            isReplacement = false,
            isPassed = true,
            specificReason = null,
            items = listOf(
                GradeDetailItem("平时成绩", 0.3, "95"),
                GradeDetailItem("期末考试", 0.7, "91"),
            ),
        )
}

private class FakeCampusCardRepository : CampusCardRepository {
    override suspend fun cardInfo(session: SiteSession): CampusCardInfo =
        CampusCardInfo(balanceYuan = 42.5, holderName = "学生")

    override suspend fun transactions(session: SiteSession, page: Int, pageSize: Int): List<CampusCardTransaction> = listOf(
        CampusCardTransaction("2026-05-20 12:00", "康桥苑", -12.0, TransactionKind.EXPENSE),
    )

    override suspend fun transactionPage(
        session: SiteSession,
        page: Int,
        pageSize: Int,
    ): CampusCardTransactionPage =
        CampusCardTransactionPage(total = 37, records = transactions(session, page, pageSize))
}

private class FakeNoticeRepository : NoticeRepository {
    override suspend fun notices(page: Int): List<NoticeItem> = listOf(
        NoticeItem("关于考试安排的通知", "https://example.edu/1", "教务处", "2026-05-20"),
        NoticeItem("校园网络维护通知", "https://example.edu/2", "网信中心", "2026-05-19"),
    )

    override suspend fun noticePage(page: Int): NoticePage =
        NoticePage(total = 23, records = notices(page))
}

private class FakeEmptyRoomRepository : EmptyRoomRepository {
    override suspend fun rooms(campus: String, date: String, sections: IntRange): List<EmptyRoom> = listOf(
        EmptyRoom("中二-3201", campus, "中二", sections.toList()),
    )
}

private class FakeLibrarySeatRepository : LibrarySeatRepository {
    override suspend fun snapshot(session: SiteSession, areaCode: String?): LibrarySeatSnapshot {
        assertEquals(SiteKey.LIBRARY, session.site)
        val areas = listOf(
            LibraryAreaStats("north2east", "北楼二层外文库（东）", "二楼", available = 10, total = 100),
            LibraryAreaStats("north4middle", "北楼四层中间", "四楼", available = 80, total = 120),
        )
        return LibrarySeatSnapshot(
            selectedAreaCode = areaCode ?: "north2east",
            areas = areas,
            seats = listOf(LibrarySeatItem("J001", available = true)),
            recommendedAreas = listOf(areas.last()),
            myBooking = null,
        )
    }

    override suspend fun bookSeat(
        session: SiteSession,
        seatId: String,
        areaCode: String,
        allowSwap: Boolean,
    ): LibrarySeatBookingResult =
        LibrarySeatBookingResult(success = true, message = "ok")
}

private class FakeCouponRepository : CouponRepository {
    override suspend fun coupons(
        session: SiteSession,
        filter: CouponFilter,
        page: Int,
        pageSize: Int,
    ): CouponPage {
        assertEquals(SiteKey.COUPON, session.site)
        val records = listOf(
            CouponRecord(
                sendId = "coupon-1",
                showCardId = "show-1",
                voucherName = "康桥苑加餐券",
                typeName = "餐补券",
                amountFen = 800,
                leftAmountFen = 800,
                leftCount = 1,
                startDate = "2026-06-01",
                endDate = "2026-06-30",
            ),
        )
        return CouponPage(
            filter = filter,
            total = records.size,
            records = records.drop((page - 1) * pageSize).take(pageSize),
        )
    }
}

private class FakeSchoolCourseRepository : SchoolCourseRepository {
    override suspend fun courses(
        session: SiteSession,
        termCode: String?,
        courseName: String,
        teacher: String,
        campusCode: String,
        weekday: Int,
        page: Int,
        pageSize: Int,
    ): SchoolCoursePage {
        assertEquals(SiteKey.SCHEDULE, session.site)
        val record = SchoolCourseItem(
            courseCode = "MATH1001",
            courseName = courseName.ifBlank { "高等数学" },
            sectionNumber = "01",
            teacher = "王老师",
            department = "数学与统计学院",
            credit = 3.0,
            enrollCount = 86,
            capacity = 100,
            scheduleLocation = "周三 1-2 节 主楼-101",
            campus = "兴庆校区",
            teachingClassId = "class-1",
            termCode = termCode ?: "2025-2026-2",
        )
        return SchoolCoursePage(
            termCode = record.termCode,
            total = 1,
            page = page,
            pageSize = pageSize,
            records = listOf(record),
        )
    }
}
