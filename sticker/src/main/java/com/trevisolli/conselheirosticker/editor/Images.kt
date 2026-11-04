package com.trevisolli.conselheirosticker.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object Images {
    private const val MAX_SIDE = 1024

    /** Abre a foto já girada certo e reduzida (até 1024 px), editável. */
    suspend fun load(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val bmp = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { dec, info, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                dec.isMutableRequired = true
                val s = info.size
                val k = minOf(1f, MAX_SIDE.toFloat() / maxOf(s.width, s.height))
                if (k < 1f) dec.setTargetSize((s.width * k).toInt().coerceAtLeast(1), (s.height * k).toInt().coerceAtLeast(1))
            }
        } else {
            loadLegacy(context, uri)
        }
        if (bmp.config == Bitmap.Config.ARGB_8888 && bmp.isMutable) bmp
        else bmp.copy(Bitmap.Config.ARGB_8888, true).also { bmp.recycle() }
    }

    private fun loadLegacy(context: Context, uri: Uri): Bitmap {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val raw = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Não consegui abrir a imagem")
        val orientation = cr.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        var bmp = raw
        val k = minOf(1f, MAX_SIDE.toFloat() / maxOf(raw.width, raw.height))
        if (deg != 0f || k < 1f) {
            val m = Matrix().apply { postRotate(deg); postScale(k, k) }
            bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            if (bmp != raw) raw.recycle()
        }
        return bmp
    }

    /**
     * Recorte automático (ML Kit, no aparelho). Devolve a confiança 0..1 de cada pixel ser
     * a pessoa/objeto principal.
     */
    suspend fun segment(source: Bitmap): FloatArray {
        val options = SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build()
        val client = SubjectSegmentation.getClient(options)
        try {
            val result = client.process(InputImage.fromBitmap(source, 0)).await()
            val buf = result.foregroundConfidenceMask ?: error("Nada encontrado na foto")
            buf.rewind()
            return FloatArray(source.width * source.height).also { buf.get(it) }
        } finally {
            client.close()
        }
    }
}
