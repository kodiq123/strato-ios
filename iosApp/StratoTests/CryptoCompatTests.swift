import XCTest
import StratoKit
@testable import Strato

/// Runs the shared crypto self-test through the real CryptoKit provider: the fixtures were written
/// by the Android (javax.crypto) implementation, so passing means iOS reads and writes the same
/// drive format.
final class CryptoCompatTests: XCTestCase {

    func testFixturesFromAndroidDecryptWithCryptoKit() {
        IosCryptoBridge.shared.aesGcm = CryptoKitAesGcm()
        let failures = CryptoSelfTest.shared.run()
        XCTAssertEqual(failures, [], failures.joined(separator: "\n"))
    }

    /// File, streams, gzip and SHA-256 over Foundation/POSIX/zlib/CommonCrypto, held to java.io.
    func testIoLayerMatchesJavaIo() {
        IosCryptoBridge.shared.aesGcm = CryptoKitAesGcm()
        let failures = IoSelfTest.shared.run(scratch: File(pathname: NSTemporaryDirectory()))
        XCTAssertEqual(failures, [], failures.joined(separator: "\n"))
    }

    func testTdlibIsLinked() {
        let raw = TdJsonBridge().execute(request: #"{"@type":"getOption","name":"version"}"#)
        XCTAssertNotNil(raw)
        XCTAssertTrue(raw?.contains("optionValueString") == true, raw ?? "nil")
    }
}
