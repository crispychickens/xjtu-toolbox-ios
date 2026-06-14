import Foundation

struct SharedCourseItem: Identifiable, Equatable, Codable {
    let id: String
    let name: String
    let teacher: String
    let location: String
    let dayOfWeek: Int
    let startSection: Int
    let endSection: Int
    let weeks: [Int]
}

struct SharedExamItem: Identifiable, Equatable, Codable {
    let id: String
    let courseName: String
    let time: String
    let location: String
    let courseCode: String
    let examDate: String
    let examTime: String
    let seatNumber: String
}

struct SharedTextbookItem: Identifiable, Equatable, Codable {
    let id: String
    let courseName: String
    let textbookName: String
    let author: String
    let publisher: String
    let isbn: String
    let edition: String
    let price: String
    let hasSubstantiveTextbook: Bool
}

struct SharedGradeItem: Identifiable, Equatable, Codable {
    let id: String
    let courseName: String
    let score: String
    let credit: Double
    let gradePoint: Double
    let hasDetail: Bool
    let termCode: String
}

struct SharedGradeDetailItem: Identifiable, Equatable, Codable {
    let name: String
    let percent: Double
    let score: String

    var id: String {
        "\(name)|\(percent)|\(score)"
    }
}

struct SharedGradeDetail: Equatable, Codable {
    let courseName: String
    let score: String
    let credit: Double
    let gradePoint: Double
    let examType: String
    let courseProperty: String?
    let examProperty: String
    let isReplacement: Bool
    let isPassed: Bool
    let specificReason: String?
    let items: [SharedGradeDetailItem]
}

struct SharedCampusCardInfo: Equatable, Codable {
    let balanceYuan: Double
    let holderName: String
}

struct SharedCampusCardTransaction: Identifiable, Equatable, Codable {
    let id: String
    let time: String
    let merchant: String
    let amountYuan: Double
    let isIncome: Bool
    let balanceAfterYuan: Double?
}

struct SharedNoticeItem: Identifiable, Equatable, Codable {
    let id: String
    let title: String
    let link: String
    let source: String
    let date: String?
}

struct SharedNoticePage: Equatable, Codable {
    let total: Int
    let records: [SharedNoticeItem]
}

struct SharedEmptyRoom: Identifiable, Equatable, Codable {
    let id: String
    let name: String
    let campus: String
    let building: String
    let availableSections: [Int]
    let capacity: Int
}

struct SharedLibraryAreaStats: Identifiable, Equatable, Codable {
    let code: String
    let name: String
    let floor: String
    let available: Int
    let total: Int

    var id: String { code }
    var isOpen: Bool { total > 0 }
    var availabilityRate: Double { total > 0 ? Double(available) / Double(total) : 0 }
}

struct SharedLibrarySeatItem: Identifiable, Equatable, Codable {
    let seatId: String
    let available: Bool

    var id: String { seatId }
}

struct SharedLibraryBookingInfo: Equatable, Codable {
    let seatId: String
    let areaName: String?
    let statusText: String?
}

struct SharedLibrarySeatSnapshot: Equatable, Codable {
    let selectedAreaCode: String
    let areas: [SharedLibraryAreaStats]
    let seats: [SharedLibrarySeatItem]
    let recommendedAreas: [SharedLibraryAreaStats]
    let myBooking: SharedLibraryBookingInfo?
}

struct SharedLibrarySeatBookingResult: Equatable, Codable {
    let success: Bool
    let message: String
    let finalURL: String
}

enum SharedCouponFilter: String, CaseIterable, Identifiable, Codable {
    case available
    case usable
    case usedUp
    case expired

    var id: String { rawValue }

    var title: String {
        switch self {
        case .available:
            return "待使用"
        case .usable:
            return "可使用"
        case .usedUp:
            return "已用完"
        case .expired:
            return "已过期"
        }
    }
}

struct SharedCouponRecord: Identifiable, Equatable, Codable {
    let id: String
    let sendId: String
    let showCardId: String
    let voucherName: String
    let typeName: String
    let amountYuan: Double
    let leftAmountYuan: Double
    let leftCount: Int
    let startDate: String
    let endDate: String
    let imageURL: String
}

struct SharedCouponPage: Equatable, Codable {
    let filter: SharedCouponFilter
    let total: Int
    let records: [SharedCouponRecord]
}

struct SharedSchoolCourse: Identifiable, Equatable, Codable {
    let id: String
    let courseCode: String
    let courseName: String
    let sectionNumber: String
    let teacher: String
    let department: String
    let credit: Double
    let enrollCount: Int
    let capacity: Int
    let scheduleLocation: String
    let campus: String
    let termCode: String
}

struct SharedSchoolCoursePage: Equatable, Codable {
    let termCode: String
    let total: Int
    let page: Int
    let pageSize: Int
    let records: [SharedSchoolCourse]
}

struct SharedDashboardSnapshot: Equatable, Codable {
    let todayCourses: [SharedCourseItem]
    let campusCard: SharedCampusCardInfo?
    let notices: [SharedNoticeItem]
}

struct SharedScheduleSnapshot: Equatable, Codable {
    let courses: [SharedCourseItem]
    let exams: [SharedExamItem]
    let textbooks: [SharedTextbookItem]
}

struct SharedGradeSnapshot: Equatable, Codable {
    let grades: [SharedGradeItem]
    let weightedGpa: Double?
    let totalCredits: Double
}

struct SharedCampusCardSnapshot: Equatable, Codable {
    let info: SharedCampusCardInfo
    let transactions: [SharedCampusCardTransaction]
    let totalTransactions: Int
}

protocol SharedFeatureProviding {
    func dashboard(dayOfWeek: Int) async throws -> SharedDashboardSnapshot
    func schedule() async throws -> SharedScheduleSnapshot
    func grades() async throws -> SharedGradeSnapshot
    func gradeDetail(gradeId: String) async throws -> SharedGradeDetail
    func campusCard(page: Int, pageSize: Int) async throws -> SharedCampusCardSnapshot
    func noticePage(page: Int) async throws -> SharedNoticePage
    func emptyRooms(campus: String, date: String, sections: ClosedRange<Int>) async throws -> [SharedEmptyRoom]
    func librarySeats(areaCode: String?) async throws -> SharedLibrarySeatSnapshot
    func bookLibrarySeat(seatId: String, areaCode: String, allowSwap: Bool) async throws -> SharedLibrarySeatBookingResult
    func coupons(filter: SharedCouponFilter, page: Int, pageSize: Int) async throws -> SharedCouponPage
    func schoolCourses(
        termCode: String?,
        courseName: String,
        teacher: String,
        campusCode: String,
        weekday: Int,
        page: Int,
        pageSize: Int
    ) async throws -> SharedSchoolCoursePage
}

final class PreviewFeatureProvider: SharedFeatureProviding {
    func dashboard(dayOfWeek: Int) async throws -> SharedDashboardSnapshot {
        SharedDashboardSnapshot(
            todayCourses: sampleCourses.filter { $0.dayOfWeek == dayOfWeek },
            campusCard: SharedCampusCardInfo(balanceYuan: 42.5, holderName: "学生"),
            notices: Array(sampleNotices.prefix(2))
        )
    }

    func schedule() async throws -> SharedScheduleSnapshot {
        SharedScheduleSnapshot(
            courses: sampleCourses,
            exams: [
                SharedExamItem(
                    id: "exam-1",
                    courseName: "高等数学",
                    time: "2026-01-10 09:00-11:00",
                    location: "主楼-101",
                    courseCode: "MATH1001",
                    examDate: "2026-01-10",
                    examTime: "09:00-11:00",
                    seatNumber: "12"
                )
            ],
            textbooks: [
                SharedTextbookItem(
                    id: "textbook-1",
                    courseName: "高等数学",
                    textbookName: "高等数学 第八版",
                    author: "同济大学数学系",
                    publisher: "高等教育出版社",
                    isbn: "9787040589812",
                    edition: "第八版",
                    price: "58.00 元",
                    hasSubstantiveTextbook: true
                ),
                SharedTextbookItem(
                    id: "textbook-2",
                    courseName: "线性代数",
                    textbookName: "无教材",
                    author: "",
                    publisher: "",
                    isbn: "",
                    edition: "",
                    price: "",
                    hasSubstantiveTextbook: false
                )
            ]
        )
    }

    func grades() async throws -> SharedGradeSnapshot {
        let grades = [
            SharedGradeItem(id: "grade-1", courseName: "高等数学", score: "92", credit: 3.0, gradePoint: 3.9, hasDetail: true, termCode: "2025-2026-2"),
            SharedGradeItem(id: "grade-2", courseName: "大学物理", score: "85", credit: 2.0, gradePoint: 3.25, hasDetail: true, termCode: "2025-2026-1")
        ]
        let credits = grades.reduce(0) { $0 + $1.credit }
        let gpa = grades.reduce(0) { $0 + $1.credit * $1.gradePoint } / credits
        return SharedGradeSnapshot(grades: grades, weightedGpa: gpa, totalCredits: credits)
    }

    func gradeDetail(gradeId: String) async throws -> SharedGradeDetail {
        switch gradeId {
        case "grade-1":
            return SharedGradeDetail(
                courseName: "高等数学",
                score: "92",
                credit: 3.0,
                gradePoint: 3.9,
                examType: "正常考试",
                courseProperty: "专业基础课",
                examProperty: "正常",
                isReplacement: false,
                isPassed: true,
                specificReason: nil,
                items: [
                    SharedGradeDetailItem(name: "平时成绩", percent: 0.3, score: "95"),
                    SharedGradeDetailItem(name: "期末考试", percent: 0.7, score: "91")
                ]
            )
        case "grade-2":
            return SharedGradeDetail(
                courseName: "大学物理",
                score: "85",
                credit: 2.0,
                gradePoint: 3.25,
                examType: "正常考试",
                courseProperty: "专业基础课",
                examProperty: "正常",
                isReplacement: false,
                isPassed: true,
                specificReason: nil,
                items: []
            )
        default:
            throw PreviewFeatureError.missingGradeDetail
        }
    }

    func campusCard(page: Int, pageSize: Int) async throws -> SharedCampusCardSnapshot {
        let start = max(0, (page - 1) * pageSize)
        let end = min(sampleCampusCardTransactions.count, start + pageSize)
        let transactions = start < end ? Array(sampleCampusCardTransactions[start..<end]) : []
        return SharedCampusCardSnapshot(
            info: SharedCampusCardInfo(balanceYuan: 42.5, holderName: "学生"),
            transactions: transactions,
            totalTransactions: sampleCampusCardTransactions.count
        )
    }

    func noticePage(page: Int) async throws -> SharedNoticePage {
        SharedNoticePage(
            total: sampleNotices.count,
            records: page == 1 ? sampleNotices : []
        )
    }

    func emptyRooms(campus: String, date: String, sections: ClosedRange<Int>) async throws -> [SharedEmptyRoom] {
        [
            SharedEmptyRoom(id: "room-1", name: "中二-3201", campus: campus, building: "中二", availableSections: Array(sections), capacity: 120),
            SharedEmptyRoom(id: "room-2", name: "主楼-101", campus: campus, building: "主楼", availableSections: [1, 2, 3, 4], capacity: 60)
        ]
    }

    func librarySeats(areaCode: String?) async throws -> SharedLibrarySeatSnapshot {
        let areas = sampleLibraryAreas
        let selected = areaCode.flatMap { code in areas.first { $0.code == code }?.code } ?? areas[0].code
        return SharedLibrarySeatSnapshot(
            selectedAreaCode: selected,
            areas: areas,
            seats: [
                SharedLibrarySeatItem(seatId: "D021", available: true),
                SharedLibrarySeatItem(seatId: "D022", available: true),
                SharedLibrarySeatItem(seatId: "D023", available: false),
                SharedLibrarySeatItem(seatId: "D024", available: true),
                SharedLibrarySeatItem(seatId: "E101", available: false)
            ],
            recommendedAreas: Array(areas.filter { $0.isOpen && $0.available > 0 }.sorted {
                if $0.availabilityRate != $1.availabilityRate {
                    return $0.availabilityRate > $1.availabilityRate
                }
                return $0.available > $1.available
            }.prefix(3)),
            myBooking: SharedLibraryBookingInfo(
                seatId: "D021",
                areaName: "北楼二层外文库（东）",
                statusText: "已预约"
            )
        )
    }

    func bookLibrarySeat(seatId: String, areaCode: String, allowSwap: Bool) async throws -> SharedLibrarySeatBookingResult {
        SharedLibrarySeatBookingResult(
            success: true,
            message: allowSwap ? "已换座到 \(seatId.uppercased())" : "座位 \(seatId.uppercased()) 预约成功",
            finalURL: "http://rg.lib.xjtu.edu.cn:8086/my/"
        )
    }

    func coupons(filter: SharedCouponFilter, page: Int, pageSize: Int) async throws -> SharedCouponPage {
        let filtered = sampleCoupons.filter { coupon in
            switch filter {
            case .available:
                return false
            case .usable:
                return coupon.leftCount > 0 || coupon.leftAmountYuan > 0
            case .usedUp:
                return coupon.leftCount == 0 && coupon.leftAmountYuan == 0
            case .expired:
                return false
            }
        }
        let start = max(0, (page - 1) * pageSize)
        let end = min(filtered.count, start + pageSize)
        let records = start < end ? Array(filtered[start..<end]) : []
        return SharedCouponPage(filter: filter, total: filtered.count, records: records)
    }

    func schoolCourses(
        termCode: String?,
        courseName: String,
        teacher: String,
        campusCode: String,
        weekday: Int,
        page: Int,
        pageSize: Int
    ) async throws -> SharedSchoolCoursePage {
        let selectedTerm = termCode?.isEmpty == false ? termCode : nil
        let campusName = [
            "1": "兴庆校区",
            "2": "雁塔校区",
            "3": "曲江校区",
            "4": "苏州校区",
            "5": "创新港校区"
        ][campusCode] ?? ""
        let filtered = sampleSchoolCourses.filter { course in
            (courseName.isEmpty || course.courseName.localizedCaseInsensitiveContains(courseName)) &&
                (teacher.isEmpty || course.teacher.localizedCaseInsensitiveContains(teacher)) &&
                (campusName.isEmpty || course.campus == campusName) &&
                (selectedTerm == nil || course.termCode == selectedTerm)
        }
        let start = max(0, (page - 1) * pageSize)
        let end = min(filtered.count, start + pageSize)
        let records = start < end ? Array(filtered[start..<end]) : []
        return SharedSchoolCoursePage(
            termCode: selectedTerm ?? "2025-2026-2",
            total: filtered.count,
            page: page,
            pageSize: pageSize,
            records: records
        )
    }

    private var sampleCourses: [SharedCourseItem] {
        [
            SharedCourseItem(id: "course-1", name: "高等数学", teacher: "王老师", location: "主楼-101", dayOfWeek: 3, startSection: 1, endSection: 2, weeks: Array(1...16)),
            SharedCourseItem(id: "course-2", name: "大学物理", teacher: "李老师", location: "中二-3201", dayOfWeek: 3, startSection: 3, endSection: 4, weeks: Array(1...16)),
            SharedCourseItem(id: "course-3", name: "线性代数", teacher: "张老师", location: "主楼-102", dayOfWeek: 4, startSection: 1, endSection: 2, weeks: Array(1...12))
        ]
    }

    private var sampleNotices: [SharedNoticeItem] {
        [
            SharedNoticeItem(id: "notice-1", title: "关于考试安排的通知", link: "https://example.edu/1", source: "教务处", date: "2026-05-20"),
            SharedNoticeItem(id: "notice-2", title: "校园网络维护通知", link: "https://example.edu/2", source: "网信中心", date: "2026-05-19"),
            SharedNoticeItem(id: "notice-3", title: "空闲教室查询更新", link: "https://example.edu/3", source: "一网通办", date: "2026-05-18")
        ]
    }

    private var sampleCampusCardTransactions: [SharedCampusCardTransaction] {
        [
            SharedCampusCardTransaction(id: "tx-1", time: "2026-05-20 12:00", merchant: "康桥苑", amountYuan: -12.0, isIncome: false, balanceAfterYuan: 30.5),
            SharedCampusCardTransaction(id: "tx-2", time: "2026-05-20 08:12", merchant: "充值", amountYuan: 100.0, isIncome: true, balanceAfterYuan: 130.5),
            SharedCampusCardTransaction(id: "tx-3", time: "2026-05-19 18:40", merchant: "梧桐苑", amountYuan: -18.5, isIncome: false, balanceAfterYuan: 49.0),
            SharedCampusCardTransaction(id: "tx-4", time: "2026-05-19 10:16", merchant: "图书馆打印", amountYuan: -1.2, isIncome: false, balanceAfterYuan: 67.5)
        ]
    }

    private var sampleLibraryAreas: [SharedLibraryAreaStats] {
        [
            SharedLibraryAreaStats(code: "north2east", name: "北楼二层外文库（东）", floor: "二楼", available: 42, total: 120),
            SharedLibraryAreaStats(code: "south2", name: "南楼二层大厅", floor: "二楼", available: 18, total: 112),
            SharedLibraryAreaStats(code: "north4middle", name: "北楼四层中间", floor: "四楼", available: 56, total: 144),
            SharedLibraryAreaStats(code: "north4southeast", name: "北楼四层东南侧", floor: "四楼", available: 0, total: 136)
        ]
    }

    private var sampleCoupons: [SharedCouponRecord] {
        [
            SharedCouponRecord(
                id: "coupon-1",
                sendId: "preview-coupon-1",
                showCardId: "meal-20260601",
                voucherName: "康桥苑加餐券",
                typeName: "餐补券",
                amountYuan: 8,
                leftAmountYuan: 8,
                leftCount: 1,
                startDate: "2026-06-01",
                endDate: "2026-06-30",
                imageURL: ""
            ),
            SharedCouponRecord(
                id: "coupon-2",
                sendId: "preview-coupon-2",
                showCardId: "meal-20260515",
                voucherName: "兴庆校区夜宵券",
                typeName: "餐补券",
                amountYuan: 5,
                leftAmountYuan: 0,
                leftCount: 0,
                startDate: "2026-05-15",
                endDate: "2026-06-15",
                imageURL: ""
            )
        ]
    }

    private var sampleSchoolCourses: [SharedSchoolCourse] {
        [
            SharedSchoolCourse(
                id: "preview-school-course-1",
                courseCode: "MATH1001",
                courseName: "高等数学",
                sectionNumber: "01",
                teacher: "王老师",
                department: "数学与统计学院",
                credit: 3,
                enrollCount: 86,
                capacity: 100,
                scheduleLocation: "周三 1-2 节 主楼-101",
                campus: "兴庆校区",
                termCode: "2025-2026-2"
            ),
            SharedSchoolCourse(
                id: "preview-school-course-2",
                courseCode: "PHYS1001",
                courseName: "大学物理",
                sectionNumber: "02",
                teacher: "李老师",
                department: "物理学院",
                credit: 2,
                enrollCount: 120,
                capacity: 140,
                scheduleLocation: "周四 3-4 节 中二-3201",
                campus: "兴庆校区",
                termCode: "2025-2026-2"
            ),
            SharedSchoolCourse(
                id: "preview-school-course-3",
                courseCode: "CS2001",
                courseName: "程序设计基础",
                sectionNumber: "03",
                teacher: "陈老师",
                department: "计算机科学与技术学院",
                credit: 3,
                enrollCount: 74,
                capacity: 80,
                scheduleLocation: "周一 5-6 节 涵英楼-5-102",
                campus: "创新港校区",
                termCode: "2025-2026-2"
            )
        ]
    }
}

private enum PreviewFeatureError: LocalizedError {
    case missingGradeDetail

    var errorDescription: String? {
        "未找到预览成绩详情"
    }
}
