package com.trevisolli.agendawidget.smart

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream

/** Prints compartilhados: reduz a imagem e lê o texto no próprio aparelho (ML Kit, sem internet). */
object ImageReader {
    private const val MAX_SIDE = 2304

    fun loadBitmap(context: Context, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalStateException("não consegui abrir a imagem")
        val longest = maxOf(decoded.width, decoded.height)
        val resized = if (longest <= MAX_SIDE) decoded else {
            val scale = MAX_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
        }
        if (resized !== decoded) decoded.recycle()
        val transform = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                val orientation = ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
                )
                Matrix().apply {
                    when (orientation) {
                        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                        ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                        ExifInterface.ORIENTATION_TRANSPOSE -> { setScale(-1f, 1f); postRotate(270f) }
                        ExifInterface.ORIENTATION_TRANSVERSE -> { setScale(-1f, 1f); postRotate(90f) }
                    }
                }
            }
        }.getOrNull()
        if (transform == null || transform.isIdentity) return resized
        return Bitmap.createBitmap(resized, 0, 0, resized.width, resized.height, transform, true).also {
            if (it !== resized) resized.recycle()
        }
    }

    fun toJpeg(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()

    suspend fun ocr(bitmap: Bitmap): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0)).await().text
        } finally {
            recognizer.close()
        }
    }
}
