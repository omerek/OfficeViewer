package com.omerek.officeviewer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.omerek.officeviewer.core.DocumentFormat
import com.omerek.officeviewer.data.DocumentLoader
import com.omerek.officeviewer.data.FileInfo
import com.omerek.officeviewer.data.RecentFile
import com.omerek.officeviewer.data.RecentFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ViewerState {
    data object Idle : ViewerState
    data class Loading(val name: String) : ViewerState
    data class Ready(
        val uri: Uri,
        val info: FileInfo,
        val html: String,
        val format: DocumentFormat,
        val sections: Int,
        val largeFileWarning: Boolean,
    ) : ViewerState
    data class Failed(val name: String, val message: String) : ViewerState
}

class ViewerViewModel(app: Application) : AndroidViewModel(app) {
    private val recentStore = RecentFiles(app)

    private val _state = MutableStateFlow<ViewerState>(ViewerState.Idle)
    val state: StateFlow<ViewerState> = _state

    private val _recent = MutableStateFlow(recentStore.load())
    val recent: StateFlow<List<RecentFile>> = _recent

    private var job: Job? = null

    /**
     * Opens a document. [persistable] is true for URIs from the system file picker, which can
     * keep their read permission so the file can be reopened from the recent list.
     */
    fun open(uri: Uri, persistable: Boolean) {
        val ctx = getApplication<Application>()
        if (persistable) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        job?.cancel()
        job = viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { DocumentLoader.info(ctx, uri) }
            _state.value = ViewerState.Loading(info.name)
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val bytes = withContext(Dispatchers.IO) { DocumentLoader.read(ctx, uri) }
                    bytes.size.toLong() to DocumentLoader.render(ctx, bytes, info)
                }
            }
            result.onSuccess { (size, r) ->
                _state.value = ViewerState.Ready(uri, info, r.html, r.format, r.sectionCount, size > DocumentLoader.WARN_BYTES)
                if (hasPersistedGrant(uri)) {
                    _recent.value = recentStore.add(RecentFile(uri, info.name, System.currentTimeMillis(), size))
                }
            }.onFailure { e ->
                _state.value = ViewerState.Failed(info.name, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun close() {
        job?.cancel()
        _state.value = ViewerState.Idle
    }

    fun removeRecent(uri: Uri) {
        _recent.value = recentStore.remove(uri)
        runCatching { getApplication<Application>().contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun clearRecent() {
        _recent.value.forEach { f ->
            runCatching { getApplication<Application>().contentResolver.releasePersistableUriPermission(f.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        _recent.value = recentStore.clear()
    }

    private fun hasPersistedGrant(uri: Uri): Boolean =
        getApplication<Application>().contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
}
