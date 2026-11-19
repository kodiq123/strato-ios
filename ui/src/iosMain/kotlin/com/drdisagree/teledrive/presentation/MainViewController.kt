package com.drdisagree.teledrive.presentation

import androidx.compose.ui.window.ComposeUIViewController
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.key.Keyer
import coil3.request.Options
import coil3.request.crossfade
import com.drdisagree.teledrive.core.crypto.SecureStore
import com.drdisagree.teledrive.core.files.PendingShare
import com.drdisagree.teledrive.core.logging.DebugLogController
import com.drdisagree.teledrive.core.logging.debugLogModule
import com.drdisagree.teledrive.core.media.ThumbnailFetcherFactory
import com.drdisagree.teledrive.core.media.ThumbnailModel
import com.drdisagree.teledrive.core.media.ThumbnailStore
import com.drdisagree.teledrive.core.media.thumbnailCacheKey
import com.drdisagree.teledrive.core.permissions.PermissionChecker
import com.drdisagree.teledrive.core.publish.PublishScheduler
import com.drdisagree.teledrive.core.telegram.TdJsonTransport
import com.drdisagree.teledrive.core.update.UpdateChecker
import com.drdisagree.teledrive.data.repository.FolderOwnershipRepair
import com.drdisagree.teledrive.domain.repository.FileRepository
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.domain.repository.TrashRepository
import com.drdisagree.teledrive.platform.IosEngine
import com.drdisagree.teledrive.presentation.di.sharedUiModule
import com.drdisagree.teledrive.presentation.platform.IosPermissionChecker
import com.drdisagree.teledrive.presentation.platform.IosPlatformCapabilities
import com.drdisagree.teledrive.presentation.platform.IosStandardFolderPaths
import com.drdisagree.teledrive.presentation.platform.PlatformCapabilities
import com.drdisagree.teledrive.presentation.platform.ProvideIosPlatformActions
import com.drdisagree.teledrive.presentation.platform.StandardFolderPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.dsl.module
import platform.UIKit.UIViewController

/** View models and platform services the full interface needs on top of the engine. */
private val iosUiModule = module {
    includes(debugLogModule, sharedUiModule)
    single { UpdateChecker("") }
    single<PermissionChecker> { IosPermissionChecker() }
    single<StandardFolderPaths> { IosStandardFolderPaths() }
    single<PlatformCapabilities> { IosPlatformCapabilities() }
}

/**
 * Entry point the Swift host (iosApp/) embeds, handing over its TDLib bridge and Keychain store.
 * Starts the shared engine and shows the full TeleDrive interface.
 */
@Suppress("FunctionName", "unused")
fun MainViewController(transport: TdJsonTransport, secureStore: SecureStore): UIViewController {
    val koin = IosEngine.start(transport, secureStore, listOf(iosUiModule))
    startBackgroundWork(koin)
    return ComposeUIViewController {
        setSingletonImageLoaderFactory { context ->
            ImageLoader.Builder(context)
                .components {
                    add(ThumbnailFetcherFactory(koin.get<ThumbnailStore>()))
                    add(Keyer<ThumbnailModel> { data, _: Options -> thumbnailCacheKey(data.fileId) })
                }
                .crossfade(true)
                .build()
        }
        ProvideIosPlatformActions {
            TeleDriveApp(
                pendingShare = koin.get<PendingShare>(),
                notificationDestination = null,
                onDestinationHandled = {}
            )
        }
    }
}

private var backgroundStarted = false

/** The same start-up housekeeping as the desktop app, plus the stage-2 credential carry-over. */
private fun startBackgroundWork(koin: Koin) {
    if (backgroundStarted) return
    backgroundStarted = true
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    koin.get<DebugLogController>().start(scope, alwaysVerbose = false)
    scope.launch {
        runCatching { IosEngine.migrateStage2Credentials(koin) }
        runCatching { koin.get<TransferRepository>().recoverOrphanedTransfers() }
        runCatching { koin.get<FileRepository>().sweepImportOrphans() }
        runCatching { koin.get<FolderOwnershipRepair>().refileStrayFiles() }
        koin.get<PublishScheduler>().kick()
        runCatching { koin.get<TrashRepository>().repairTrashTree() }
    }
}
