package com.drdisagree.teledrive.presentation.preview

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.ObjCSignatureOverride
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIGestureRecognizer
import platform.UIKit.UIGestureRecognizerDelegateProtocol
import platform.UIKit.UITapGestureRecognizer
import platform.UIKit.UIView
import platform.darwin.NSObject

/**
 * Reports single taps on a UIKit view (PDFView, the player) back to Compose without taking them
 * away from the view: its own gestures (zoom, scroll, player controls) keep working.
 *
 * UIKit holds gesture targets weakly, so the caller keeps the relay alive as long as the view.
 */
internal class TapRelay(var onTap: () -> Unit) : NSObject(), UIGestureRecognizerDelegateProtocol {

    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    fun attachTo(view: UIView) {
        val tap = UITapGestureRecognizer(target = this, action = NSSelectorFromString("handleTap:"))
        tap.cancelsTouchesInView = false
        tap.delegate = this
        // A double tap zooms in PDFView; only a lone tap toggles the bars.
        view.gestureRecognizers
            ?.filterIsInstance<UITapGestureRecognizer>()
            ?.filter { it.numberOfTapsRequired > 1uL }
            ?.forEach { tap.requireGestureRecognizerToFail(it) }
        view.addGestureRecognizer(tap)
    }

    @OptIn(BetaInteropApi::class)
    @ObjCAction
    fun handleTap(sender: UITapGestureRecognizer) {
        onTap()
    }

    @ObjCSignatureOverride
    override fun gestureRecognizer(
        gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWithGestureRecognizer: UIGestureRecognizer
    ): Boolean = true
}
