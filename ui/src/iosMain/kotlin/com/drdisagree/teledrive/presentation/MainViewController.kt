package com.drdisagree.teledrive.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import com.drdisagree.teledrive.core.telegram.TdJsonTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.UIKit.UIViewController

/**
 * Entry point the Swift host (iosApp/) embeds, handing over its TDLib bridge. Until the
 * presentation layer moves to common code it shows a placeholder that also proves TDLib is
 * linked: getOption("version") is one of the requests TDLib can execute synchronously.
 */
@Suppress("FunctionName", "unused")
fun MainViewController(transport: TdJsonTransport): UIViewController {
    val tdlibVersion = tdlibVersion(transport)
    return ComposeUIViewController {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("TeleDrive", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "iOS port in progress",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        "TDLib ${tdlibVersion ?: "unavailable"}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }
    }
}

private fun tdlibVersion(transport: TdJsonTransport): String? = runCatching {
    val raw = transport.execute("""{"@type":"getOption","name":"version"}""") ?: return null
    Json.parseToJsonElement(raw).jsonObject["value"]?.jsonPrimitive?.content
}.getOrNull()
