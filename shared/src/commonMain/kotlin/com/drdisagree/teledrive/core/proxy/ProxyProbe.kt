package com.drdisagree.teledrive.core.proxy

import com.drdisagree.teledrive.core.telegram.TelegramProxy

/** Checks whether a proxy answers without TDLib (used while signed out). */
expect class ProxyProbe {
    suspend fun probe(proxy: TelegramProxy): ProxyProbeResult
}
