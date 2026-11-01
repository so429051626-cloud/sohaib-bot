package com.madix.beam.data.source

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import com.madix.beam.data.settings.ImageQuality
import com.madix.beam.protocol.container.EntryKind
import com.madix.beam.protocol.container.TextSource
import com.madix.beam.protocol.container.TransferSource
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/** An item selected for sending. */
data class PickedItem(
    val id: Long,
    val uri: Uri?,
    val text: String?,
    val name: String,
    val mimeType: String,
    val size: Long,
) {
    val isText: Boolean get() = text != null
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isVideo: Boolean get() = mimeType.startsWith("video/")

    companion object {
        private val ids = AtomicLong(1)
        fun nextId(): Long = ids.getAndIncrement()
    }
}

object SourceResolver {

    /** Reads name, size and MIME type of a content URI. */
    fun describe(resolver: ContentResolver, uri: Uri): PickedItem {
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        val resolvedName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val extension = resolvedName.substringAfterLast('.', "").lowercase()
        val mime = resolver.getType(uri)?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
        return PickedItem(PickedItem.nextId(), uri, null, resolvedName, mime, size)
    }

    fun textItem(text: String, name: String): PickedItem =
        PickedItem(PickedItem.nextId(), null, text, name, "text/plain", text.toByteArray(Charsets.UTF_8).size.toLong())

    fun toSource(context: Context, item: PickedItem, quality: ImageQuality): TransferSource =
        if (item.text != null) {
            TextSource(item.text, item.name)
        } else {
            UriTransferSource(context, requireNotNull(item.uri), item.name, item.mimeType, item.size, quality)
        }
}

/** Streams a content URI, optionally re-encoding photos to reduce transfer time. */
class UriTransferSource(
    private val context: Context,
    private val uri: Uri,
    name: String,
    mime: String,
    private val originalSize: Long,
    private val quality: ImageQuality,
) : TransferSource {

    private var currentName = name
    private var currentMime = mime

    override val displayName: String get() = currentName
    override val mimeType: String get() = currentMime
    override val kind: EntryKind = EntryKind.FILE

    override fun openStream(): InputStream {
        if (quality != ImageQuality.ORIGINAL && ImageOptimizer.canOptimize(currentMime)) {
            val optimized = runCatching { ImageOptimizer.optimize(context.contentResolver, uri, quality, originalSize) }.getOrNull()
            if (optimized != null) {
                currentName = currentName.substringBeforeLast('.', currentName) + ".webp"
                currentMime = "image/webp"
                return optimized.inputStream()
            }
        }
        return context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
    }
}

/**
 * Downscales and re-encodes photos as lossy WebP, which is markedly smaller than JPEG at equal
 * visual quality. Re-encoding also drops embedded metadata such as the capture location.
 * Returns null when the result would not be smaller than the original.
 */
object ImageOptimizer {
    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    private val OPTIMIZABLE = setOf("image/jpeg", "image/jpg", "image/pjpeg", "image/heic", "image/heif", "image/webp")

    fun canOptimize(mime: String): Boolean = mime.lowercase() in OPTIMIZABLE

    fun optimize(resolver: ContentResolver, uri: Uri, quality: ImageQuality, originalSize: Long): ByteArray? {
        if (quality == ImageQuality.ORIGINAL) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val longest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= quality.maxDimension) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null

        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val scale = min(1f, quality.maxDimension.toFloat() / max(decoded.width, decoded.height))
        val matrix = Matrix()
        if (scale < 1f) matrix.postScale(scale, scale)
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
        }
        val output = if (matrix.isIdentity) {
            decoded
        } else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }
        val bytes = ByteArrayOutputStream().use { stream ->
            output.compress(webpFormat(), quality.quality, stream)
            stream.toByteArray()
        }
        if (output !== decoded) output.recycle()
        decoded.recycle()
        if (originalSize in 1..bytes.size.toLong()) return null
        return bytes
    }
}
