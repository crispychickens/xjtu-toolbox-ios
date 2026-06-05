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

    override suspend fun campusCard(page: Int, pageSize: Int): CampusCardSnapshot =
        CampusCardSnapshot(
            info = CampusCardInfo(42.5, "学生"),
            transactions = emptyList(),
        )

    override suspend fun notices(page: Int): List<NoticeItem> =
        emptyList()

    override suspend fun emptyRooms(campus: String, date: String, sections: IntRange): List<EmptyRoom> {
        emptyRoomCalls += 1
        return listOf(EmptyRoom("中二-3201", campus, "中二", sections.toList()))
    }
}
