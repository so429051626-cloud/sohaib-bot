package com.madix.beam.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.text.format.DateUtils
import android.text.format.Formatter
import com.madix.beam.R

/** Locale-aware formatting helpers. */
object Format {

    fun bytes(context: Context, bytes: Long): String =
        Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0))

    fun speed(context: Context, bytesPerSecond: Double): String =
        context.getString(R.string.format_speed, bytes(context, bytesPerSecond.toLong()))

    /** `m:ss` or `h:mm:ss`, localised digits. */
    fun duration(seconds: Long): String = DateUtils.formatElapsedTime(seconds.coerceAtLeast(0))

    fun dateTime(context: Context, millis: Long): String = DateUtils.formatDateTime(
        context,
        millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )

    fun relative(millis: Long): String = DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()

    fun percent(fraction: Float): String = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%"
}

/** Walks the context chain to the hosting activity. */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
