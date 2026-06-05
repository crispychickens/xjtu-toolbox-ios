import Foundation

enum AppTab: String, CaseIterable, Identifiable {
    case home
    case schedule
    case tools
    case profile

    var id: String { rawValue }

    var title: String {
        switch self {
        case .home: return "首页"
        case .schedule: return "日程"
        case .tools: return "学辅"
        case .profile: return "我的"
        }
    }

    var systemImage: String {
        switch self {
        case .home: return "house"
        case .schedule: return "calendar"
        case .tools: return "book"
        case .profile: return "person"
        }
    }
}

@MainActor
final class Router: ObservableObject {
    @Published var selectedTab: AppTab = XjtuLaunchArguments.initialTab
}
