package com.madix.beam.ui.components

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.madix.beam.R
import com.madix.beam.data.storage.ReceivedFileStore
import kotlinx.coroutines.launch
import java.io.File

/** A stored file together with the metadata needed to hand it to other apps. */
data class StoredFile(val file: File, val name: String, val mimeType: String)

object FileActions {

    fun resolveMime(name: String, declared: String): String {
        if (declared.isNotBlank() && declared != "application/octet-stream") return declared
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    fun open(context: Context, store: ReceivedFileStore, item: StoredFile) {
        val uri = store.uriFor(item.file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, resolveMime(item.name, item.mimeType))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.toast_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context, store: ReceivedFileStore, items: List<StoredFile>) {
        if (items.isEmpty()) return
        val uris = ArrayList<Uri>(items.map { store.uriFor(it.file) })
        val mimes = items.map { resolveMime(it.name, it.mimeType) }.distinct()
        val type = when {
            mimes.size == 1 -> mimes.first()
            mimes.all { it.substringBefore('/') == mimes.first().substringBefore('/') } -> mimes.first().substringBefore('/') + "/*"
            else -> "*/*"
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.type = type
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val clip = ClipData.newUri(context.contentResolver, items.first().name, uris.first())
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        launchChooser(context, intent)
    }

    fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        launchChooser(context, intent)
    }

    fun copyText(context: Context, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.toast_copied, Toast.LENGTH_SHORT).show()
        }
    }

    fun openLink(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.toast_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchChooser(context: Context, intent: Intent) {
        try {
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_share)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.toast_no_app, Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Returns a function that copies files into the shared device storage, requesting the legacy
 * storage permission on Android 9 and earlier.
 */
@Composable
fun rememberDeviceExporter(store: ReceivedFileStore): (List<StoredFile>) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = remember { mutableListOf<StoredFile>() }

    fun export(items: List<StoredFile>) {
        scope.launch {
            var saved = 0
            for (item in items) {
                runCatching { store.exportToDevice(item.file, item.name, FileActions.resolveMime(item.name, item.mimeType)) }
                    .onSuccess { saved++ }
            }
            val message = if (saved == items.size) {
                context.resources.getQuantityString(R.plurals.toast_saved, saved, saved)
            } else {
                context.getString(R.string.toast_save_failed)
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val items = pending.toList()
        pending.clear()
        if (granted) {
            export(items)
        } else {
            Toast.makeText(context, R.string.toast_storage_permission, Toast.LENGTH_LONG).show()
        }
    }

    return { items ->
        if (items.isNotEmpty()) {
            val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
            if (needsPermission) {
                pending.clear()
                pending.addAll(items)
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                export(items)
            }
        }
    }
}
