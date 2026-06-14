package com.xjtu.toolbox.shared

import com.xjtu.toolbox.shared.auth.AccessMode
import com.xjtu.toolbox.shared.auth.AccountChoice
import com.xjtu.toolbox.shared.auth.AccountChoiceChallenge
import com.xjtu.toolbox.shared.auth.AccountType
import com.xjtu.toolbox.shared.auth.AuthEngine
import com.xjtu.toolbox.shared.auth.AuthManager
import com.xjtu.toolbox.shared.auth.BrowserAuthCallback
import com.xjtu.toolbox.shared.auth.BrowserAuthChallenge
import com.xjtu.toolbox.shared.auth.CaptchaChallenge
import com.xjtu.toolbox.shared.auth.Credentials
import com.xjtu.toolbox.shared.auth.DefaultAuthManager
import com.xjtu.toolbox.shared.auth.EngineLoginResult
import com.xjtu.toolbox.shared.auth.InMemoryCredentialVault
import com.xjtu.toolbox.shared.auth.MfaChallenge
import com.xjtu.toolbox.shared.auth.MfaFlow
import com.xjtu.toolbox.shared.auth.SessionRegistry
import com.xjtu.toolbox.shared.auth.SiteVerificationContext
import com.xjtu.toolbox.shared.auth.SiteKey
import com.xjtu.toolbox.shared.auth.SiteSession
import com.xjtu.toolbox.shared.features.CampusCardInfo
import com.xjtu.toolbox.shared.features.CampusCardRepository
import com.xjtu.toolbox.shared.features.CampusCardTransaction
import com.xjtu.toolbox.shared.features.CoreFeatureService
import com.xjtu.toolbox.shared.features.CouponFilter
import com.xjtu.toolbox.shared.features.CouponPage
import com.xjtu.toolbox.shared.features.CouponRecord
import com.xjtu.toolbox.shared.features.CouponRepository
import com.xjtu.toolbox.shared.features.CourseItem
import com.xjtu.toolbox.shared.features.DefaultCoreFeatureService
import com.xjtu.toolbox.shared.features.EmptyRoom
import com.xjtu.toolbox.shared.features.EmptyRoomRepository
import com.xjtu.toolbox.shared.features.ExamItem
import com.xjtu.toolbox.shared.features.GradeDetail
import com.xjtu.toolbox.shared.features.GradeDetailItem
import com.xjtu.toolbox.shared.features.GradeItem
import com.xjtu.toolbox.shared.features.GradeRepository
import com.xjtu.toolbox.shared.features.LibraryAreaStats
import com.xjtu.toolbox.shared.features.LibraryBookingInfo
import com.xjtu.toolbox.shared.features.LibrarySeatBookingResult
import com.xjtu.toolbox.shared.features.LibrarySeatItem
import com.xjtu.toolbox.shared.features.LibrarySeatRepository
import com.xjtu.toolbox.shared.features.LibrarySeatSnapshot
import com.xjtu.toolbox.shared.features.NoticeItem
import com.xjtu.toolbox.shared.features.NoticePage
import com.xjtu.toolbox.shared.features.NoticeRepository
import com.xjtu.toolbox.shared.features.ScheduleRepository
import com.xjtu.toolbox.shared.features.SchoolCourseItem
import com.xjtu.toolbox.shared.features.SchoolCoursePage
import com.xjtu.toolbox.shared.features.SchoolCourseRepository
import com.xjtu.toolbox.shared.features.TextbookItem
import com.xjtu.toolbox.shared.features.TransactionKind

data class PreviewAppServices(
    val authManager: AuthManager,
    val coreFeatureService: CoreFeatureService,
)

object XjtuToolboxPreviewFactory {
    fun appServices(initialAccessMode: AccessMode = AccessMode.AUTO): PreviewAppServices {
        val authManager = authManager(initialAccessMode)
        return PreviewAppServices(
            authManager = authManager,
            coreFeatureService = coreFeatureService(authManager),
        )
    }

    fun authManager(initialAccessMode: AccessMode = AccessMode.AUTO): AuthManager =
        DefaultAuthManager(
            engine = PreviewAuthEngine(),
            vault = InMemoryCredentialVault(),
            registry = SessionRegistry(emptyMap()),
            initialAccessMode = initialAccessMode,
        )

    fun coreFeatureService(authManager: AuthManager): CoreFeatureService =
        DefaultCoreFeatureService(
            authManager = authManager,
            scheduleRepository = PreviewScheduleRepository,
            gradeRepository = PreviewGradeRepository,
            campusCardRepository = PreviewCampusCardRepository,
            noticeRepository = PreviewNoticeRepository,
            emptyRoomRepository = PreviewEmptyRoomRepository,
            librarySeatRepository = PreviewLibrarySeatRepository,
            couponRepository = PreviewCouponRepository,
            schoolCourseRepository = PreviewSchoolCourseRepository,
        )

    fun coreFeatureServiceWithEmptyRooms(
        authManager: AuthManager,
        emptyRoomRepository: EmptyRoomRepository,
    ): CoreFeatureService =
        DefaultCoreFeatureService(
            authManager = authManager,
            scheduleRepository = PreviewScheduleRepository,
            gradeRepository = PreviewGradeRepository,
            campusCardRepository = PreviewCampusCardRepository,
            noticeRepository = PreviewNoticeRepository,
            emptyRoomRepository = emptyRoomRepository,
            librarySeatRepository = PreviewLibrarySeatRepository,
            couponRepository = PreviewCouponRepository,
            schoolCourseRepository = PreviewSchoolCourseRepository,
        )

    fun coreFeatureServiceWithPublicRepositories(
        authManager: AuthManager,
        noticeRepository: NoticeRepository = PreviewNoticeRepository,
        emptyRoomRepository: EmptyRoomRepository = PreviewEmptyRoomRepository,
    ): CoreFeatureService =
        DefaultCoreFeatureService(
            authManager = authManager,
            scheduleRepository = PreviewScheduleRepository,
            gradeRepository = PreviewGradeRepository,
            campusCardRepository = PreviewCampusCardRepository,
            noticeRepository = noticeRepository,
            emptyRoomRepository = emptyRoomRepository,
            librarySeatRepository = PreviewLibrarySeatRepository,
            couponRepository = PreviewCouponRepository,
            schoolCourseRepository = PreviewSchoolCourseRepository,
        )

    fun coreFeatureServiceWithCampusCard(
        authManager: AuthManager,
        campusCardRepository: CampusCardRepository,
        noticeRepository: NoticeRepository = PreviewNoticeRepository,
        emptyRoomRepository: EmptyRoomRepository = PreviewEmptyRoomRepository,
    ): CoreFeatureService =
        DefaultCoreFeatureService(
            authManager = authManager,
            scheduleRepository = PreviewScheduleRepository,
            gradeRepository = PreviewGradeRepository,
            campusCardRepository = campusCardRepository,
            noticeRepository = noticeRepository,
            emptyRoomRepository = emptyRoomRepository,
            librarySeatRepository = PreviewLibrarySeatRepository,
            couponRepository = PreviewCouponRepository,
            schoolCourseRepository = PreviewSchoolCourseRepository,
        )

    fun coreFeatureServiceWithRepositories(
        authManager: AuthManager,
        scheduleRepository: ScheduleRepository = PreviewScheduleRepository,
        gradeRepository: GradeRepository = PreviewGradeRepository,
        campusCardRepository: CampusCardRepository = PreviewCampusCardRepository,
        noticeRepository: NoticeRepository = PreviewNoticeRepository,
        emptyRoomRepository: EmptyRoomRepository = PreviewEmptyRoomRepository,
        librarySeatRepository: LibrarySeatRepository = PreviewLibrarySeatRepository,
        couponRepository: CouponRepository = PreviewCouponRepository,
        schoolCourseRepository: SchoolCourseRepository = PreviewSchoolCourseRepository,
    ): CoreFeatureService =
        DefaultCoreFeatureService(
            authManager = authManager,
            scheduleRepository = scheduleRepository,
            gradeRepository = gradeRepository,
            campusCardRepository = campusCardRepository,
            noticeRepository = noticeRepository,
            emptyRoomRepository = emptyRoomRepository,
            librarySeatRepository = librarySeatRepository,
            couponRepository = couponRepository,
            schoolCourseRepository = schoolCourseRepository,
        )
}

private class PreviewAuthEngine : AuthEngine {
    private var pendingUsername: String? = null

    override suspend fun login(credentials: Credentials, accessMode: AccessMode): EngineLoginResult {
        pendingUsername = credentials.username
        return when {
            credentials.password == "mfa" -> EngineLoginResult.NeedMfa(
                MfaChallenge(
                    flow = MfaFlow.MFA_DETECT,
                    maskedPhone = "188****0000",
                    site = null,
                ),
            )
            credentials.password == "captcha" -> EngineLoginResult.NeedCaptcha(
                CaptchaChallenge(
                    imageBase64 = PREVIEW_CAPTCHA_PNG_BASE64,
                    site = null,
                ),
            )
            credentials.password == "account" -> EngineLoginResult.NeedAccountChoice(previewAccountChoice())
            credentials.password == "browser" -> beginBrowserAuth(site = null)
            credentials.password.isBlank() -> EngineLoginResult.InvalidPassword("CAS")
            else -> EngineLoginResult.Success(credentials.username)
        }
    }

    override suspend fun beginBrowserAuth(site: SiteKey?): EngineLoginResult =
        EngineLoginResult.NeedBrowserAuth(
            BrowserAuthChallenge(
                loginUrl = "https://login.xjtu.edu.cn/cas/login?service=xjtutoolbox://auth",
                callbackScheme = "xjtutoolbox",
                state = "preview-state",
                site = site,
            ),
        )

    override suspend fun beginSiteVerification(
        credentials: Credentials,
        accessMode: AccessMode,
        site: SiteKey,
        context: SiteVerificationContext,
    ): EngineLoginResult {
        pendingUsername = credentials.username
        return EngineLoginResult.NeedMfa(
            MfaChallenge(
                flow = MfaFlow.SAFETY_VERIFY,
                maskedPhone = "188****0000",
                site = site,
            ),
        )
    }

    override suspend fun submitCaptcha(code: String): EngineLoginResult =
        if (code.length >= 4) {
            EngineLoginResult.Success(pendingUsername ?: "3124000000")
        } else {
            EngineLoginResult.VerificationRejected("请输入图形验证码")
        }

    override suspend fun submitMfa(code: String): EngineLoginResult =
        if (code.length == 6) {
            EngineLoginResult.Success(pendingUsername ?: "3124000000")
        } else {
            EngineLoginResult.VerificationRejected("请输入 6 位验证码")
        }

    override suspend fun submitAccountChoice(choiceId: String): EngineLoginResult =
        if (choiceId.isNotBlank()) {
            EngineLoginResult.Success(pendingUsername ?: "3124000000")
        } else {
            EngineLoginResult.VerificationRejected("请选择账号类型")
        }

    override suspend fun resumeBrowserAuth(callback: BrowserAuthCallback): EngineLoginResult =
        if (!callback.ticket.isNullOrBlank() || !callback.code.isNullOrBlank()) {
            EngineLoginResult.Success(pendingUsername ?: "3124000000")
        } else {
            EngineLoginResult.VerificationRejected("网页登录回跳缺少 ticket 或 code")
        }

    override suspend fun logout() = Unit

    private companion object {
        private const val PREVIEW_CAPTCHA_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgwJ/lzqY2QAAAABJRU5ErkJggg=="

        private fun previewAccountChoice(): AccountChoiceChallenge =
            AccountChoiceChallenge(
                choices = listOf(
                    AccountChoice(
                        id = "undergraduate-preview",
                        displayName = "本科生账号",
                        accountType = AccountType.UNDERGRADUATE,
                    ),
                    AccountChoice(
                        id = "postgraduate-preview",
                        displayName = "研究生账号",
                        accountType = AccountType.POSTGRADUATE,
                    ),
                ),
                site = null,
            )
    }
}

private object PreviewScheduleRepository : ScheduleRepository {
    override suspend fun courses(session: SiteSession, termCode: String?): List<CourseItem> =
        previewCourses

    override suspend fun exams(session: SiteSession, termCode: String?): List<ExamItem> =
        listOf(
            ExamItem(
                courseName = "高等数学",
                time = "2026-01-10 09:00-11:00",
                location = "主楼-101",
                courseCode = "MATH1001",
                examDate = "2026-01-10",
                examTime = "09:00-11:00",
                seatNumber = "12",
            ),
        )

    override suspend fun textbooks(session: SiteSession, termCode: String?): List<TextbookItem> =
        listOf(
            TextbookItem(
                courseName = "高等数学",
                textbookName = "高等数学 第八版",
                author = "同济大学数学系",
                publisher = "高等教育出版社",
                isbn = "9787040589812",
                price = "58.00 元",
                edition = "第八版",
            ),
            TextbookItem(
                courseName = "线性代数",
                textbookName = "无教材",
            ),
        )
}

private object PreviewGradeRepository : GradeRepository {
    override suspend fun grades(session: SiteSession, termCode: String?): List<GradeItem> =
        listOf(
            GradeItem(
                courseName = "高等数学",
                score = "92",
                credit = 3.0,
                gradePoint = 3.9,
                id = "preview-grade-1",
                termCode = "2025-2026-2",
            ),
            GradeItem(
                courseName = "大学物理",
                score = "85",
                credit = 2.0,
                gradePoint = 3.25,
                id = "preview-grade-2",
                termCode = "2025-2026-1",
            ),
        )

    override suspend fun gradeDetail(session: SiteSession, gradeId: String): GradeDetail =
        when (gradeId) {
            "preview-grade-1" -> GradeDetail(
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
                    GradeDetailItem(name = "平时成绩", percent = 0.3, score = "95"),
                    GradeDetailItem(name = "期末考试", percent = 0.7, score = "91"),
                ),
            )
            "preview-grade-2" -> GradeDetail(
                courseName = "大学物理",
                score = "85",
                credit = 2.0,
                gradePoint = 3.25,
                examType = "正常考试",
                courseProperty = "专业基础课",
                examProperty = "正常",
                isReplacement = false,
                isPassed = true,
                specificReason = null,
                items = emptyList(),
            )
            else -> error("未找到预览成绩详情")
        }
}

private object PreviewCampusCardRepository : CampusCardRepository {
    override suspend fun cardInfo(session: SiteSession): CampusCardInfo =
        CampusCardInfo(
            balanceYuan = 42.5,
            holderName = "学生",
        )

    override suspend fun transactions(session: SiteSession, page: Int, pageSize: Int): List<CampusCardTransaction> =
        listOf(
            CampusCardTransaction(
                time = "2026-05-20 12:00",
                merchant = "康桥苑",
                amountYuan = -12.0,
                kind = TransactionKind.EXPENSE,
                balanceAfterYuan = 30.5,
            ),
            CampusCardTransaction(
                time = "2026-05-20 08:12",
                merchant = "充值",
                amountYuan = 100.0,
                kind = TransactionKind.INCOME,
                balanceAfterYuan = 130.5,
            ),
        ).take(pageSize)
}

private object PreviewNoticeRepository : NoticeRepository {
    override suspend fun notices(page: Int): List<NoticeItem> =
        noticePage(page).records

    override suspend fun noticePage(page: Int): NoticePage {
        val records = listOf(
            NoticeItem(
                title = "关于考试安排的通知",
                link = "https://example.edu/1",
                source = "教务处",
                date = "2026-05-20",
            ),
            NoticeItem(
                title = "校园网络维护通知",
                link = "https://example.edu/2",
                source = "网信中心",
                date = "2026-05-19",
            ),
            NoticeItem(
                title = "空闲教室查询更新",
                link = "https://example.edu/3",
                source = "一网通办",
                date = "2026-05-18",
            ),
        )
        return NoticePage(
            total = records.size,
            records = if (page == 1) records else emptyList(),
        )
    }
}

private object PreviewEmptyRoomRepository : EmptyRoomRepository {
    override suspend fun rooms(campus: String, date: String, sections: IntRange): List<EmptyRoom> =
        listOf(
            EmptyRoom(
                name = "中二-3201",
                campus = campus,
                building = "中二",
                availableSections = sections.toList(),
                capacity = 120,
            ),
            EmptyRoom(
                name = "主楼-101",
                campus = campus,
                building = "主楼",
                availableSections = listOf(1, 2, 3, 4),
                capacity = 60,
            ),
        )
}

private object PreviewLibrarySeatRepository : LibrarySeatRepository {
    private val areas = listOf(
        LibraryAreaStats("north2east", "北楼二层外文库（东）", "二楼", available = 42, total = 120),
        LibraryAreaStats("south2", "南楼二层大厅", "二楼", available = 18, total = 112),
        LibraryAreaStats("north4middle", "北楼四层中间", "四楼", available = 56, total = 144),
        LibraryAreaStats("north4southeast", "北楼四层东南侧", "四楼", available = 0, total = 136),
    )

    override suspend fun snapshot(session: SiteSession, areaCode: String?): LibrarySeatSnapshot {
        val selectedAreaCode = areaCode?.takeIf { code -> areas.any { it.code == code } } ?: areas.first().code
        return LibrarySeatSnapshot(
            selectedAreaCode = selectedAreaCode,
            areas = areas,
            seats = listOf(
                LibrarySeatItem("D021", available = true),
                LibrarySeatItem("D022", available = true),
                LibrarySeatItem("D023", available = false),
                LibrarySeatItem("D024", available = true),
                LibrarySeatItem("E101", available = false),
            ),
            recommendedAreas = areas
                .filter { it.isOpen && it.available > 0 }
                .sortedWith(compareByDescending<LibraryAreaStats> { it.availabilityRate }.thenByDescending { it.available })
                .take(3),
            myBooking = LibraryBookingInfo(
                seatId = "D021",
                areaName = "北楼二层外文库（东）",
                statusText = "已预约",
            ),
        )
    }

    override suspend fun bookSeat(
        session: SiteSession,
        seatId: String,
        areaCode: String,
        allowSwap: Boolean,
    ): LibrarySeatBookingResult =
        LibrarySeatBookingResult(
            success = true,
            message = if (allowSwap) {
                "已换座到 ${seatId.trim().uppercase()}"
            } else {
                "座位 ${seatId.trim().uppercase()} 预约成功"
            },
            finalUrl = "http://rg.lib.xjtu.edu.cn:8086/my/",
        )
}

private object PreviewCouponRepository : CouponRepository {
    private val records = listOf(
        CouponRecord(
            sendId = "preview-coupon-1",
            showCardId = "meal-20260601",
            voucherName = "康桥苑加餐券",
            typeName = "餐补券",
            amountFen = 800,
            leftAmountFen = 800,
            leftCount = 1,
            startDate = "2026-06-01",
            endDate = "2026-06-30",
        ),
        CouponRecord(
            sendId = "preview-coupon-2",
            showCardId = "meal-20260515",
            voucherName = "兴庆校区夜宵券",
            typeName = "餐补券",
            amountFen = 500,
            leftAmountFen = 0,
            leftCount = 0,
            startDate = "2026-05-15",
            endDate = "2026-06-15",
        ),
    )

    override suspend fun coupons(
        session: SiteSession,
        filter: CouponFilter,
        page: Int,
        pageSize: Int,
    ): CouponPage {
        val filtered = when (filter) {
            CouponFilter.AVAILABLE,
            CouponFilter.USABLE -> records.filter { it.leftCount > 0 || it.leftAmountFen > 0 }
            CouponFilter.USED_UP -> records.filter { it.leftCount == 0 && it.leftAmountFen == 0L }
            CouponFilter.EXPIRED -> emptyList()
        }
        val fromIndex = ((page - 1) * pageSize).coerceAtLeast(0)
        val pageRecords = if (fromIndex >= filtered.size) {
            emptyList()
        } else {
            filtered.drop(fromIndex).take(pageSize)
        }
        return CouponPage(
            filter = filter,
            total = filtered.size,
            records = pageRecords,
        )
    }
}

private object PreviewSchoolCourseRepository : SchoolCourseRepository {
    private val records = listOf(
        SchoolCourseItem(
            courseCode = "MATH1001",
            courseName = "高等数学",
            sectionNumber = "01",
            teacher = "王老师",
            department = "数学与统计学院",
            credit = 3.0,
            enrollCount = 86,
            capacity = 100,
            scheduleLocation = "周三 1-2 节 主楼-101",
            campus = "兴庆校区",
            teachingClassId = "preview-school-course-1",
            termCode = "2025-2026-2",
        ),
        SchoolCourseItem(
            courseCode = "PHYS1001",
            courseName = "大学物理",
            sectionNumber = "02",
            teacher = "李老师",
            department = "物理学院",
            credit = 2.0,
            enrollCount = 120,
            capacity = 140,
            scheduleLocation = "周四 3-4 节 中二-3201",
            campus = "兴庆校区",
            teachingClassId = "preview-school-course-2",
            termCode = "2025-2026-2",
        ),
        SchoolCourseItem(
            courseCode = "CS2001",
            courseName = "程序设计基础",
            sectionNumber = "03",
            teacher = "陈老师",
            department = "计算机科学与技术学院",
            credit = 3.0,
            enrollCount = 74,
            capacity = 80,
            scheduleLocation = "周一 5-6 节 涵英楼-5-102",
            campus = "创新港校区",
            teachingClassId = "preview-school-course-3",
            termCode = "2025-2026-2",
        ),
    )

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
        val campusName = when (campusCode) {
            "1" -> "兴庆校区"
            "2" -> "雁塔校区"
            "3" -> "曲江校区"
            "4" -> "苏州校区"
            "5" -> "创新港校区"
            else -> ""
        }
        val filtered = records.filter {
            (courseName.isBlank() || it.courseName.contains(courseName, ignoreCase = true)) &&
                (teacher.isBlank() || it.teacher.contains(teacher, ignoreCase = true)) &&
                (campusName.isBlank() || it.campus == campusName) &&
                (termCode.isNullOrBlank() || it.termCode == termCode)
        }
        val fromIndex = ((page - 1) * pageSize).coerceAtLeast(0)
        return SchoolCoursePage(
            termCode = termCode?.takeIf { it.isNotBlank() } ?: "2025-2026-2",
            total = filtered.size,
            page = page,
            pageSize = pageSize,
            records = if (fromIndex >= filtered.size) emptyList() else filtered.drop(fromIndex).take(pageSize),
        )
    }
}

private val previewCourses = listOf(
    CourseItem(
        name = "高等数学",
        teacher = "王老师",
        location = "主楼-101",
        dayOfWeek = 3,
        startSection = 1,
        endSection = 2,
        weeks = (1..16).toList(),
    ),
    CourseItem(
        name = "大学物理",
        teacher = "李老师",
        location = "中二-3201",
        dayOfWeek = 3,
        startSection = 3,
        endSection = 4,
        weeks = (1..16).toList(),
    ),
    CourseItem(
        name = "线性代数",
        teacher = "张老师",
        location = "主楼-102",
        dayOfWeek = 4,
        startSection = 1,
        endSection = 2,
        weeks = (1..12).toList(),
    ),
)
