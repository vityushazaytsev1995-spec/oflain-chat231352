package com.gonets.messenger.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

object QrUtils {
    fun generateQrBitmap(content: String, size: Int = 700): Bitmap? {
        return try {
            val bitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (bitMatrix.get(x,y)) Color.BLACK else Color.WHITE)
            bmp
        } catch (e: Exception) { null }
    }
    fun createQrContent(deviceId: String, name: String): String = "GONETS|$deviceId|$name"
    fun parseQrContent(content: String): Pair<String, String>? {
        return try {
            if (!content.startsWith("GONETS|") && !content.startsWith("OFFLINE_MESH|")) return null
            val parts = content.split("|")
            if (parts.size < 3) return null
            Pair(parts[1], parts[2])
        } catch (e: Exception) { null }
    }
}
