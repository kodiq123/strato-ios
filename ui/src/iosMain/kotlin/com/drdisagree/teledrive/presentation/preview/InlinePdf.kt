package com.drdisagree.teledrive.presentation.preview

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.drdisagree.teledrive.presentation.components.EmptyState
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.preview_pdf_open_failed
import kotlinx.cinterop.ExperimentalForeignApi
import org.jetbrains.compose.resources.stringResource
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument
import platform.PDFKit.PDFView
import platform.PDFKit.kPDFDisplaySinglePageContinuous
import platform.UIKit.UIColor
import platform.UIKit.systemGroupedBackgroundColor

/**
 * Renders the PDF inside the preview, like Android's in-app viewer: PDFKit pages in a continuous
 * vertical scroll with pinch zoom, and text that can be selected and copied.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal fun InlinePdf(path: String, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val document = remember(path) { PDFDocument(uRL = NSURL.fileURLWithPath(path)) }
    val tapRelay = remember { TapRelay(onTap) }
    tapRelay.onTap = onTap
    if (document == null) {
        EmptyState(
            icon = Icons.Filled.PictureAsPdf,
            title = stringResource(Res.string.preview_pdf_open_failed),
            modifier = modifier.fillMaxSize()
        )
        return
    }
    UIKitView(
        factory = {
            PDFView().apply {
                displayMode = kPDFDisplaySinglePageContinuous
                displaysPageBreaks = true
                autoScales = true
                backgroundColor = UIColor.systemGroupedBackgroundColor
                this.document = document
                tapRelay.attachTo(this)
            }
        },
        modifier = modifier.fillMaxSize(),
        update = { view ->
            if (view.document != document) view.document = document
        }
    )
}
