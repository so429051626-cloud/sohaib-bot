package com.madix.beam

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.madix.beam.core.AppContainer
import com.madix.beam.data.settings.AppSettings
import com.madix.beam.data.settings.SettingsRepository
import com.madix.beam.data.settings.ThemeMode
import kotlinx.coroutines.runBlocking

class MadixApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Settings are tiny; reading them synchronously lets the first frame use the right theme.
        val initial = runCatching { runBlocking { SettingsRepository(this@MadixApp).current() } }
            .getOrDefault(AppSettings())
        applyThemeMode(initial.themeMode)
        container = AppContainer(this, initial)
        container.files.cleanTemporaryFiles()
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()

    companion object {
        fun applyThemeMode(mode: ThemeMode) {
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                    ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                },
            )
        }
    }
}
