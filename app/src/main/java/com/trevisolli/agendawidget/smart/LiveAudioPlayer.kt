package com.trevisolli.agendawidget.smart

import android.media.AudioTrack
import android.os.SystemClock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class LiveAudioPlayer {
    private data class Chunk(val epoch: Int, val pcm: ByteArray)
    private val queue = Channel<Chunk>(96)
    private val generation = AtomicInteger()
    private val playingUntil = AtomicLong()

    fun isPlaying(): Boolean = playingUntil.get() > SystemClock.elapsedRealtime()

    fun enqueue(bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size % 2 != 0 || bytes.size > 192_000) throw IOException("Trecho do Gemini Live inválido")
        val epoch = generation.get()
        playingUntil.updateAndGet { maxOf(it, SystemClock.elapsedRealtime()) + (bytes.size / 48L).coerceAtLeast(1) }
        if (!queue.trySend(Chunk(epoch, bytes)).isSuccess) throw IOException("O áudio chegou antes que o aparelho pudesse tocar")
    }

    fun interrupt() {
        generation.incrementAndGet()
        playingUntil.set(0)
        while (queue.tryReceive().isSuccess) Unit
    }

    fun close() = queue.close()

    suspend fun run(allowed: () -> Boolean, onFirstAudio: () -> Unit) {
        val track = GeminiSpeechStream.newPcmTrack()
        val context = currentCoroutineContext()
        var playedEpoch = -1
        var started = false
        try {
            while (true) {
                context.ensureActive()
                if (!allowed()) return
                val liveEpoch = generation.get()
                if (playedEpoch != liveEpoch) {
                    if (playedEpoch >= 0) {
                        track.pause()
                        track.flush()
                    }
                    playedEpoch = liveEpoch
                    started = false
                }
                val chunk = queue.tryReceive().getOrNull()
                if (chunk == null) {
                    delay(15)
                    continue
                }
                if (chunk.epoch != playedEpoch) continue
                if (!started) track.play()
                var offset = 0
                while (offset < chunk.pcm.size && chunk.epoch == generation.get() && allowed()) {
                    context.ensureActive()
                    val count = track.write(chunk.pcm, offset, minOf(chunk.pcm.size - offset, 2_400), AudioTrack.WRITE_BLOCKING)
                    if (count <= 0) throw IOException("Não consegui tocar a resposta do Gemini Live")
                    if (!started) {
                        started = true
                        onFirstAudio()
                    }
                    offset += count
                }
            }
        } finally {
            track.release()
        }
    }
}
