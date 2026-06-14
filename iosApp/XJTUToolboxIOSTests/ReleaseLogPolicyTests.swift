import XCTest
@testable import XJTUToolboxIOS

#if DEBUG && canImport(XJTUToolboxShared)
final class ReleaseLogPolicyTests: XCTestCase {
    func testDebugAuthMetadataDoesNotExposeSensitiveValues() {
        let secret = "sensitive-value-3124000000"
        let rawURL = "https://login.xjtu.edu.cn/cas/login/\(secret)?ticket=\(secret)#state=\(secret)&code=\(secret)"
        let formBody = "username=\(secret)&password=\(secret)&execution=e1s1"
        let jsonBody = #"{"token":"\#(secret)","status":"\#(secret)"}"#
        let htmlBody = #"<html><title>\#(secret)</title><el-alert title="\#(secret)"></el-alert></html>"#

        let metadata = [
            AuthNetworkDebugMetadata.sanitizedURL(rawURL),
            AuthNetworkDebugMetadata.parameterNames(rawURL),
            AuthNetworkDebugMetadata.bodyFieldNames(formBody),
            AuthNetworkDebugMetadata.classify(jsonBody),
            AuthNetworkDebugMetadata.classify(htmlBody),
            AuthNetworkDebugMetadata.bodyFieldNames(secret),
        ]

        XCTAssertEqual(metadata[0], "login.xjtu.edu.cn pathSegments=3")
        XCTAssertEqual(metadata[1], "code,state,ticket")
        XCTAssertEqual(metadata[2], "execution,password,username")
        XCTAssertEqual(metadata[3], "json-keys=status,token")
        XCTAssertEqual(metadata[4], "cas-alert-html")
        XCTAssertEqual(metadata[5], "<non-form>")
        XCTAssertTrue(metadata.allSatisfy { !$0.contains(secret) })
    }

    func testDebugAuthMetadataClassifiesBodiesWithoutReturningRawText() {
        let secret = "private-response-text"

        XCTAssertEqual(
            AuthNetworkDebugMetadata.classify("<html><body>\(secret)</body></html>"),
            "html"
        )
        XCTAssertEqual(
            AuthNetworkDebugMetadata.classify("Safety Verify \(secret)"),
            "safety-html"
        )
        XCTAssertEqual(
            AuthNetworkDebugMetadata.classify(secret),
            "text-len=\(secret.count)"
        )
    }
}
#endif
