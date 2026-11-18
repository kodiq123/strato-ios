package com.drdisagree.teledrive.core.telegram

import com.drdisagree.teledrive.core.crypto.KeychainTdlibDatabaseKeyProvider
import com.drdisagree.teledrive.core.crypto.SecureStore
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSBundle
import platform.Foundation.NSLocale
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.preferredLanguages
import platform.UIKit.UIDevice

/** Builds the iOS TelegramClient with this device's paths and identity. */
object IosTelegramFactory {

    fun create(transport: TdJsonTransport, secureStore: SecureStore): IosTelegramClient {
        val support = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true
        ).firstOrNull() as? String ?: error("No Application Support directory")
        val device = UIDevice.currentDevice
        val version = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString")
            as? String ?: "0"
        val language = (NSLocale.preferredLanguages.firstOrNull() as? String)
            ?.substringBefore('-') ?: "en"
        return IosTelegramClient(
            transport = transport,
            databaseKeyProvider = KeychainTdlibDatabaseKeyProvider(secureStore),
            pacer = TelegramPacer(),
            tdlibDirectory = "$support/tdlib",
            deviceModel = device.model,
            systemVersion = "iOS ${device.systemVersion}",
            applicationVersion = version,
            systemLanguageCode = language
        )
    }
}
