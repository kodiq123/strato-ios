package com.drdisagree.teledrive.presentation.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Drag and drop from other apps (iPad) comes with the share extension in stage 5.
@Composable
actual fun FileDropArea(
    onDropped: (List<String>) -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier) { content() }
}
