package com.xjtu.toolbox.shared.features

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CachedCoreFeatureServiceTest {
    @Test
    fun freshDashboardCacheAvoidsSecondDelegateCall() = runTest {
        val delegate = CountingCoreFeatureService()
        val service = cached(delegate)

        val first = service.dashboard(dayOfWeek = 3, noticePageSize = 2)
        val second = service.dashboard(dayOfWeek = 3, noticePageSize = 2)

        assertEquals(first, second)
        assertEquals(1, delegate.dashboardCalls)
    }

    @Test
    fun staleDashboardCacheRefreshesAfterTtl() = runTest {
        val clock = ManualFeatureCacheClock(now = 1_000)
        val delegate = CountingCoreFeatureService()
        val service = cached(delegate, clock = clock, ttlMillis = 10_000)

        service.dashboard(dayOfWeek = 3, noticePageSize = 2)
        clock.advance(10_000)
        service.dashboard(dayOfWeek = 3, noticePageSize = 2)

        assertEquals(2, delegate.dashboardCalls)
    }

    @Test
    fun staleDashboardCacheFallsBackWhenRefreshFails() = runTest {
        val clock = ManualFeatureCacheClock(now = 1_000)
        val delegate = CountingCoreFeatureService()
        val service = cached(delegate, clock = clock, ttlMillis = 10_000)

        val cached = service.dashboard(dayOfWeek = 3, noticePageSize = 2)
        clock.advance(10_000)
        delegate.failDashboard = true
        val fallback = service.dashboard(dayOfWeek = 3, noticePageSize = 2)

        assertEquals(cached, fallback)
        assertEquals(2, delegate.dashboardCalls)
    }

    @Test
    fun emptyRoomCacheKeyIncludesSectionRange() = runTest {
        val delegate = CountingCoreFeatureService()
        val service = cached(delegate)

        service.emptyRooms("兴庆校区", "2026-05-20", 1..2)
        service.emptyRooms("兴庆校区", "2026-05-20", 3..4)

        assertEquals(2, delegate.emptyRoomCalls)
    }

    @Test
    fun freshNoticePageCachePreservesTotalAndAvoidsSecondDelegateCall() = runTest {
        val delegate = CountingCoreFeatureService()
        val service = cached(delegate)

        val first = service.noticePage(page = 1)
        val second = service.noticePage(page = 1)

        assertEquals(first, second)
        assertEquals(23, second.total)
        assertEquals(1, delegate.noticePageCalls)
    }

    @Test
    fun librarySeatSnapshotAndBookingActionsBypassCache() = runTest {
        val delegate = CountingCoreFeatureService()
        val service = cached(delegate)

        service.librarySeats("north2east")
        service.librarySeats("north2east")
        service.bookLibrarySeat("D021", "north2east")
        service.bookLibrarySeat("D022", "north2east")

        assertEquals(2, delegate.librarySeatCalls)
        assertEquals(2, delegate.libraryBookingCalls)
    }

    @Test
    fun couponsBypassCache() = runTest {
        val delegate = CountingCoreFeatureService()
        val service = cached(delegate)

        service.coupons(CouponFilter.USABLE, page = 1, pageSize = 10)
        service.coupons(CouponFilter.USABLE, page = 1, pageSize = 10)

        assertEquals(2, delegate.couponCalls)
    }

    @Test
    fun uncachedFailureStillPropagates() = runTest {
        val delegate = CountingCoreFeatureService(failDashboard = true)
        val service = cached(delegate)

        assertFailsWith<IllegalStateException> {
            service.dashboard(dayOfWeek = 3, noticePageSize = 2)
        }
    }

    private fun cached(
        delegate: CountingCoreFeatureService,
        clock: FeatureCacheClock = ManualFeatureCacheClock(now = 1_000),
        ttlMillis: Long = 10_000,
    ): CachedCoreFeatureService = CachedCoreFeatureService(
        delegate = delegate,
        cacheStore = InMemoryCoreFeatureCacheStore(),
        clock = clock,
        ttlMillis = ttlMillis,
    )
}

private class ManualFeatureCacheClock(now: Long) : FeatureCacheClock {
    private var current = now

    override fun nowMillis(): Long = current

    fun advance(millis: Long) {
        current += millis
    }
}

private class CountingCoreFeatureService(
    var failDashboard: Boolean = false,
) : CoreFeatureService {
    var dashboardCalls = 0
        private set
    var emptyRoomCalls = 0
        private set
    var noticePageCalls = 0
        private set
    var librarySeatCalls = 0
        private set
    var libraryBookingCalls = 0
        private set
    var couponCalls = 0
        private set
    var schoolCourseCalls = 0
        private set

    override suspend fun dashboard(dayOfWeek: Int, noticePageSize: Int): CoreDashboardSnapshot {
        dashboardCalls += 1
        if (failDashboard) error("dashboard unavailable")
        return CoreDashboardSnapshot(
            todayCourses = listOf(
                CourseItem("高等数学", "王老师", "主楼-101", dayOfWeek, 1, 2, listOf(1, 2, 3)),
            ),
            campusCard = CampusCardInfo(42.5, "学生"),
            notices = listOf(
                NoticeItem("通知", "https://example.edu", "教务处", "2026-05-20"),
            ).take(noticePageSize),
        )
    }

    override suspend fun schedule(termCode: String?): ScheduleSnapshot =
        ScheduleSnapshot(courses = emptyList(), exams = emptyList())

    override suspend fun grades(termCode: String?): GradeSnapshot =
        GradeSnapshot(grades = emptyList(), weightedGpa = null, totalCredits = 0.0)

    override suspend fun gradeDetail(gradeId: String): GradeDetail =
        GradeDetail(
            courseName = "高等数学",
            score = "92",
            credit = 3.0,
            gradePoint = 3.9,
            examType = "正常考试",
            courseProperty = null,
            examProperty = "正常",
            isReplacement = false,
            isPassed = true,
            specificReason = null,
            items = emptyList(),
        )

    override suspend fun campusCard(page: Int, pageSize: Int): CampusCardSnapshot =
        CampusCardSnapshot(
            info = CampusCardInfo(42.5, "学生"),
            transactions = emptyList(),
        )

    override suspend fun notices(page: Int): List<NoticeItem> =
        emptyList()

    override suspend fun noticePage(page: Int): NoticePage {
        noticePageCalls += 1
        return NoticePage(
            total = 23,
            records = listOf(NoticeItem("通知", "https://example.edu", "教务处", "2026-05-20")),
        )
    }

    override suspend fun emptyRooms(campus: String, date: String, sections: IntRange): List<EmptyRoom> {
        emptyRoomCalls += 1
        return listOf(EmptyRoom("中二-3201", campus, "中二", sections.toList()))
    }

    override suspend fun librarySeats(areaCode: String?): LibrarySeatSnapshot {
        librarySeatCalls += 1
        val area = LibraryAreaStats(areaCode ?: "north2east", "北楼二层外文库（东）", "二楼", 42, 120)
        return LibrarySeatSnapshot(
            selectedAreaCode = area.code,
            areas = listOf(area),
            seats = listOf(LibrarySeatItem("D021", available = true)),
            recommendedAreas = listOf(area),
            myBooking = null,
        )
    }

    override suspend fun bookLibrarySeat(
        seatId: String,
        areaCode: String,
        allowSwap: Boolean,
    ): LibrarySeatBookingResult {
        libraryBookingCalls += 1
        return LibrarySeatBookingResult(success = true, message = "ok")
    }

    override suspend fun coupons(filter: CouponFilter, page: Int, pageSize: Int): CouponPage {
        couponCalls += 1
        return CouponPage(
            filter = filter,
            total = 1,
            records = listOf(
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
            ),
        )
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
        schoolCourseCalls += 1
        return SchoolCoursePage(
            termCode = termCode ?: "2025-2026-2",
            total = 0,
            page = page,
            pageSize = pageSize,
            records = emptyList(),
        )
    }
}
