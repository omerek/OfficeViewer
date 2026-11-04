package com.omerek.officeviewer.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omerek.officeviewer.R
import com.omerek.officeviewer.core.DocumentFamily
import com.omerek.officeviewer.core.DocumentFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(state: ViewerState, onBack: () -> Unit) {
    val context = LocalContext.current
    val handle = remember { WebViewHandle() }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var active by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }

    DisposableEffect(handle) {
        handle.onFindResult = { a, t, _ -> active = a; total = t }
        onDispose { handle.onFindResult = null }
    }

    val title = when (state) {
        is ViewerState.Loading -> state.name
        is ViewerState.Ready -> state.info.name
        is ViewerState.Failed -> state.name
        ViewerState.Idle -> ""
    }

    Scaffold(
        topBar = {
            if (searching) {
                SearchBar(
                    query = query,
                    active = active,
                    total = total,
                    onQuery = { query = it; handle.find(it) },
                    onNext = { handle.findNext(true) },
                    onPrevious = { handle.findNext(false) },
                    onClose = { searching = false; query = ""; handle.clearFind(); total = 0 },
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    title = {
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            if (state is ViewerState.Ready && state.sections > 0) {
                                Text(
                                    sectionLabel(state.format, state.sections),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = {
                        if (state is ViewerState.Ready) {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search))
                            }
                            IconButton(onClick = { shareDocument(context, state.uri, state.info.mime, context.getString(R.string.share)) }) {
                                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share))
                            }
                            Box {
                                IconButton(onClick = { menu = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.print)) },
                                        onClick = { menu = false; handle.print(context, state.info.name) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.open_with)) },
                                        onClick = {
                                            menu = false
                                            if (!openWith(context, state.uri, state.info.mime, context.getString(R.string.open_with))) {
                                                Toast.makeText(context, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                is ViewerState.Loading, ViewerState.Idle -> Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.loading))
                }
                is ViewerState.Failed -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.error_open, state.message), textAlign = TextAlign.Center)
                }
                is ViewerState.Ready -> {
                    DocumentWebView(html = state.html, handle = handle, modifier = Modifier.fillMaxSize())
                    if (state.largeFileWarning) {
                        LaunchedEffect(state.uri) {
                            val size = android.text.format.Formatter.formatShortFileSize(context, state.info.size)
                            Toast.makeText(context, context.getString(R.string.file_too_large, size), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun sectionLabel(format: DocumentFormat, n: Int): String = when (format.family) {
    DocumentFamily.EXCEL -> stringResource(R.string.sheets_count, n)
    DocumentFamily.POWERPOINT -> stringResource(R.string.slides_count, n)
    else -> stringResource(R.string.pages_count, n)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    query: String,
    active: Int,
    total: Int,
    onQuery: (String) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close_search)) }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onNext() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = {
            if (query.isNotEmpty()) {
                Text(
                    if (total == 0) stringResource(R.string.no_matches) else stringResource(R.string.match_count, active, total),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            IconButton(onClick = onPrevious, enabled = total > 0) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.search_previous))
            }
            IconButton(onClick = onNext, enabled = total > 0) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.search_next))
            }
        },
    )
}
