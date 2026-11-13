package com.trevisolli.agendawidget.smart

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Áudio compartilhado de outro app (gravação de ligação, áudio do WhatsApp, gravador): prepara para o Gemini.
 * Formatos que o Gemini aceita vão direto; .m4a/.mp4/.3gp com AAC são remontados em AAC puro (sem perder qualidade).
 */
object AudioImport {
    /** Limite do envio direto ao Gemini (a requisição inteira precisa ficar abaixo de ~20 MB). */
    private const val MAX_BYTES = 18 * 1024 * 1024

    private val DIRECT = mapOf(
        "audio/mpeg" to "audio/mp3", "audio/mp3" to "audio/mp3", "audio/wav" to "audio/wav", "audio/x-wav" to "audio/wav",
        "audio/aac" to "audio/aac", "audio/ogg" to "audio/ogg", "audio/opus" to "audio/ogg", "audio/flac" to "audio/flac",
        "audio/x-flac" to "audio/flac", "audio/aiff" to "audio/aiff", "audio/x-aiff" to "audio/aiff",
    )
    private val EXT = mapOf("mp3" to "audio/mp3", "wav" to "audio/wav", "aac" to "audio/aac", "ogg" to "audio/ogg", "opus" to "audio/ogg", "oga" to "audio/ogg", "flac" to "audio/flac")

    /** (bytes, mime) prontos para o Gemini. Lança exceção com mensagem para a pessoa. */
    fun prepare(context: Context, uri: Uri, mimeHint: String?): Pair<ByteArray, String> {
        val name = uri.lastPathSegment.orEmpty().lowercase()
        val direct = DIRECT[mimeHint?.lowercase()] ?: EXT[name.substringAfterLast('.', "")]
        val out = if (direct != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("não consegui abrir o arquivo")
            bytes to direct
        } else {
            toAdts(context, uri) to "audio/aac"
        }
        check(out.first.size <= MAX_BYTES) { "a gravação é grande demais (${out.first.size / 1_048_576} MB; o limite é 18 MB, uns 60 min)" }
        check(out.first.size > 2_000) { "o arquivo está vazio" }
        return out
    }

    /** Tira o AAC de dentro do .m4a/.mp4/.3gp e põe cabeçalho ADTS em cada quadro (AAC "puro"). */
    private fun toAdts(context: Context, uri: Uri): ByteArray {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" }
                ?: error("formato de áudio não suportado (use MP3, M4A, AAC, OGG ou WAV)")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            // Tipo do AAC: primeiros 5 bits do csd-0 (2 = AAC-LC, o mais comum).
            val objectType = format.getByteBuffer("csd-0")?.let { (it.get(0).toInt() and 0xFF) shr 3 }?.takeIf { it in 1..4 } ?: 2
            val buffer = ByteBuffer.allocate(256 * 1024)
            val out = ByteArrayOutputStream()
            while (true) {
                buffer.clear()
                val size = ex.readSampleData(buffer, 0)
                if (size < 0) break
                out.write(adtsHeader(size, objectType, rate, channels))
                out.write(buffer.array(), buffer.arrayOffset(), size)
                if (out.size() > MAX_BYTES) break
                ex.advance()
            }
            return out.toByteArray()
        } finally {
            ex.release()
        }
    }

    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    internal fun adtsHeader(frameSize: Int, objectType: Int, sampleRate: Int, channels: Int): ByteArray {
        val len = frameSize + 7
        val freq = RATES.indexOf(sampleRate).takeIf { it >= 0 } ?: 4
        val profile = objectType - 1
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            ((profile shl 6) or (freq shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or (len shr 11)).toByte(),
            ((len and 0x7FF) shr 3).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }
}
