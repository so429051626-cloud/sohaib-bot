package com.madix.beam.data.settings

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.madix.beam.protocol.frame.SecurityMode
import com.madix.beam.protocol.session.TransferProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Photo re-encoding applied before sending. */
enum class ImageQuality(val maxDimension: Int, val jpegQuality: Int) {
    ORIGINAL(0, 100),
    HIGH(2560, 90),
    BALANCED(1920, 82),
    COMPACT(1280, 74),
}

data class AppSettings(
    val onboardingDone: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val appLockEnabled: Boolean = false,
    val secureScreen: Boolean = true,
    val defaultSecurity: SecurityMode = SecurityMode.PAIRED,
    val profile: TransferProfile = TransferProfile.DEFAULT,
    val imageQuality: ImageQuality = ImageQuality.BALANCED,
    val maxBrightness: Boolean = true,
    val haptics: Boolean = true,
    val deviceName: String = "",
)

class SettingsRepository(private val context: Context) {

    private val store = context.settingsDataStore

    val settings: Flow<AppSettings> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map(::read)

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { preferences -> write(preferences, transform(read(preferences))) }
    }

    /** Name announced to the other device; falls back to the system device name. */
    fun effectiveDeviceName(settings: AppSettings): String =
        settings.deviceName.trim().ifEmpty { systemDeviceName() }

    fun systemDeviceName(): String {
        val configured = runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
        if (!configured.isNullOrBlank()) return configured.trim()
        val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        return if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model".trim()
    }

    private fun read(p: Preferences) = AppSettings(
        onboardingDone = p[Keys.ONBOARDING] ?: false,
        themeMode = enumOrDefault(p[Keys.THEME], ThemeMode.SYSTEM),
        appLockEnabled = p[Keys.APP_LOCK] ?: false,
        secureScreen = p[Keys.SECURE_SCREEN] ?: true,
        defaultSecurity = enumOrDefault(p[Keys.SECURITY], SecurityMode.PAIRED),
        profile = TransferProfile.fromId(p[Keys.PROFILE]),
        imageQuality = enumOrDefault(p[Keys.IMAGE_QUALITY], ImageQuality.BALANCED),
        maxBrightness = p[Keys.MAX_BRIGHTNESS] ?: true,
        haptics = p[Keys.HAPTICS] ?: true,
        deviceName = p[Keys.DEVICE_NAME] ?: "",
    )

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[Keys.ONBOARDING] = s.onboardingDone
        p[Keys.THEME] = s.themeMode.name
        p[Keys.APP_LOCK] = s.appLockEnabled
        p[Keys.SECURE_SCREEN] = s.secureScreen
        p[Keys.SECURITY] = s.defaultSecurity.name
        p[Keys.PROFILE] = s.profile.id
        p[Keys.IMAGE_QUALITY] = s.imageQuality.name
        p[Keys.MAX_BRIGHTNESS] = s.maxBrightness
        p[Keys.HAPTICS] = s.haptics
        p[Keys.DEVICE_NAME] = s.deviceName.take(40)
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default

    private object Keys {
        val ONBOARDING = booleanPreferencesKey("onboarding_done")
        val THEME = stringPreferencesKey("theme_mode")
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val SECURE_SCREEN = booleanPreferencesKey("secure_screen")
        val SECURITY = stringPreferencesKey("default_security")
        val PROFILE = stringPreferencesKey("profile")
        val IMAGE_QUALITY = stringPreferencesKey("image_quality")
        val MAX_BRIGHTNESS = booleanPreferencesKey("max_brightness")
        val HAPTICS = booleanPreferencesKey("haptics")
        val DEVICE_NAME = stringPreferencesKey("device_name")
    }
}
