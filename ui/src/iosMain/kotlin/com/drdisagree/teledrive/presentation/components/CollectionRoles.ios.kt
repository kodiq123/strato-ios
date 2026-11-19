package com.drdisagree.teledrive.presentation.components

import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription

actual fun SemanticsPropertyReceiver.collectionRole(name: String, grid: Boolean) {
    contentDescription = name
}

actual fun SemanticsPropertyReceiver.tileRole() {
}
