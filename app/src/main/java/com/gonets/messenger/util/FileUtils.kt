package com.gonets.messenger.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

object FileUtils {
    fun uriToFile(context: Context, uri: Uri): File? {
        return try {
            val name = getFileName(context, uri) ?: "file_${System.currentTimeMillis()}"
            val file = File(context.cacheDir, name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            file
        } catch (e: Exception) { null }
    }
    fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >=0) name = cursor.getString(idx)
        }
        return name
    }
    fun getMimeType(context: Context, uri: Uri): String = context.contentResolver.getType(uri) ?: "application/octet-stream"
}
