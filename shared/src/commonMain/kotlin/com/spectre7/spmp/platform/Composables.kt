package com.spectre7.spmp.platform

import androidx.compose.runtime.Composable

@Composable
expect fun BackHandler(enabled: Boolean = true, action: () -> Unit)