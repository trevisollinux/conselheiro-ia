package com.trevisolli.agendawidget.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * Obras em domínio público do Met Museum (API aberta, sem chave).
 * Se a internet ou o museu falharem, usa a coleção embutida no app (baixada do Met na hora do build).
 */
object ArtSource {
    private const val API = "https://collectionapi.metmuseum.org/public/collection/v1"

    fun imageFile(context: Context) = File(context.filesDir, "art.jpg")
    private fun idsFile(context: Context, style: ArtStyle) = File(context.filesDir, "met_ids_${style.name}.json")

    /** Troca a obra se passou o intervalo ou se o estilo mudou. Retorna true se trocou. */
    fun refreshIfDue(context: Context, force: Boolean = false): Boolean {
        val s = Settings(context)
        if (s.widgetTheme != WidgetTheme.ART) return false
        val style = s.artStyle
        val due = System.currentTimeMillis() - s.artFetchedAt >= s.rotateMinutes * 60_000L
        if (!force && !due && s.artFetchedStyle == style.name && imageFile(context).exists()) return false

        val online = try {
            fromMet(context, style).also { if (it) s.artError = "" }
        } catch (e: Exception) {
            s.artError = "Met Museum indisponível (${e.message?.take(60)}); usando a coleção do app."
            false
        }
        val ok = online || fromBundle(context, style)
        if (ok) {
            s.artFetchedAt = System.currentTimeMillis()
            s.artFetchedStyle = style.name
        }
        return ok
    }

    private fun fromMet(context: Context, style: ArtStyle): Boolean {
        val ids = ids(context, style)
        if (ids.isEmpty()) return false
        // Algumas obras não têm imagem liberada: tenta até 6.
        repeat(6) {
            val id = ids[Random.nextInt(ids.size)]
            val o = Http.getPublic("$API/objects/$id")
            val img = o.optString("primaryImageSmall").ifBlank { o.optString("primaryImage") }
            if (o.optBoolean("isPublicDomain") && img.isNotBlank()) {
                imageFile(context).writeBytes(Http.getBytes(img))
                Settings(context).artCaption = caption(o.optString("title"), o.optString("artistDisplayName"))
                return true
            }
        }
        return false
    }

    /** IDs do estilo, guardados por uma semana. */
    private fun ids(context: Context, style: ArtStyle): List<Int> {
        val f = idsFile(context, style)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 24 * 3600_000L) {
            runCatching { JSONArray(f.readText()).let { a -> return List(a.length()) { a.getInt(it) } } }
        }
        val all = LinkedHashSet<Int>()
        for (q in style.queries) {
            // Busca nova do Met (v1.1, paginada; a v1 foi aposentada em out/2026).
            val resp = runCatching { Http.getPublic("${API}.1/search?$q&limit=200&offset=0") }.getOrNull() ?: continue
            all += extractIds(resp).take(300)
        }
        if (all.isNotEmpty()) f.writeText(JSONArray(all.toList()).toString())
        return all.toList()
    }

    /** Aceita os formatos possíveis da resposta de busca. */
    private fun extractIds(resp: JSONObject): List<Int> {
        for (key in listOf("objectIDs", "objectIds", "ids")) {
            resp.optJSONArray(key)?.let { a -> return List(a.length()) { a.optInt(it) }.filter { it > 0 } }
        }
        for (key in listOf("data", "results", "objects", "items", "records")) {
            val a = resp.optJSONArray(key) ?: continue
            return List(a.length()) { i ->
                a.optJSONObject(i)?.let { o -> o.optInt("objectID", o.optInt("objectId", o.optInt("id"))) } ?: a.optInt(i)
            }.filter { it > 0 }
        }
        return emptyList()
    }

    /** Coleção embutida: assets/art/manifest.json → {"ESTILO": [{"file": "...", "caption": "..."}]}. */
    private fun fromBundle(context: Context, style: ArtStyle): Boolean {
        val manifest = runCatching {
            JSONObject(context.assets.open("art/manifest.json").bufferedReader().use { it.readText() })
        }.getOrNull() ?: return false
        val list = manifest.optJSONArray(style.name)?.takeIf { it.length() > 0 }
            ?: manifest.optJSONArray(ArtStyle.GENERAL.name)?.takeIf { it.length() > 0 }
            ?: return false
        val pick = list.getJSONObject(Random.nextInt(list.length()))
        val bytes = runCatching { context.assets.open(pick.getString("file")).use { it.readBytes() } }.getOrNull() ?: return false
        imageFile(context).writeBytes(bytes)
        Settings(context).artCaption = pick.optString("caption")
        return true
    }

    private fun caption(title: String, artist: String) =
        listOf(title, artist).filter { it.isNotBlank() && it != "null" }.joinToString(" — ")
}
