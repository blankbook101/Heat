package com.heat.app.core

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class HeatFile(val id: Long, val uri: Uri, val name: String, val size: Long)

/**
 * "Burning" never deletes anything: files are moved from Downloads/Heat to
 * Downloads/Heat/Recently Burned (and can be moved back). Files Heat saved itself are owned
 * by the app, so MediaStore lets us move them without extra permissions.
 */
class Burner(private val ctx: Context) {
    companion object {
        const val DIR = "Download/Heat/"
        const val BURNED = "Download/Heat/Recently Burned/"
    }

    private val base: Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    fun list(burned: Boolean): List<HeatFile> {
        val out = ArrayList<HeatFile>()
        try {
            ctx.contentResolver.query(
                base,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE
                ),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(if (burned) BURNED else DIR),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out.add(HeatFile(id, ContentUris.withAppendedId(base, id), c.getString(1) ?: "file", c.getLong(2)))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** Really deletes the files (no way back). Returns how many were removed. */
    fun delete(files: List<HeatFile>): Int {
        var ok = 0
        for (f in files) {
            try {
                if (ctx.contentResolver.delete(f.uri, null, null) > 0) ok++
            } catch (_: Exception) {
            }
        }
        return ok
    }

    /** Moves the files into (toBurned = true) or out of the Recently Burned folder. Returns how many moved. */
    fun move(files: List<HeatFile>, toBurned: Boolean): Int {
        var ok = 0
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (toBurned) BURNED else DIR)
        }
        for (f in files) {
            try {
                if (ctx.contentResolver.update(f.uri, v, null, null) > 0) ok++
            } catch (_: Exception) {
            }
        }
        return ok
    }
}
