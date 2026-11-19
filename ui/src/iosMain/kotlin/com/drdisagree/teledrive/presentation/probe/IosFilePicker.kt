package com.drdisagree.teledrive.presentation.probe

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject

/** Top-most presented controller, to present pickers and share sheets from Compose. */
private fun topController(): UIViewController? {
    @Suppress("DEPRECATION")
    var controller = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (controller?.presentedViewController != null) controller = controller.presentedViewController
    return controller
}

/** Keeps the delegate alive while the picker is on screen (UIKit holds it weakly). */
private var activeDelegate: NSObject? = null

/**
 * Lets the user pick files from the Files app; iOS copies them into the app's tmp inbox
 * (asCopy = true), so the paths are readable without security-scoped access.
 */
@OptIn(ExperimentalForeignApi::class)
fun pickFiles(onPicked: (List<String>) -> Unit) {
    val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeItem), asCopy = true)
    picker.allowsMultipleSelection = true
    val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
        override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
            activeDelegate = null
            onPicked(didPickDocumentsAtURLs.mapNotNull { (it as? NSURL)?.path })
        }

        override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
            activeDelegate = null
        }
    }
    activeDelegate = delegate
    picker.delegate = delegate
    topController()?.presentViewController(picker, animated = true, completion = null)
}

/** Share sheet for a local file (open in another app, save to Files, AirDrop…). */
fun shareFile(path: String) {
    val url = NSURL.fileURLWithPath(path)
    val sheet = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
    topController()?.presentViewController(sheet, animated = true, completion = null)
}
