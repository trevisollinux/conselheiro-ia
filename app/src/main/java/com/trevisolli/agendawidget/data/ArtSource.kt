package com.trevisolli.agendawidget.data

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.net.URLEncoder
import kotlin.random.Random

/**
 * Obras de arte em domínio público do Art Institute of Chicago (API aberta, sem chave).
 * Baixa uma obra do estilo escolhido e troca no intervalo configurado.
 */
object ArtSource {
    fun imageFile(context: Context) = File(context.filesDir, "art.jpg")
    private fun listFile(context: Context, style: ArtStyle) = File(context.filesDir, "art_list_${style.name}.json")

    /** Troca a obra se passou o intervalo ou se o estilo mudou. Retorna true se trocou. */
    fun refreshIfDue(context: Context, force: Boolean = false): Boolean {
        val s = Settings(context)
        if (s.widgetTheme != WidgetTheme.ART) return false
        val style = s.artStyle
        val due = System.currentTimeMillis() - s.artFetchedAt >= s.rotateMinutes * 60_000L
        if (!force && !due && s.artFetchedStyle == style.name && imageFile(context).exists()) return false

        val list = candidates(context, style)
        if (list.length() == 0) return false
        val pick = list.getJSONObject(Random.nextInt(list.length()))
        val url = "https://www.artic.edu/iiif/2/${pick.getString("image_id")}/full/843,/0/default.jpg"
        val bytes = Http.getBytes(url)
        imageFile(context).writeBytes(bytes)
        s.artFetchedAt = System.currentTimeMillis()
        s.artFetchedStyle = style.name
        s.artCaption = listOf(pick.optString("title"), pick.optString("artist_title"))
            .filter { it.isNotBlank() && it != "null" }.joinToString(" — ")
        return true
    }

    /** Lista de obras do estilo, guardada por uma semana para não buscar toda vez. */
    private fun candidates(context: Context, style: ArtStyle): JSONArray {
        val f = listFile(context, style)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 24 * 3600_000L) {
            runCatching { return JSONArray(f.readText()) }
        }
        val url = "https://api.artic.edu/api/v1/artworks/search?limit=100" +
            "&fields=id,title,artist_title,image_id" +
            "&q=" + URLEncoder.encode(style.query, "UTF-8") +
            "&" + URLEncoder.encode("query[term][is_public_domain]", "UTF-8") + "=true"
        val data = Http.getPublic(url).optJSONArray("data") ?: JSONArray()
        val out = JSONArray()
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val img = o.optString("image_id")
            if (img.isNotBlank() && img != "null") out.put(o)
        }
        if (out.length() > 0) f.writeText(out.toString())
        return out
    }
}
