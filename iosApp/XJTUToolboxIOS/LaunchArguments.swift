import Foundation

enum XjtuDependencyMode: Equatable {
    case preview
    case realLoginValidation
    case realEmptyRooms
    case realPublicData
    case realCampusCardAndPublicData
    case realFirstReleaseCore

    var displayName: String {
        switch self {
        case .preview:
            return "预览模式"
        case .realLoginValidation:
            return "真实登录验证"
        case .realEmptyRooms:
            return "真实空教室验证"
        case .realPublicData:
            return "真实公共数据验证"
        case .realCampusCardAndPublicData:
            return "真实一卡通验证"
        case .realFirstReleaseCore:
            return "真实首发核心"
        }
    }

    var authenticationSourceLabel: String {
        switch self {
        case .preview, .realEmptyRooms, .realPublicData:
            return "预览登录"
        case .realLoginValidation, .realCampusCardAndPublicData, .realFirstReleaseCore:
            return "真实 CAS 登录"
        }
    }

    var featureDataSourceLabel: String {
        switch self {
        case .preview:
            return "预览功能数据"
        case .realLoginValidation:
            return "预览功能数据"
        case .realEmptyRooms:
            return "真实空教室，其他预览"
        case .realPublicData:
            return "真实公告/空教室，登录态功能预览"
        case .realCampusCardAndPublicData:
            return "真实一卡通/公共数据，其他预览"
        case .realFirstReleaseCore:
            return "真实首发核心数据"
        }
    }

    var validationNotice: String? {
        switch self {
        case .preview:
            return "当前不会主动访问 CAS、教务或一卡通。"
        case .realLoginValidation:
            return "当前只验证 CAS 登录链路；首页、日程、学辅和我的页展示的是预览业务数据，不代表你的真实教务或一卡通信息。"
        case .realEmptyRooms:
            return "当前只启用真实空教室公共数据；登录态业务数据仍为预览。"
        case .realPublicData:
            return "当前只启用真实公告和空教室公共数据；登录态业务数据仍为预览。"
        case .realCampusCardAndPublicData:
            return "当前启用真实 CAS、一卡通和公共数据；日程与成绩仍为预览。"
        case .realFirstReleaseCore:
            return nil
        }
    }

    var allowsBrowserAuthHandoff: Bool {
        switch self {
        case .preview, .realEmptyRooms, .realPublicData:
            return true
        case .realLoginValidation, .realCampusCardAndPublicData, .realFirstReleaseCore:
            return false
        }
    }

    var browserAuthNotice: String? {
        allowsBrowserAuthHandoff ? nil : "学校 CAS 当前不接受 App 回跳 service，网页登录需白名单或 Universal Link 后才能启用；请使用上方账号密码登录。"
    }

    var cacheKeySegment: String {
        switch self {
        case .preview:
            return "preview"
        case .realLoginValidation:
            return "realLoginValidation"
        case .realEmptyRooms:
            return "realEmptyRooms"
        case .realPublicData:
            return "realPublicData"
        case .realCampusCardAndPublicData:
            return "realCampusCardAndPublicData"
        case .realFirstReleaseCore:
            return "realFirstReleaseCore"
        }
    }
}

enum XjtuBuildConfiguration {
    case debug
    case release

    static var current: XjtuBuildConfiguration {
        #if DEBUG
        .debug
        #else
        .release
        #endif
    }
}

enum XjtuLaunchArguments {
    static let realLoginValidation = "-XJTURealLoginValidation"
    static let realEmptyRooms = "-XJTURealEmptyRooms"
    static let realPublicData = "-XJTURealPublicData"
    static let realCampusCardAndPublicData = "-XJTURealCampusCardAndPublicData"
    static let realFirstReleaseCore = "-XJTURealFirstReleaseCore"
    static let previewAutoLogin = "-XJTUPreviewAutoLogin"
    static let previewAccountChoice = "-XJTUPreviewAccountChoice"
    static let autoSiteVerification = "-XJTUAutoSiteVerification"
    static let requireFreshLogin = "-XJTURequireFreshLogin"
    static let authNetworkDebug = "-XJTUAuthNetworkDebug"
    static let initialUsername = "-XJTUInitialUsername"
    static let startTab = "-XJTUStartTab"
    static let startTool = "-XJTUStartTool"
    static let startScheduleView = "-XJTUStartScheduleView"

    static var dependencyMode: XjtuDependencyMode {
        dependencyMode(arguments: arguments, buildConfiguration: .current)
    }

    static func dependencyMode(
        arguments: Set<String>,
        buildConfiguration: XjtuBuildConfiguration
    ) -> XjtuDependencyMode {
        guard buildConfiguration == .debug else {
            return .realFirstReleaseCore
        }
        if arguments.contains(realFirstReleaseCore) {
            return .realFirstReleaseCore
        }
        if arguments.contains(realCampusCardAndPublicData) {
            return .realCampusCardAndPublicData
        }
        if arguments.contains(realPublicData) {
            return .realPublicData
        }
        if arguments.contains(realEmptyRooms) {
            return .realEmptyRooms
        }
        if arguments.contains(realLoginValidation) {
            return .realLoginValidation
        }
        return .preview
    }

    static var allowsPreviewAutoLogin: Bool {
        guard arguments.contains(previewAutoLogin) else { return false }
        switch dependencyMode {
        case .preview, .realEmptyRooms, .realPublicData:
            return true
        case .realLoginValidation, .realCampusCardAndPublicData, .realFirstReleaseCore:
            return false
        }
    }

    static var allowsPreviewAccountChoice: Bool {
        guard arguments.contains(previewAccountChoice) else { return false }
        switch dependencyMode {
        case .preview:
            return true
        case .realEmptyRooms, .realPublicData, .realLoginValidation, .realCampusCardAndPublicData, .realFirstReleaseCore:
            return false
        }
    }

    static var shouldAutoLoadFeatures: Bool {
        true
    }

    static var shouldAutoBeginSiteVerification: Bool {
        arguments.contains(autoSiteVerification)
    }

    static var shouldRequireFreshLogin: Bool {
        arguments.contains(requireFreshLogin)
    }

    static var shouldLogAuthNetworkDebug: Bool {
        shouldLogAuthNetworkDebug(arguments: arguments, buildConfiguration: .current)
    }

    static func shouldLogAuthNetworkDebug(
        arguments: Set<String>,
        buildConfiguration: XjtuBuildConfiguration
    ) -> Bool {
        buildConfiguration == .debug && arguments.contains(authNetworkDebug)
    }

    static var persistentFeatureCacheKeyPrefix: String {
        persistentFeatureCacheKeyPrefix(
            dependencyMode: dependencyMode,
            buildConfiguration: .current
        )
    }

    static func persistentFeatureCacheKeyPrefix(
        dependencyMode: XjtuDependencyMode,
        buildConfiguration: XjtuBuildConfiguration
    ) -> String {
        let buildSegment = buildConfiguration == .debug ? "debug" : "release"
        return "com.xjtu.toolbox.ios.featureCache.\(buildSegment).\(dependencyMode.cacheKeySegment)."
    }

    static var prefilledUsername: String? {
        guard let index = raw.firstIndex(of: initialUsername),
              raw.indices.contains(index + 1) else {
            return nil
        }
        return raw[index + 1].trimmingCharacters(in: .whitespacesAndNewlines).takeIfNotEmpty()
    }

    static var initialTab: AppTab {
        guard let index = raw.firstIndex(of: startTab),
              raw.indices.contains(index + 1),
              let tab = AppTab(rawValue: raw[index + 1].lowercased()) else {
            return .schedule
        }
        return tab
    }

    static var initialToolName: String? {
        guard let index = raw.firstIndex(of: startTool),
              raw.indices.contains(index + 1) else {
            return nil
        }
        return raw[index + 1].trimmingCharacters(in: .whitespacesAndNewlines).takeIfNotEmpty()
    }

    static var initialScheduleViewName: String? {
        guard let index = raw.firstIndex(of: startScheduleView),
              raw.indices.contains(index + 1) else {
            return nil
        }
        return raw[index + 1].trimmingCharacters(in: .whitespacesAndNewlines).takeIfNotEmpty()
    }

    private static var raw: [String] {
        ProcessInfo.processInfo.arguments
    }

    private static var arguments: Set<String> {
        Set(raw)
    }
}

private extension String {
    func takeIfNotEmpty() -> String? {
        isEmpty ? nil : self
    }
}
