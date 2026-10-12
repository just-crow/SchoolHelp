package com.schoolhelp.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ImageStore(private val context: Context) {
    fun clearSessionPhotos() {
        File(context.filesDir, "session/images").listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }

    fun captureFile() = File(context.filesDir, "session/images/capture-${java.util.UUID.randomUUID()}.jpg").apply {
        parentFile?.mkdirs()
    }

    private suspend fun prepare(block: () -> File): File {
        var result: File? = null
        try { return withContext(Dispatchers.IO) { block().also { result = it } } }
        catch (e: Throwable) { result?.delete(); throw e }
    }

    suspend fun import(uri: Uri): File = prepare {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This image could not be opened. Choose a JPEG or PNG photo." }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("This image could not be opened.")
        val orientation = resolver.openInputStream(uri)?.use {
            runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1)
        } ?: 1
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated !== decoded) decoded.recycle()
        save(rotated)
    }

    suspend fun rotate(file: File): File = prepare {
        val bitmap = BitmapFactory.decodeFile(file.path) ?: error("Photo could not be opened.")
        val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(90f) }, true)
        bitmap.recycle()
        save(result)
    }

    suspend fun crop(file: File, left: Float, top: Float, right: Float, bottom: Float): File = prepare {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f && right > left && bottom > top)
        val bitmap = BitmapFactory.decodeFile(file.path) ?: error("Photo could not be opened.")
        val x = (left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val y = (top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val width = ((right - left) * bitmap.width).toInt().coerceIn(1, bitmap.width - x)
        val height = ((bottom - top) * bitmap.height).toInt().coerceIn(1, bitmap.height - y)
        val result = Bitmap.createBitmap(bitmap, x, y, width, height)
        if (result !== bitmap) bitmap.recycle()
        save(result)
    }

    private fun save(bitmap: Bitmap): File {
        val scale = minOf(1f, 1800f / maxOf(bitmap.width, bitmap.height))
        val resized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        return captureFile().also { file ->
            try { file.outputStream().use { check(resized.compress(Bitmap.CompressFormat.JPEG, 92, it)) } }
            catch (e: Throwable) { file.delete(); throw e }
            finally { if (resized !== bitmap) resized.recycle(); bitmap.recycle() }
        }
    }
}
