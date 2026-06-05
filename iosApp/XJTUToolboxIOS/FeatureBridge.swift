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
}

struct SharedTextbookItem: Identifiable, Equatable, Codable {
    let id: String
    let courseName: String
    let textbookName: String
    let author: String
    let publisher: String
    let isbn: String
    let hasSubstantiveTextbook: Bool
}

struct SharedGradeItem: Identifiable, Equatable, Codable {
    let id: String
    let courseName: String
    let score: String
    let credit: Double
    let gradePoint: Double
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
}

struct SharedNoticeItem: Identifiable, Equatable, Codable {
    let id: String
    let title: String
    let link: String
    let source: String
    let date: String?
}

struct SharedEmptyRoom: Identifiable, Equatable, Codable {
    let id: String
    let name: String
    let campus: String
    let building: String
    let availableSections: [Int]
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
}

protocol SharedFeatureProviding {
    func dashboard(dayOfWeek: Int) async throws -> SharedDashboardSnapshot
    func schedule() async throws -> SharedScheduleSnapshot
    func grades() async throws -> SharedGradeSnapshot
    func campusCard(page: Int, pageSize: Int) async throws -> SharedCampusCardSnapshot
    func notices(page: Int) async throws -> [SharedNoticeItem]
    func emptyRooms(campus: String, date: String, sections: ClosedRange<Int>) async throws -> [SharedEmptyRoom]
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
                SharedExamItem(id: "exam-1", courseName: "高等数学", time: "2026-01-10 09:00", location: "主楼-101")
            ],
            textbooks: [
                SharedTextbookItem(
                    id: "textbook-1",
                    courseName: "高等数学",
                    textbookName: "高等数学 第八版",
                    author: "同济大学数学系",
                    publisher: "高等教育出版社",
                    isbn: "9787040589812",
                    hasSubstantiveTextbook: true
                ),
                SharedTextbookItem(
                    id: "textbook-2",
                    courseName: "线性代数",
                    textbookName: "无教材",
                    author: "",
                    publisher: "",
                    isbn: "",
                    hasSubstantiveTextbook: false
                )
            ]
        )
    }

    func grades() async throws -> SharedGradeSnapshot {
        let grades = [
            SharedGradeItem(id: "grade-1", courseName: "高等数学", score: "92", credit: 3.0, gradePoint: 3.9),
            SharedGradeItem(id: "grade-2", courseName: "大学物理", score: "85", credit: 2.0, gradePoint: 3.25)
        ]
        let credits = grades.reduce(0) { $0 + $1.credit }
        let gpa = grades.reduce(0) { $0 + $1.credit * $1.gradePoint } / credits
        return SharedGradeSnapshot(grades: grades, weightedGpa: gpa, totalCredits: credits)
    }

    func campusCard(page: Int, pageSize: Int) async throws -> SharedCampusCardSnapshot {
        let start = max(0, (page - 1) * pageSize)
        let end = min(sampleCampusCardTransactions.count, start + pageSize)
        let transactions = start < end ? Array(sampleCampusCardTransactions[start..<end]) : []
        return SharedCampusCardSnapshot(
            info: SharedCampusCardInfo(balanceYuan: 42.5, holderName: "学生"),
            transactions: transactions
        )
    }

    func notices(page: Int) async throws -> [SharedNoticeItem] {
        sampleNotices
    }

    func emptyRooms(campus: String, date: String, sections: ClosedRange<Int>) async throws -> [SharedEmptyRoom] {
        [
            SharedEmptyRoom(id: "room-1", name: "中二-3201", campus: campus, building: "中二", availableSections: Array(sections)),
            SharedEmptyRoom(id: "room-2", name: "主楼-101", campus: campus, building: "主楼", availableSections: [1, 2, 3, 4])
        ]
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
            SharedCampusCardTransaction(id: "tx-1", time: "2026-05-20 12:00", merchant: "康桥苑", amountYuan: -12.0, isIncome: false),
            SharedCampusCardTransaction(id: "tx-2", time: "2026-05-20 08:12", merchant: "充值", amountYuan: 100.0, isIncome: true),
            SharedCampusCardTransaction(id: "tx-3", time: "2026-05-19 18:40", merchant: "梧桐苑", amountYuan: -18.5, isIncome: false),
            SharedCampusCardTransaction(id: "tx-4", time: "2026-05-19 10:16", merchant: "图书馆打印", amountYuan: -1.2, isIncome: false)
        ]
    }
}
