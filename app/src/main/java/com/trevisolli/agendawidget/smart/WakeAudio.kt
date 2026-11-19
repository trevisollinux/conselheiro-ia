package com.trevisolli.agendawidget.smart

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** PCM no formato aceito pelo Gemini depois de detectar a palavra localmente. */
internal object WakeAudio {
    const val RATE = 16_000
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
