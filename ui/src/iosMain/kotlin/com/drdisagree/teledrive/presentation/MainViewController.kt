package com.drdisagree.teledrive.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.window.ComposeUIViewController
import com.drdisagree.teledrive.core.crypto.KeychainCredentialStore
import com.drdisagree.teledrive.core.crypto.SecureStore
import com.drdisagree.teledrive.core.telegram.IosTelegramFactory
import com.drdisagree.teledrive.core.telegram.TdJsonTransport
import com.drdisagree.teledrive.presentation.probe.TelegramProbeScreen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.UIKit.UIViewController

/**
 * Entry point the Swift host (iosApp/) embeds, handing over its TDLib bridge and Keychain store.
 * Until the presentation layer moves to common code (stage 4) it shows the stage-2 probe screen.
 */
@Suppress("FunctionName", "unused")
fun MainViewController(transport: TdJsonTransport, secureStore: SecureStore): UIViewController {
    val tdlibVersion = tdlibVersion(transport)
    val client = IosTelegramFactory.create(transport, secureStore)
    val credentials = KeychainCredentialStore(secureStore)
    return ComposeUIViewController {
        MaterialTheme {
            Surface {
                TelegramProbeScreen(client, credentials, tdlibVersion)
            }
        }
    }
}

/** getOption("version") is one of the requests TDLib executes synchronously. */
private fun tdlibVersion(transport: TdJsonTransport): String? = runCatching {
    val raw = transport.execute("""{"@type":"getOption","name":"version"}""") ?: return null
    Json.parseToJsonElement(raw).jsonObject["value"]?.jsonPrimitive?.content
}.getOrNull()
