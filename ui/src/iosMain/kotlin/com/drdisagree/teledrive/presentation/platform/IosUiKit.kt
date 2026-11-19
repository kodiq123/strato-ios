package com.drdisagree.teledrive.presentation.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.QuickLook.QLPreviewController
import platform.QuickLook.QLPreviewControllerDataSourceProtocol
import platform.QuickLook.QLPreviewItemProtocol
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSInteger
import platform.darwin.NSObject

/** Top-most presented controller, to present pickers and sheets from Compose. */
internal fun topController(): UIViewController? {
    @Suppress("DEPRECATION")
    var controller = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (controller?.presentedViewController != null) controller = controller.presentedViewController
    return controller
}

/** UIKit holds delegates and data sources weakly; this keeps the current one alive. */
private var retainedDelegate: NSObject? = null

/**
 * Picks files from the Files app. iOS copies them into the app's tmp inbox (asCopy = true), so the
 * paths stay readable without security-scoped access. Cancel reports an empty list.
 */
internal fun presentFilePicker(multiple: Boolean, onPicked: (List<String>) -> Unit) {
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = true)
    picker.allowsMultipleSelection = multiple
    presentPicker(picker) { urls -> onPicked(urls.mapNotNull { it.path }) }
}

/**
 * Picks a folder in place. Access to it is security-scoped; it is started here and kept for the
 * rest of the session, because uploads read the folder long after the picker closed.
 */
internal fun presentFolderPicker(onPicked: (PickResult) -> Unit) {
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeFolder))
    picker.allowsMultipleSelection = false
    presentPicker(picker) { urls ->
        val url = urls.firstOrNull()
        onPicked(
            when {
                url == null -> PickResult.Canceled
                !url.startAccessingSecurityScopedResource() -> PickResult.Unreadable
                else -> url.path?.let(PickResult::Picked) ?: PickResult.Unreadable
            }
        )
    }
}

private fun presentPicker(picker: UIDocumentPickerViewController, onResult: (List<NSURL>) -> Unit) {
    val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            retainedDelegate = null
            onResult(didPickDocumentsAtURLs.filterIsInstance<NSURL>())
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            retainedDelegate = null
            onResult(emptyList())
        }
    }
    retainedDelegate = delegate
    picker.delegate = delegate
    topController()?.presentViewController(picker, animated = true, completion = null)
        ?: onResult(emptyList())
}

/** Share sheet for local files: open in another app, save to Files, AirDrop and so on. */
internal fun presentShareSheet(paths: List<String>): Boolean {
    if (paths.isEmpty()) return false
    val items = paths.map { NSURL.fileURLWithPath(it) }
    val sheet = UIActivityViewController(activityItems = items, applicationActivities = null)
    val presenter = topController() ?: return false
    // On iPad the sheet is a popover and needs an anchor.
    sheet.popoverPresentationController?.sourceView = presenter.view
    presenter.presentViewController(sheet, animated = true, completion = null)
    return true
}

internal fun openExternalUrl(link: String): Boolean {
    val url = NSURL.URLWithString(link) ?: return false
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    return true
}

internal fun canOpenExternalUrl(link: String): Boolean {
    val url = NSURL.URLWithString(link) ?: return false
    return UIApplication.sharedApplication.canOpenURL(url)
}

/**
 * QuickLook shows PDFs, video, audio, images and Office documents with the system viewer, so the
 * app needs no player of its own for the local copies.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun presentQuickLook(path: String): Boolean {
    val url = NSURL.fileURLWithPath(path)
    val item = object : NSObject(), QLPreviewItemProtocol {
        override fun previewItemURL(): NSURL = url
    }
    if (!QLPreviewController.canPreviewItem(item)) return false
    val presenter = topController() ?: return false
    val dataSource = object : NSObject(), QLPreviewControllerDataSourceProtocol {
        override fun numberOfPreviewItemsInPreviewController(controller: QLPreviewController): NSInteger = 1

        override fun previewController(
            controller: QLPreviewController,
            previewItemAtIndex: NSInteger
        ): QLPreviewItemProtocol = item
    }
    retainedDelegate = dataSource
    val controller = QLPreviewController()
    controller.dataSource = dataSource
    presenter.presentViewController(controller, animated = true, completion = null)
    return true
}
