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
    fun scheduleAndCampusCardHideSiteSessionFromCallers() = runTest {
        val auth = RecordingAuthManager()
        val service = service(auth = auth)

        service.schedule(termCode = "2025-2026-1")
        service.campusCard(page = 1, pageSize = 10)

        assertEquals(
            listOf(SiteKey.SCHEDULE, SiteKey.CAMPUS_CARD),
            auth.requestedSites,
        )
    }

    @Test
    fun scheduleKeepsCoursesAndExamsWhenTextbooksFail() = runTest {
        val service = service(scheduleRepository = TextbookFailingScheduleRepository())

        val snapshot = service.schedule(termCode = "2025-2026-1")

        assertEquals(listOf("高等数学"), snapshot.courses.map { it.name })
        assertEquals(listOf("高等数学"), snapshot.exams.map { it.courseName })
        assertEquals(emptyList(), snapshot.textbooks)
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
}

private class FakeCampusCardRepository : CampusCardRepository {
    override suspend fun cardInfo(session: SiteSession): CampusCardInfo =
        CampusCardInfo(balanceYuan = 42.5, holderName = "学生")

    override suspend fun transactions(session: SiteSession, page: Int, pageSize: Int): List<CampusCardTransaction> = listOf(
        CampusCardTransaction("2026-05-20 12:00", "康桥苑", -12.0, TransactionKind.EXPENSE),
    )
}

private class FakeNoticeRepository : NoticeRepository {
    override suspend fun notices(page: Int): List<NoticeItem> = listOf(
        NoticeItem("关于考试安排的通知", "https://example.edu/1", "教务处", "2026-05-20"),
        NoticeItem("校园网络维护通知", "https://example.edu/2", "网信中心", "2026-05-19"),
    )
}

private class FakeEmptyRoomRepository : EmptyRoomRepository {
    override suspend fun rooms(campus: String, date: String, sections: IntRange): List<EmptyRoom> = listOf(
        EmptyRoom("中二-3201", campus, "中二", sections.toList()),
    )
}
