package com.trevisolli.conselheirosticker.editor

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.res.ResourcesCompat
import com.trevisolli.conselheirosticker.R
import java.io.File

data class FontOption(val key: String, val label: String)

/**
 * Fontes de meme: as embutidas são livres (OFL/Apache). A Impact é da Microsoft e não pode vir no app,
 * mas dá para importar qualquer .ttf/.otf que você tenha (inclusive ela).
 */
class Fonts(context: Context) {
    private val ctx = context.applicationContext
    private val dir = File(ctx.filesDir, "fonts").apply { mkdirs() }
    private val cache = HashMap<String, Typeface>()

    private val builtIn = linkedMapOf(
        "anton" to ("Anton (tipo Impact)" to R.font.anton),
        "bebas" to ("Bebas Neue" to R.font.bebas_neue),
        "archivo" to ("Archivo Black" to R.font.archivo_black),
        "bangers" to ("Bangers" to R.font.bangers),
        "luckiest" to ("Luckiest Guy" to R.font.luckiest_guy),
        "comic" to ("Comic Neue" to R.font.comic_neue_bold),
        "marker" to ("Permanent Marker" to R.font.permanent_marker),
    )

    fun options(): List<FontOption> =
        builtIn.map { (k, v) -> FontOption(k, v.first) } +
            (dir.listFiles()?.sortedBy { it.name }?.map { FontOption("file:" + it.name, it.nameWithoutExtension) }
                ?: emptyList())

    fun typeface(key: String): Typeface = cache.getOrPut(key) {
        runCatching {
            if (key.startsWith("file:")) Typeface.createFromFile(File(dir, key.removePrefix("file:")))
            else ResourcesCompat.getFont(ctx, builtIn[key]?.second ?: R.font.anton)
        }.getOrNull() ?: Typeface.DEFAULT_BOLD
    }

    /** Copia a fonte escolhida para o app. Devolve a chave, ou null se o arquivo não for uma fonte. */
    fun import(uri: Uri, displayName: String?): String? {
        val base = (displayName ?: "fonte").substringBeforeLast('.').filter { it.isLetterOrDigit() || it in " _-" }
            .ifBlank { "fonte" }.take(40)
        val ext = displayName?.substringAfterLast('.', "ttf")?.lowercase()?.takeIf { it == "otf" } ?: "ttf"
        val target = File(dir, "$base.$ext")
        ctx.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            ?: return null
        val ok = runCatching { Typeface.createFromFile(target) }.isSuccess && target.length() > 1000
        if (!ok) { target.delete(); return null }
        val key = "file:" + target.name
        cache.remove(key)
        return key
    }

    companion object {
        const val DEFAULT = "anton"
    }
}
