package com.trevisolli.conselheirosticker.data

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WebP animado sem biblioteca nativa: cada quadro é comprimido pelo próprio Android
 * (WebP parado) e os pedaços são montados no contêiner animado (VP8X + ANIM + ANMF),
 * como descrito na especificação do formato WebP.
 */
object AnimatedWebp {
    /** Limite do WhatsApp para figurinha animada. */
    const val MAX_BYTES = 500 * 1024
    const val MAX_DURATION_MS = 10_000
    private const val MIN_FRAME_MS = 8

    class Result(val bytes: ByteArray, val frames: Int, val quality: Int)

    /**
     * Monta a animação baixando a qualidade e, se preciso, pulando quadros até caber em 500 KB.
     * [frames] são as figurinhas já desenhadas em 512×512.
     */
    fun encodeFitting(frames: List<Bitmap>, durations: List<Int>): Result {
        require(frames.size == durations.size && frames.isNotEmpty())
        var step = 1
        var best: Result? = null
        while (true) {
            val (fs, ds) = subsample(frames, durations, step)
            for (q in intArrayOf(80, 65, 50, 40, 30, 20)) {
                val bytes = encode(fs, ds, q)
                val r = Result(bytes, fs.size, q)
                if (best == null || bytes.size < best.bytes.size) best = r
                if (bytes.size <= MAX_BYTES) return r
            }
            if (fs.size <= 2) return best!!
            step *= 2
        }
    }

    /** Fica com um quadro a cada [step]; a duração dos pulados vai para o que fica. */
    private fun subsample(frames: List<Bitmap>, durations: List<Int>, step: Int): Pair<List<Bitmap>, List<Int>> {
        if (step == 1) return frames to durations
        val fs = mutableListOf<Bitmap>()
        val ds = mutableListOf<Int>()
        var i = 0
        while (i < frames.size) {
            fs += frames[i]
            ds += (i until minOf(i + step, frames.size)).sumOf { durations[it] }
            i += step
        }
        return fs to ds
    }

    fun encode(frames: List<Bitmap>, durations: List<Int>, quality: Int): ByteArray {
        val w = frames[0].width
        val h = frames[0].height
        val body = ByteArrayOutputStream()
        // VP8X: animação + transparência
        body.chunk("VP8X", ByteArray(10).also {
            it[0] = (0x02 or 0x10).toByte()
            put24(it, 4, w - 1)
            put24(it, 7, h - 1)
        })
        // ANIM: fundo transparente, repetição infinita
        body.chunk("ANIM", ByteArray(6))
        frames.forEachIndexed { i, f ->
            val frameData = stillChunks(f, quality)
            val header = ByteArray(16)
            put24(header, 0, 0) // x/2
            put24(header, 3, 0) // y/2
            put24(header, 6, w - 1)
            put24(header, 9, h - 1)
            put24(header, 12, durations[i].coerceIn(MIN_FRAME_MS, 0xFFFFFF))
            header[15] = 0x02 // sem mistura com o quadro anterior; sem descarte
            body.chunk("ANMF", header + frameData)
        }
        val payload = body.toByteArray()
        val out = ByteArrayOutputStream(payload.size + 12)
        out.write("RIFF".toByteArray())
        out.write(le32(payload.size + 4))
        out.write("WEBP".toByteArray())
        out.write(payload)
        return out.toByteArray()
    }

    /** Comprime um quadro como WebP parado e devolve só os pedaços de imagem (ALPH + VP8/VP8L). */
    private fun stillChunks(bitmap: Bitmap, quality: Int): ByteArray {
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        val bytes = ByteArrayOutputStream().also { bitmap.compress(format, quality, it) }.toByteArray()
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP") { "WebP inválido" }
        val out = ByteArrayOutputStream()
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val tag = String(bytes, pos, 4)
            val size = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val total = 8 + size + (size and 1)
            if (tag == "ALPH" || tag == "VP8 " || tag == "VP8L") {
                out.write(bytes, pos, minOf(total, bytes.size - pos))
            }
            pos += total
        }
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.chunk(tag: String, data: ByteArray) {
        write(tag.toByteArray())
        write(le32(data.size))
        write(data)
        if (data.size and 1 == 1) write(0)
    }

    private fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun put24(a: ByteArray, at: Int, v: Int) {
        a[at] = (v and 0xFF).toByte()
        a[at + 1] = ((v shr 8) and 0xFF).toByte()
        a[at + 2] = ((v shr 16) and 0xFF).toByte()
    }
}
