package com.drdisagree.teledrive.presentation.platform

import com.drdisagree.teledrive.core.permissions.AppPermission
import com.drdisagree.teledrive.core.permissions.PermissionChecker

/**
 * Stage 4 runs the full interface; what needs iOS-specific services (photo-library backup,
 * background transfers, Face ID) arrives in stage 5 and switches these flags on then.
 */
class IosPlatformCapabilities : PlatformCapabilities {
    override val supportsAutoBackup: Boolean = false
    override val requiresPermissions: Boolean = false
    override val supportsPullToRefresh: Boolean = true
    override val supportsAppLock: Boolean = false
    override val supportsScreenCaptureBlocking: Boolean = false
    override val supportsDynamicColor: Boolean = false
}

/**
 * The app only touches its own container and files the user picks, neither of which needs a
 * runtime permission on iOS. Photo-library access comes with the gallery backup in stage 5.
 */
class IosPermissionChecker : PermissionChecker {
    override fun isGranted(permission: AppPermission): Boolean = true
    override fun hasAllFilesAccess(): Boolean = true
    override fun statuses(): Map<AppPermission, Boolean> = AppPermission.entries.associateWith { true }
    override fun missingCritical(): List<AppPermission> = emptyList()
    override fun isRequestable(permission: AppPermission): Boolean = false
}

/** iOS has no browsable camera or pictures folder; the photo library is reached in stage 5. */
class IosStandardFolderPaths : StandardFolderPaths {
    override val camera: String? = null
    override val pictures: String? = null
    override val movies: String? = null
}

/** Needs "tg" under LSApplicationQueriesSchemes in Info.plist for canOpenURL to answer. */
class IosTelegramLinkOpener : TelegramLinkOpener {
    override val canOpenTelegram: Boolean
        get() = canOpenExternalUrl("tg://")

    override fun open(link: String): Boolean = openExternalUrl(link)
}
