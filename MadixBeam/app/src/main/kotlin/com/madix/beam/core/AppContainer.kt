package com.madix.beam.core

import android.content.Context
import android.net.Uri
import com.madix.beam.MadixApp
import com.madix.beam.data.history.HistoryRepository
import com.madix.beam.data.settings.AppSettings
import com.madix.beam.data.settings.SettingsRepository
import com.madix.beam.data.storage.ReceivedFileStore
import com.madix.beam.security.AppLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Content shared into Madix Beam from another application. */
data class IncomingShare(val uris: List<Uri>, val text: String?)

/** Manual dependency container; one instance per process. */
class AppContainer(context: Context, val initialSettings: AppSettings) {
    val appContext: Context = context.applicationContext
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(appContext)
    val history = HistoryRepository(appContext, applicationScope)
    val files = ReceivedFileStore(appContext)
    val appLock = AppLock().apply { enabled = initialSettings.appLockEnabled }

    private val pendingShareState = MutableStateFlow<IncomingShare?>(null)
    val pendingShare: StateFlow<IncomingShare?> = pendingShareState.asStateFlow()

    fun offerShare(share: IncomingShare) {
        pendingShareState.value = share
    }

    fun consumeShare(): IncomingShare? {
        val share = pendingShareState.value
        pendingShareState.value = null
        return share
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as MadixApp).container
