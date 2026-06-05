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

    override suspend fun campusCard(page: Int, pageSize: Int): CampusCardSnapshot {
        val session = authManager.ensureSession(SiteKey.CAMPUS_CARD)
        return CampusCardSnapshot(
            info = campusCardRepository.cardInfo(session),
            transactions = campusCardRepository.transactions(session, page, pageSize),
        )
    }

    override suspend fun notices(page: Int): List<NoticeItem> =
        noticeRepository.notices(page)

    override suspend fun emptyRooms(campus: String, date: String, sections: IntRange): List<EmptyRoom> =
        emptyRoomRepository.rooms(campus, date, sections)

    private fun List<GradeItem>.weightedGpa(): Double? {
        val credits = sumOf { it.credit }
        if (credits <= 0.0) return null
        return sumOf { it.gradePoint * it.credit } / credits
    }
}
