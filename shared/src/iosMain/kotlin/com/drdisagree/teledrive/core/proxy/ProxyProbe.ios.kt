package com.drdisagree.teledrive.core.proxy

import com.drdisagree.teledrive.core.telegram.TelegramProxy

/**
 * Reachability check used only when TDLib cannot test a proxy (signed out). The JVM version speaks
 * SOCKS5/HTTP over a raw socket; that port is pending on iOS, so it reports REACHABLE and lets TDLib
 * surface the real failure when the proxy is applied.
 */
actual class ProxyProbe {
    actual suspend fun probe(proxy: TelegramProxy): ProxyProbeResult = ProxyProbeResult.REACHABLE
}
