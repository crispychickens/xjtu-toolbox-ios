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
import com.xjtu.toolbox.shared.features.CourseItem
import com.xjtu.toolbox.shared.features.DefaultCoreFeatureService
import com.xjtu.toolbox.shared.features.EmptyRoom
import com.xjtu.toolbox.shared.features.EmptyRoomRepository
import com.xjtu.toolbox.shared.features.ExamItem
import com.xjtu.toolbox.shared.features.GradeItem
import com.xjtu.toolbox.shared.features.GradeRepository
import com.xjtu.toolbox.shared.features.NoticeItem
import com.xjtu.toolbox.shared.features.NoticeRepository
import com.xjtu.toolbox.shared.features.ScheduleRepository
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
        )

    fun coreFeatureServiceWithRepositories(
        authManager: AuthManager,
        scheduleRepository: ScheduleRepository = PreviewScheduleRepository,
        gradeRepository: GradeRepository = PreviewGradeRepository,
        campusCardRepository: CampusCardRepository = PreviewCampusCardRepository,
        noticeRepository: NoticeRepository = PreviewNoticeRepository,
        emptyRoomRepository: EmptyRoomRepository = PreviewEmptyRoomRepository,
    ): CoreFeatureService =
        DefaultCoreFeatureService(
            authManager = authManager,
            scheduleRepository = scheduleRepository,
            gradeRepository = gradeRepository,
            campusCardRepository = campusCardRepository,
            noticeRepository = noticeRepository,
            emptyRoomRepository = emptyRoomRepository,
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
                time = "2026-01-10 09:00",
                location = "主楼-101",
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
            ),
            GradeItem(
                courseName = "大学物理",
                score = "85",
                credit = 2.0,
                gradePoint = 3.25,
            ),
        )
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
            ),
            CampusCardTransaction(
                time = "2026-05-20 08:12",
                merchant = "充值",
                amountYuan = 100.0,
                kind = TransactionKind.INCOME,
            ),
        ).take(pageSize)
}

private object PreviewNoticeRepository : NoticeRepository {
    override suspend fun notices(page: Int): List<NoticeItem> =
        listOf(
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
}

private object PreviewEmptyRoomRepository : EmptyRoomRepository {
    override suspend fun rooms(campus: String, date: String, sections: IntRange): List<EmptyRoom> =
        listOf(
            EmptyRoom(
                name = "中二-3201",
                campus = campus,
                building = "中二",
                availableSections = sections.toList(),
            ),
            EmptyRoom(
                name = "主楼-101",
                campus = campus,
                building = "主楼",
                availableSections = listOf(1, 2, 3, 4),
            ),
        )
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
