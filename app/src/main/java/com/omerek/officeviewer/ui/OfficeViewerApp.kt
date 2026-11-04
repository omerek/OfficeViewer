package com.omerek.officeviewer.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun OfficeViewerApp(
    incomingUri: Uri?,
    onIncomingConsumed: () -> Unit,
    vm: ViewerViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()

    LaunchedEffect(incomingUri) {
        if (incomingUri != null) {
            vm.open(incomingUri, persistable = false)
            onIncomingConsumed()
        }
    }

    when (val s = state) {
        ViewerState.Idle -> HomeScreen(
            recent = recent,
            onOpen = { uri -> vm.open(uri, persistable = true) },
            onOpenRecent = { uri -> vm.open(uri, persistable = false) },
            onRemoveRecent = vm::removeRecent,
            onClearRecent = vm::clearRecent,
        )
        else -> {
            BackHandler { vm.close() }
            ViewerScreen(state = s, onBack = vm::close)
        }
    }
}
