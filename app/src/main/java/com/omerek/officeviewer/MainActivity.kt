package com.omerek.officeviewer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.omerek.officeviewer.ui.OfficeViewerApp
import com.omerek.officeviewer.ui.theme.OfficeViewerTheme

class MainActivity : ComponentActivity() {

    /** A document handed to us by another app ("Open with" / "Share"). */
    private val incoming = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) incoming.value = intent.documentUri()
        setContent {
            OfficeViewerTheme {
                OfficeViewerApp(
                    incomingUri = incoming.value,
                    onIncomingConsumed = { incoming.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.documentUri()?.let { incoming.value = it }
    }

    private fun Intent.documentUri(): Uri? = when (action) {
        Intent.ACTION_VIEW -> data
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_STREAM)
        }
        else -> null
    }
}
