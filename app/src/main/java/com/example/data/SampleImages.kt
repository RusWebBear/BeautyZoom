package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Environment
import java.io.File

object SampleImages {

    enum class SampleType(
        val id: String,
        val filename: String,
        val label: String,
        val description: String
    ) {
        FEMALE_ARMCHAIR(
            id = "female",
            filename = "female.jpg",
            label = "Девушка в кресле",
            description = "Портрет девушки в кресле (female.jpg)"
        ),
        DUAL_COUPLE(
            id = "both",
            filename = "both.jpg",
            label = "Пара на диване",
            description = "Парный портрет для совместной ретуши (both.jpg)"
        ),
        MALE_ARMCHAIR(
            id = "male",
            filename = "male.jpg",
            label = "Парень в кресле",
            description = "Портрет парня в кресле (male.jpg)"
        )
    }

    private val bitmapCache = mutableMapOf<String, Bitmap>()
    private val thumbnailCache = mutableMapOf<String, Bitmap>()

    /**
     * Loads the real photograph sample.
     * Prioritizes loading high-resolution photos from assets (female.jpg, both.jpg, male.jpg)
     * or user-provided files in app storage.
     */
    fun getSampleBitmap(context: Context? = null, type: SampleType): Bitmap {
        bitmapCache[type.id]?.let { cached ->
            if (!cached.isRecycled) return cached
        }

        // 1. Try loading from assets
        if (context != null) {
            try {
                context.assets.open(type.filename).use { stream ->
                    val options = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    val bmp = BitmapFactory.decodeStream(stream, null, options)
                    if (bmp != null) {
                        bitmapCache[type.id] = bmp
                        return bmp
                    }
                }
            } catch (_: Exception) { }

            // 2. Try loading from app internal storage or cache
            try {
                val internalFile = File(context.filesDir, type.filename)
                if (internalFile.exists() && internalFile.length() > 0) {
                    val bmp = BitmapFactory.decodeFile(internalFile.absolutePath)
                    if (bmp != null) {
                        bitmapCache[type.id] = bmp
                        return bmp
                    }
                }
            } catch (_: Exception) { }

            // 3. Try loading from external storage / downloads
            try {
                val pathsToTry = listOf(
                    File(context.getExternalFilesDir(null), type.filename),
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), type.filename),
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), type.filename),
                    File("/sdcard/Download/${type.filename}"),
                    File("/sdcard/Pictures/${type.filename}"),
                    File("/sdcard/${type.filename}")
                )
                for (file in pathsToTry) {
                    if (file.exists() && file.length() > 0) {
                        val bmp = BitmapFactory.decodeFile(file.absolutePath)
                        if (bmp != null) {
                            bitmapCache[type.id] = bmp
                            return bmp
                        }
                    }
                }
            } catch (_: Exception) { }
        }

        // 4. Clean studio backdrop fallback
        val fallback = createStudioPortraitFallback(type)
        bitmapCache[type.id] = fallback
        return fallback
    }

    /**
     * Generates or retrieves a lightweight thumbnail for the sample modal picker.
     */
    fun getThumbnail(context: Context?, type: SampleType): Bitmap {
        thumbnailCache[type.id]?.let { cached ->
            if (!cached.isRecycled) return cached
        }

        val fullBmp = getSampleBitmap(context, type)
        val targetW = 120
        val targetH = (targetW * (fullBmp.height.toFloat() / fullBmp.width.toFloat())).toInt()
        val thumb = Bitmap.createScaledBitmap(fullBmp, targetW, targetH, true)
        thumbnailCache[type.id] = thumb
        return thumb
    }

    private fun createStudioPortraitFallback(type: SampleType): Bitmap {
        val w = 800
        val h = 1000
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint().apply {
            shader = RadialGradient(
                w * 0.5f, h * 0.4f, w * 0.7f,
                intArrayOf(Color.parseColor("#38384E"), Color.parseColor("#181824")),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            textAlign = Paint.Align.CENTER
        }
        val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00EBFF")
            textSize = 24f
            textAlign = Paint.Align.CENTER
        }

        canvas.drawText(type.label, w * 0.5f, h * 0.48f, textPaint)
        canvas.drawText("Поместите ${type.filename} в папку assets", w * 0.5f, h * 0.54f, subTextPaint)

        return bitmap
    }
}
