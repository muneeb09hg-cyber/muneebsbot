package com.muneeb.autotrader

import android.graphics.Bitmap
import android.media.ImageReader

/** स्क्रीन कैप्चर का ताज़ा फ़्रेम यहाँ से मिलता है। */
object CaptureHolder {
    @Volatile var running = false
    @Volatile var reader: ImageReader? = null
    private var last: Bitmap? = null

    // स्क्रीन स्थिर हो तो नया फ़्रेम नहीं आता, इसलिए पिछला फ़्रेम संभालकर रखते हैं
    @Synchronized
    fun grab(): Bitmap? {
        val r = reader ?: return last
        val img = try {
            r.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        if (img != null) {
            try {
                val plane = img.planes[0]
                val rowPadding = plane.rowStride - plane.pixelStride * img.width
                val bmp = Bitmap.createBitmap(
                    img.width + rowPadding / plane.pixelStride,
                    img.height,
                    Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(plane.buffer)
                last = bmp
            } finally {
                img.close()
            }
        }
        return last
    }
}
