package com.xjtu.toolbox.shared.features

interface FeatureCacheClock {
    fun nowMillis(): Long
}

@OptIn(kotlin.time.ExperimentalTime::class)
object SystemFeatureCacheClock : FeatureCacheClock {
    override fun nowMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}

data class CoreFeatureCacheEntry<T>(
    val value: T,
    val storedAtMillis: Long,
)

interface CoreFeatureCacheStore {
    fun loadDashboard(dayOfWeek: Int, noticePageSize: Int): CoreFeatureCacheEntry<CoreDashboardSnapshot>?
    fun saveDashboard(dayOfWeek: Int, noticePageSize: Int, entry: CoreFeatureCacheEntry<CoreDashboardSnapshot>)

    fun loadSchedule(termCode: String?): CoreFeatureCacheEntry<ScheduleSnapshot>?
    fun saveSchedule(termCode: String?, entry: CoreFeatureCacheEntry<ScheduleSnapshot>)

    fun loadGrades(termCode: String?): CoreFeatureCacheEntry<GradeSnapshot>?
    fun saveGrades(termCode: String?, entry: CoreFeatureCacheEntry<GradeSnapshot>)

    fun loadCampusCard(page: Int, pageSize: Int): CoreFeatureCacheEntry<CampusCardSnapshot>?
    fun saveCampusCard(page: Int, pageSize: Int, entry: CoreFeatureCacheEntry<CampusCardSnapshot>)

    fun loadNotices(page: Int): CoreFeatureCacheEntry<List<NoticeItem>>?
    fun saveNotices(page: Int, entry: CoreFeatureCacheEntry<List<NoticeItem>>)

    fun loadEmptyRooms(campus: String, date: String, sections: IntRange): CoreFeatureCacheEntry<List<EmptyRoom>>?
    fun saveEmptyRooms(campus: String, date: String, sections: IntRange, entry: CoreFeatureCacheEntry<List<EmptyRoom>>)

    fun clear()
}

class InMemoryCoreFeatureCacheStore : CoreFeatureCacheStore {
    private val entries = mutableMapOf<String, CoreFeatureCacheEntry<*>>()

    override fun loadDashboard(dayOfWeek: Int, noticePageSize: Int): CoreFeatureCacheEntry<CoreDashboardSnapshot>? =
        entry(dashboardKey(dayOfWeek, noticePageSize))

    override fun saveDashboard(dayOfWeek: Int, noticePageSize: Int, entry: CoreFeatureCacheEntry<CoreDashboardSnapshot>) {
        entries[dashboardKey(dayOfWeek, noticePageSize)] = entry
    }

    override fun loadSchedule(termCode: String?): CoreFeatureCacheEntry<ScheduleSnapshot>? =
        entry(scheduleKey(termCode))

    override fun saveSchedule(termCode: String?, entry: CoreFeatureCacheEntry<ScheduleSnapshot>) {
        entries[scheduleKey(termCode)] = entry
    }

    override fun loadGrades(termCode: String?): CoreFeatureCacheEntry<GradeSnapshot>? =
        entry(gradesKey(termCode))

    override fun saveGrades(termCode: String?, entry: CoreFeatureCacheEntry<GradeSnapshot>) {
        entries[gradesKey(termCode)] = entry
    }

    override fun loadCampusCard(page: Int, pageSize: Int): CoreFeatureCacheEntry<CampusCardSnapshot>? =
        entry(campusCardKey(page, pageSize))

    override fun saveCampusCard(page: Int, pageSize: Int, entry: CoreFeatureCacheEntry<CampusCardSnapshot>) {
        entries[campusCardKey(page, pageSize)] = entry
    }

    override fun loadNotices(page: Int): CoreFeatureCacheEntry<List<NoticeItem>>? =
        entry(noticesKey(page))

    override fun saveNotices(page: Int, entry: CoreFeatureCacheEntry<List<NoticeItem>>) {
        entries[noticesKey(page)] = entry
    }

    override fun loadEmptyRooms(campus: String, date: String, sections: IntRange): CoreFeatureCacheEntry<List<EmptyRoom>>? =
        entry(emptyRoomsKey(campus, date, sections))

    override fun saveEmptyRooms(campus: String, date: String, sections: IntRange, entry: CoreFeatureCacheEntry<List<EmptyRoom>>) {
        entries[emptyRoomsKey(campus, date, sections)] = entry
    }

    override fun clear() {
        entries.clear()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> entry(key: String): CoreFeatureCacheEntry<T>? =
        entries[key] as? CoreFeatureCacheEntry<T>
}

class CachedCoreFeatureService(
    private val delegate: CoreFeatureService,
    private val cacheStore: CoreFeatureCacheStore,
    private val clock: FeatureCacheClock = SystemFeatureCacheClock,
    private val ttlMillis: Long = 5 * 60_000,
) : CoreFeatureService {
    override suspend fun dashboard(dayOfWeek: Int, noticePageSize: Int): CoreDashboardSnapshot =
        load(
            cached = cacheStore.loadDashboard(dayOfWeek, noticePageSize),
            fetch = { delegate.dashboard(dayOfWeek, noticePageSize) },
            save = { cacheStore.saveDashboard(dayOfWeek, noticePageSize, it) },
        )

    override suspend fun schedule(termCode: String?): ScheduleSnapshot =
        load(
            cached = cacheStore.loadSchedule(termCode),
            fetch = { delegate.schedule(termCode) },
            save = { cacheStore.saveSchedule(termCode, it) },
        )

    override suspend fun grades(termCode: String?): GradeSnapshot =
        load(
            cached = cacheStore.loadGrades(termCode),
            fetch = { delegate.grades(termCode) },
            save = { cacheStore.saveGrades(termCode, it) },
        )

    override suspend fun campusCard(page: Int, pageSize: Int): CampusCardSnapshot =
        load(
            cached = cacheStore.loadCampusCard(page, pageSize),
            fetch = { delegate.campusCard(page, pageSize) },
            save = { cacheStore.saveCampusCard(page, pageSize, it) },
        )

    override suspend fun notices(page: Int): List<NoticeItem> =
        load(
            cached = cacheStore.loadNotices(page),
            fetch = { delegate.notices(page) },
            save = { cacheStore.saveNotices(page, it) },
        )

    override suspend fun emptyRooms(campus: String, date: String, sections: IntRange): List<EmptyRoom> =
        load(
            cached = cacheStore.loadEmptyRooms(campus, date, sections),
            fetch = { delegate.emptyRooms(campus, date, sections) },
            save = { cacheStore.saveEmptyRooms(campus, date, sections, it) },
        )

    private suspend fun <T> load(
        cached: CoreFeatureCacheEntry<T>?,
        fetch: suspend () -> T,
        save: (CoreFeatureCacheEntry<T>) -> Unit,
    ): T {
        if (cached != null && isFresh(cached)) return cached.value
        return try {
            fetch().also { value ->
                save(CoreFeatureCacheEntry(value = value, storedAtMillis = clock.nowMillis()))
            }
        } catch (error: Throwable) {
            cached?.value ?: throw error
        }
    }

    private fun isFresh(entry: CoreFeatureCacheEntry<*>): Boolean =
        clock.nowMillis() - entry.storedAtMillis < ttlMillis
}

private fun dashboardKey(dayOfWeek: Int, noticePageSize: Int): String =
    "dashboard:$dayOfWeek:$noticePageSize"

private fun scheduleKey(termCode: String?): String =
    "schedule:${termCode.orEmpty()}"

private fun gradesKey(termCode: String?): String =
    "grades:${termCode.orEmpty()}"

private fun campusCardKey(page: Int, pageSize: Int): String =
    "campus_card:$page:$pageSize"

private fun noticesKey(page: Int): String =
    "notices:$page"

private fun emptyRoomsKey(campus: String, date: String, sections: IntRange): String =
    "empty_rooms:$campus:$date:${sections.first}:${sections.last}"
