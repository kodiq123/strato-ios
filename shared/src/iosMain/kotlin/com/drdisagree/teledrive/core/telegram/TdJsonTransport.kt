package com.drdisagree.teledrive.core.telegram

/**
 * TDLib's JSON interface (td_json_client.h), implemented in Swift by the iOS host because the
 * static TDLibFramework is linked by Xcode, not by Kotlin. Keeping the boundary to four calls
 * means no cinterop is needed and this module still compiles on non-Apple hosts.
 */
interface TdJsonTransport {

    /** td_create_client_id. The client starts on the first request sent to it. */
    fun createClientId(): Int

    /** td_send: queues [request], a JSON-serialized TDLib function. */
    fun send(clientId: Int, request: String)

    /** td_receive: blocks up to [timeoutSeconds]; null when nothing arrived. */
    fun receive(timeoutSeconds: Double): String?

    /** td_execute: runs a synchronous TDLib function such as setLogVerbosityLevel. */
    fun execute(request: String): String?
}
