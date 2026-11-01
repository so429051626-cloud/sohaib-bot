package com.madix.beam

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.madix.beam.core.IncomingShare
import com.madix.beam.core.appContainer
import com.madix.beam.ui.navigation.MadixRoot
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = appContainer

        applySecureFlag(container.initialSettings.secureScreen)
        if (savedInstanceState == null) {
            container.appLock.lockIfEnabled()
            handleShareIntent(intent)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                container.settings.settings
                    .map { it.appLockEnabled to it.secureScreen }
                    .distinctUntilChanged()
                    .collect { (lock, secure) ->
                        container.appLock.enabled = lock
                        applySecureFlag(secure)
                    }
            }
        }

        setContent { MadixRoot(container) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        appContainer.appLock.onForeground()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) appContainer.appLock.onBackground()
    }

    private fun applySecureFlag(secure: Boolean) {
        if (secure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val container = appContainer
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                when {
                    uri != null -> container.offerShare(IncomingShare(listOf(uri), null))
                    !text.isNullOrBlank() -> container.offerShare(IncomingShare(emptyList(), text))
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.filterNotNull()
                    .orEmpty()
                if (uris.isNotEmpty()) container.offerShare(IncomingShare(uris, null))
            }
        }
    }
}
