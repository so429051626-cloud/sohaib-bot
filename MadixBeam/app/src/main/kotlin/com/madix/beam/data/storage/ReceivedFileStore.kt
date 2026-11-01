package com.madix.beam.data.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.madix.beam.protocol.container.FileNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns every file Madix Beam writes. Received files live in private internal storage and
 * are only exposed through explicit user actions (open, share, export).
 */
class ReceivedFileStore(private val context: Context) {

    val root: File = File(context.filesDir, "received").apply { mkdirs() }

    val outgoingWorkDir: File get() = File(context.cacheDir, "outgoing").apply { mkdirs() }
    val incomingWorkDir: File get() = File(context.cacheDir, "incoming").apply { mkdirs() }
    private val shareDir: File get() = File(context.cacheDir, "shared").apply { mkdirs() }

    private val authority = "${context.packageName}.fileprovider"
    private val random = SecureRandom()

    /** Removes leftovers of interrupted transfers. */
    fun cleanTemporaryFiles() {
        File(context.cacheDir, "outgoing").deleteRecursively()
        File(context.cacheDir, "incoming").deleteRecursively()
        File(context.cacheDir, "shared").deleteRecursively()
    }

    fun createTransferDirectory(): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val suffix = Integer.toHexString(random.nextInt() and 0xFFFF)
        return File(root, "$stamp-$suffix").apply { mkdirs() }
    }

    fun relativePath(file: File): String = file.canonicalFile.relativeTo(root.canonicalFile).path

    /** Resolves a stored relative path, refusing anything outside [root]. */
    fun resolve(relativePath: String): File? {
        val base = root.canonicalFile
        val candidate = File(base, relativePath).canonicalFile
        if (!candidate.path.startsWith(base.path + File.separator)) return null
        return candidate.takeIf { it.isFile }
    }

    fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, authority, file)

    /** Writes text to a temporary shareable file. */
    fun shareableText(text: String, name: String): File {
        val file = File(shareDir, FileNames.sanitize(name))
        file.writeText(text)
        return file
    }

    fun totalSize(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun deleteAll() {
        root.listFiles()?.forEach { it.deleteRecursively() }
    }

    fun delete(relativePaths: List<String>) {
        val parents = mutableSetOf<File>()
        for (path in relativePaths) {
            val file = resolve(path) ?: continue
            file.parentFile?.let(parents::add)
            file.delete()
        }
        for (dir in parents) {
            if (dir.canonicalFile != root.canonicalFile && dir.listFiles().isNullOrEmpty()) dir.delete()
        }
    }

    /**
     * Copies [file] into the shared device storage: photos to Pictures, videos to Movies,
     * audio to Music and everything else to Downloads, inside a "Madix Beam" folder.
     * On Android 9 and earlier the caller must hold WRITE_EXTERNAL_STORAGE.
     */
    suspend fun exportToDevice(file: File, displayName: String, mimeType: String): Uri = withContext(Dispatchers.IO) {
        val name = FileNames.sanitize(displayName)
        val mime = mimeType.ifBlank { "application/octet-stream" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                insertIntoMediaStore(file, name, mime, preferred = true)
            } catch (e: Exception) {
                // Some collections reject uncommon MIME types; Downloads accepts every file.
                insertIntoMediaStore(file, name, mime, preferred = false)
            }
        } else {
            exportLegacy(file, name, mime)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun insertIntoMediaStore(file: File, name: String, mime: String, preferred: Boolean): Uri {
        val resolver = context.contentResolver
        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        val (collection, directory) = when {
            preferred && mime.startsWith("image/") ->
                MediaStore.Images.Media.getContentUri(volume) to Environment.DIRECTORY_PICTURES
            preferred && mime.startsWith("video/") ->
                MediaStore.Video.Media.getContentUri(volume) to Environment.DIRECTORY_MOVIES
            preferred && mime.startsWith("audio/") ->
                MediaStore.Audio.Media.getContentUri(volume) to Environment.DIRECTORY_MUSIC
            else -> MediaStore.Downloads.getContentUri(volume) to Environment.DIRECTORY_DOWNLOADS
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 64 * 1024) } }
                ?: throw IOException("Cannot open destination")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun exportLegacy(file: File, name: String, mime: String): Uri {
        val type = when {
            mime.startsWith("image/") -> Environment.DIRECTORY_PICTURES
            mime.startsWith("video/") -> Environment.DIRECTORY_MOVIES
            mime.startsWith("audio/") -> Environment.DIRECTORY_MUSIC
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        val directory = File(Environment.getExternalStoragePublicDirectory(type), FOLDER)
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Cannot create $directory")
        val target = FileNames.unique(directory, name)
        file.inputStream().use { input -> target.outputStream().use { input.copyTo(it, 64 * 1024) } }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        return Uri.fromFile(target)
    }

    companion object {
        const val FOLDER = "Madix Beam"
    }
}
