package com.drdisagree.teledrive.presentation.common

import androidx.compose.runtime.Composable

// Compose on iOS reports the keyboard through WindowInsets.ime, which the screens already use.
@Composable
actual fun imeTargetBottomInset(): Int = 0
