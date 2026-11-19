package com.drdisagree.teledrive.presentation.platform

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.drdisagree.teledrive.presentation.preview.IosPreviewScreen
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.ic_launcher
import org.jetbrains.compose.resources.painterResource
import org.koin.compose.koinInject
import platform.Foundation.NSBundle
import platform.UIKit.UIApplicationOpenSettingsURLString

object IosPlatformScreens : PlatformScreens {
    @Composable
    override fun Preview(onBack: () -> Unit, onEditNote: (String, String) -> Unit) {
        IosPreviewScreen(onBack = onBack)
    }
}

/** The iOS counterpart of the desktop's ProvideDesktopPlatformActions. */
@Composable
fun ProvideIosPlatformActions(content: @Composable () -> Unit) {
    val capabilities = koinInject<PlatformCapabilities>()
    val telegramLinkOpener = remember { IosTelegramLinkOpener() }
    val urlOpener = remember { UrlOpener { url -> openExternalUrl(url) } }
    val folderPicker = remember { FolderPicker { onPicked -> presentFolderPicker(onPicked) } }
    val filePicker = remember {
        FilePicker { onPicked ->
            presentFilePicker(multiple = false) { paths ->
                onPicked(paths.firstOrNull()?.let(PickResult::Picked) ?: PickResult.Canceled)
            }
        }
    }
    val multiFilePicker = remember {
        MultiFilePicker { onPicked -> presentFilePicker(multiple = true, onPicked = onPicked) }
    }
    val fileSharer = remember { FileSharer { paths, _, _ -> presentShareSheet(paths) } }
    // Deleting the app's own files needs no system consent on iOS.
    val deleteConsentLauncher = remember { DeleteConsentLauncher { _, onResult -> onResult(true) } }
    val permissionRequester = remember { PermissionRequester { _, onDone -> onDone() } }
    val systemScreens = remember {
        object : SystemScreens {
            override fun openAppSettings() {
                openExternalUrl(UIApplicationOpenSettingsURLString)
            }

            override fun openAllFilesAccess() = Unit
            override fun openBatteryOptimization() = Unit
        }
    }
    // App lock is off until Face ID arrives in stage 5, so nothing calls this gate yet.
    val deviceOwnerGate = remember { DeviceOwnerGate { _, _, _, onConfirmed -> onConfirmed() } }
    val appVersion = remember {
        NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: ""
    }
    val appIcon: @Composable (Modifier) -> Unit = { modifier ->
        Image(
            painter = painterResource(Res.drawable.ic_launcher),
            contentDescription = null,
            modifier = modifier
        )
    }

    CompositionLocalProvider(
        LocalAppIcon provides appIcon,
        LocalUrlOpener provides urlOpener,
        LocalTelegramLinkOpener provides telegramLinkOpener,
        LocalFolderPicker provides folderPicker,
        LocalFilePicker provides filePicker,
        LocalMultiFilePicker provides multiFilePicker,
        LocalFileSharer provides fileSharer,
        LocalDeleteConsentLauncher provides deleteConsentLauncher,
        LocalPermissionRequester provides permissionRequester,
        LocalSystemScreens provides systemScreens,
        LocalDeviceOwnerGate provides deviceOwnerGate,
        LocalAppVersion provides appVersion,
        // Downloads always go to Documents, which the Files app shows as On My iPhone › Strato.
        LocalDownloadLocationConfigurable provides false,
        LocalPlatformScreens provides IosPlatformScreens,
        LocalFileRevealer provides null,
        LocalPlatformCapabilities provides capabilities,
        content = content
    )
}
