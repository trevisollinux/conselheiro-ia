package com.trevisolli.conselheirosticker.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** GIF ou vídeo escolhido para virar figurinha animada. */
data class ClipInfo(val uri: Uri, val isGif: Boolean, val durationMs: Int, val preview: Bitmap?)

class Clip(val frames: List<Bitmap>, val durations: List<Int>)

object Clips {
    /** Lado maior dos quadros: a figurinha tem 512 px e cada quadro ocupa memória. */
    private const val MAX_SIDE = 480
    /** Até 50 quadros e 10 por segundo: equilíbrio entre fluidez, memória e o limite de 500 KB. */
    const val MAX_FRAMES = 50
    const val MAX_FPS = 10
    const val MAX_CLIP_MS = 10_000

    suspend fun probe(context: Context, uri: Uri): ClipInfo = withContext(Dispatchers.IO) {
        val type = context.contentResolver.getType(uri).orEmpty()
        if (type == "image/gif" || (!type.startsWith("video/") && isGif(context, uri))) {
            val movie = decodeGif(context, uri)
            val dur = movie.duration()
            if (dur <= 0) error("Esse GIF não tem animação")
            ClipInfo(uri, true, dur, gifFrame(movie, 0))
        } else {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
                if (dur <= 0) error("Não consegui ler esse vídeo")
                ClipInfo(uri, false, dur, videoFrame(r, 0))
            } finally {
                r.release()
            }
        }
    }

    /** Extrai os quadros do trecho [startMs, endMs), em intervalos iguais. */
    suspend fun extract(
        context: Context, info: ClipInfo, startMs: Int, endMs: Int, onProgress: (Float) -> Unit,
    ): Clip = withContext(Dispatchers.IO) {
        val len = (endMs - startMs).coerceIn(100, MAX_CLIP_MS)
        val count = (len * MAX_FPS / 1000).coerceIn(2, MAX_FRAMES)
        val step = len / count
        val frames = ArrayList<Bitmap>(count)
        try {
            if (info.isGif) {
                val movie = decodeGif(context, info.uri)
                for (i in 0 until count) {
                    ensureActive()
                    frames += gifFrame(movie, startMs + i * step)
                    onProgress((i + 1f) / count)
                }
            } else {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, info.uri)
                    for (i in 0 until count) {
                        ensureActive()
                        frames += videoFrame(r, startMs + i * step)
                        onProgress((i + 1f) / count)
                    }
                } finally {
                    r.release()
                }
            }
        } catch (e: Throwable) {
            frames.forEach { it.recycle() }
            throw e
        }
        // Todos do mesmo tamanho do primeiro (a máscara de recorte vale para todos).
        val w = frames[0].width
        val h = frames[0].height
        val same = frames.map { f ->
            if (f.width == w && f.height == h) f
            else Bitmap.createScaledBitmap(f, w, h, true).also { f.recycle() }
        }
        Clip(same, List(same.size) { step })
    }

    private fun isGif(context: Context, uri: Uri): Boolean =
        context.contentResolver.openInputStream(uri)?.use { s ->
            val head = ByteArray(6)
            s.read(head) == 6 && String(head, Charsets.US_ASCII).startsWith("GIF8")
        } ?: false

    @Suppress("DEPRECATION")
    internal fun decodeGif(context: Context, uri: Uri): android.graphics.Movie {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Não consegui abrir o GIF")
        return android.graphics.Movie.decodeByteArray(bytes, 0, bytes.size) ?: error("GIF inválido")
    }

    @Suppress("DEPRECATION")
    internal fun gifFrame(movie: android.graphics.Movie, timeMs: Int): Bitmap {
        val w = movie.width().coerceAtLeast(1)
        val h = movie.height().coerceAtLeast(1)
        val k = minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))
        val out = Bitmap.createBitmap((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        movie.setTime(timeMs.coerceIn(0, movie.duration().coerceAtLeast(0)))
        Canvas(out).apply { scale(k, k); movie.draw(this, 0f, 0f) }
        return out
    }

    internal fun videoFrame(r: MediaMetadataRetriever, timeMs: Int): Bitmap {
        val us = timeMs * 1000L
        val raw = if (Build.VERSION.SDK_INT >= 27) {
            r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, MAX_SIDE, MAX_SIDE)
        } else {
            r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
        } ?: error("Não consegui ler um quadro do vídeo")
        val k = minOf(1f, MAX_SIDE.toFloat() / maxOf(raw.width, raw.height))
        val scaled = if (k < 1f) {
            Bitmap.createScaledBitmap(raw, (raw.width * k).toInt(), (raw.height * k).toInt(), true).also { raw.recycle() }
        } else raw
        return if (scaled.config == Bitmap.Config.ARGB_8888 && scaled.isMutable) scaled
        else scaled.copy(Bitmap.Config.ARGB_8888, true).also { scaled.recycle() }
    }

    /**
     * Mostra um quadro qualquer do GIF/vídeo (para a prévia do trecho), deixando o arquivo aberto
     * enquanto a pessoa mexe na faixa. Use de uma thread só por vez e feche no fim.
     */
    class FramePreview(context: Context, private val info: ClipInfo) : java.io.Closeable {
        private val retriever: MediaMetadataRetriever? =
            if (info.isGif) null else MediaMetadataRetriever().apply { setDataSource(context, info.uri) }
        @Suppress("DEPRECATION")
        private val movie: android.graphics.Movie? = if (info.isGif) decodeGif(context, info.uri) else null

        @Synchronized
        fun frame(timeMs: Int): Bitmap? = runCatching {
            val t = timeMs.coerceIn(0, info.durationMs)
            movie?.let { gifFrame(it, t) } ?: retriever?.let { videoFrame(it, t) }
        }.getOrNull()

        @Synchronized
        override fun close() {
            runCatching { retriever?.release() }
        }
    }
}
