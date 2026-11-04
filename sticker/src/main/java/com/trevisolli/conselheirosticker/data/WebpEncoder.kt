package com.trevisolli.conselheirosticker.data

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream
import java.text.BreakIterator

object WebpEncoder {
    /** Limite do WhatsApp para figurinha parada. */
    const val MAX_BYTES = 100 * 1024

    /** 512×512 WebP com transparência, baixando a qualidade até caber em 100 KB. */
    fun encode(bitmap: Bitmap): ByteArray {
        require(bitmap.width == 512 && bitmap.height == 512) { "A figurinha precisa ter 512×512" }
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        var quality = 92
        while (true) {
            val out = ByteArrayOutputStream()
            bitmap.compress(format, quality, out)
            if (out.size() <= MAX_BYTES || quality <= 5) return out.toByteArray()
            quality -= if (quality > 40) 10 else 5
        }
    }
}

object Emojis {
    const val DEFAULT = "😀"

    /** Até 3 emojis (o WhatsApp usa para a busca); letras e espaços são ignorados. */
    fun parse(text: String): List<String> {
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val out = mutableListOf<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE && out.size < 3) {
            val g = text.substring(start, end)
            if (g.isNotBlank() && g.codePoints().anyMatch { cp -> isEmojiCodePoint(cp) }) out += g
            start = end
            end = it.next()
        }
        return out.ifEmpty { listOf(DEFAULT) }
    }

    private fun isEmojiCodePoint(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2300..0x23FF ||
            cp in 0x2B00..0x2BFF || cp == 0x00A9 || cp == 0x00AE || cp in 0x2190..0x21FF
}
