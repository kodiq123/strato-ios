import Foundation
import TDLibFramework
import TeleDriveKit

/// Implements the Kotlin TdJsonTransport over TDLib's C JSON interface (td_json_client.h).
/// Kotlin calls receive(timeoutSeconds:) from a single dedicated thread, as TDLib requires.
final class TdJsonBridge: NSObject, TdJsonTransport {

    func createClientId() -> Int32 {
        td_create_client_id()
    }

    func send(clientId: Int32, request: String) {
        request.withCString { td_send(clientId, $0) }
    }

    func receive(timeoutSeconds: Double) -> String? {
        guard let result = td_receive(timeoutSeconds) else { return nil }
        return String(cString: result)
    }

    func execute(request: String) -> String? {
        let result: UnsafePointer<CChar>? = request.withCString { td_execute($0) }
        guard let result else { return nil }
        return String(cString: result)
    }
}
