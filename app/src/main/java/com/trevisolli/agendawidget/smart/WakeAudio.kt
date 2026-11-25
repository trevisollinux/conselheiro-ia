package com.trevisolli.agendawidget.smart

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** PCM no formato aceito pelo Gemini depois de detectar a palavra localmente. */
internal object WakeAudio {
    const val RATE = 16_000
    const val END_SILENCE_MS = 700L
    const val FRAME = 1_600

    fun isSpeech(pcm: ShortArray, count: Int, noise: Double): Boolean {
        val cutoff = maxOf(110.0, noise * 3.0)
        return rms(pcm, count) > cutoff
    }

    fun rms(pcm: ShortArray, count: Int): Double {
        if (count <= 0) return 0.0
        var energy = 0.0
        for (i in 0 until count) {
            val value = pcm[i].toDouble()
            energy += value * value
        }
        return sqrt(energy / count)
    }

    fun pcm(frame: ShortArray, count: Int): ByteArray =
        ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (i in 0 until count) putShort(frame[i])
        }.array()

    fun wav(pcm: ByteArray): ByteArray = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray())
        putInt(36 + pcm.size)
        put("WAVEfmt ".toByteArray())
        putInt(16)
        putShort(1)
        putShort(1)
        putInt(RATE)
        putInt(RATE * 2)
        putShort(2)
        putShort(16)
        put("data".toByteArray())
        putInt(pcm.size)
        put(pcm)
    }.array()
}


/** Preserva a pergunta que vem colada à palavra de ativação sem escrever áudio no disco. */
internal class WakeBuffer(capacity: Int = WakeAudio.RATE * 10) {
    private val samples = ShortArray(capacity)
    private var end = 0
    private var size = 0

    fun add(frame: ShortArray, count: Int) {
        val kept = minOf(count, samples.size)
        val start = count - kept
        val first = minOf(kept, samples.size - end)
        frame.copyInto(samples, end, start, start + first)
        frame.copyInto(samples, 0, start + first, count)
        end = (end + kept) % samples.size
        size = minOf(size + kept, samples.size)
    }

    fun pcm(): ByteArray {
        val start = (end - size + samples.size) % samples.size
        if (start + size <= samples.size) return WakeAudio.pcm(samples.copyOfRange(start, start + size), size)
        val ordered = samples.copyOfRange(start, samples.size) + samples.copyOfRange(0, end)
        return WakeAudio.pcm(ordered, size)
    }

    fun clear() {
        end = 0
        size = 0
    }
}
