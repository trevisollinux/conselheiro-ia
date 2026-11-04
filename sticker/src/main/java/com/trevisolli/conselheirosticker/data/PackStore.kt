package com.trevisolli.conselheirosticker.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Sticker(
    val file: String,
    val emojis: List<String>,
)

data class Pack(
    val id: String,
    val name: String,
    val publisher: String,
    val stickers: List<Sticker>,
    /** Muda a cada alteração: o WhatsApp só relê as imagens quando este número muda. */
    val version: Int,
) {
    val trayFile: String get() = TRAY_FILE
    val readyForWhatsApp: Boolean get() = stickers.size in MIN_STICKERS..MAX_STICKERS

    companion object {
        const val TRAY_FILE = "tray.png"
        const val MIN_STICKERS = 3
        const val MAX_STICKERS = 30
    }
}

/**
 * Pacotes guardados em arquivos, sem servidor:
 * files/packs/<id>/pack.json, <figurinha>.webp, tray.png e edit/<figurinha>/ (para reeditar).
 * O ContentProvider do WhatsApp lê daqui também, por isso tudo é sincronizado.
 */
class PackStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "packs").apply { mkdirs() }

    fun dir(packId: String): File = File(root, safeId(packId))

    fun list(): List<Pack> = synchronized(LOCK) {
        root.listFiles()?.mapNotNull { read(it.name) }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    fun get(packId: String): Pack? = synchronized(LOCK) { read(packId) }

    fun create(name: String, publisher: String): Pack = synchronized(LOCK) {
        val pack = Pack(
            id = "p" + UUID.randomUUID().toString().replace("-", "").take(16),
            name = name.trim().take(128).ifBlank { "Meu pacote" },
            publisher = publisher.trim().take(128).ifBlank { "Eu" },
            stickers = emptyList(),
            version = 1,
        )
        dir(pack.id).mkdirs()
        write(pack)
        pack
    }

    fun rename(packId: String, name: String, publisher: String) = update(packId) {
        it.copy(
            name = name.trim().take(128).ifBlank { it.name },
            publisher = publisher.trim().take(128).ifBlank { it.publisher },
        )
    }

    fun delete(packId: String) = synchronized(LOCK) { dir(packId).deleteRecursively() }

    /** Salva (ou substitui, se [replaceFile] vier) uma figurinha já no formato do WhatsApp. */
    fun putSticker(packId: String, webp: ByteArray, emojis: List<String>, replaceFile: String?): String =
        synchronized(LOCK) {
            val pack = read(packId) ?: error("Pacote não existe")
            // Nome novo a cada versão: o WhatsApp guarda as imagens em cache pelo nome.
            val file = "s" + UUID.randomUUID().toString().replace("-", "").take(12) + ".webp"
            File(dir(packId), file).writeBytes(webp)
            val sticker = Sticker(file, emojis)
            val list = pack.stickers.toMutableList()
            val idx = list.indexOfFirst { it.file == replaceFile }
            if (idx >= 0) {
                File(dir(packId), list[idx].file).delete()
                val oldEdit = editDir(packId, list[idx].file)
                if (oldEdit.exists()) oldEdit.renameTo(editDir(packId, file))
                list[idx] = sticker
            } else {
                list += sticker
            }
            val updated = pack.copy(stickers = list, version = pack.version + 1)
            write(updated)
            refreshTray(updated)
            file
        }

    fun setEmojis(packId: String, file: String, emojis: List<String>) = update(packId) { p ->
        p.copy(stickers = p.stickers.map { if (it.file == file) it.copy(emojis = emojis) else it })
    }

    fun removeSticker(packId: String, file: String) = synchronized(LOCK) {
        val pack = read(packId) ?: return@synchronized
        File(dir(packId), file).delete()
        editDir(packId, file).deleteRecursively()
        val updated = pack.copy(stickers = pack.stickers.filter { it.file != file }, version = pack.version + 1)
        write(updated)
        refreshTray(updated)
    }

    fun move(packId: String, file: String, delta: Int) = update(packId) { p ->
        val list = p.stickers.toMutableList()
        val i = list.indexOfFirst { it.file == file }
        val j = i + delta
        if (i < 0 || j !in list.indices) p else {
            val s = list.removeAt(i); list.add(j, s)
            val updated = p.copy(stickers = list)
            refreshTray(updated)
            updated
        }
    }

    /** Pasta com a foto original, a máscara e os textos, para reeditar depois. */
    fun editDir(packId: String, stickerFile: String): File =
        File(dir(packId), "edit/" + stickerFile.removeSuffix(".webp"))

    fun stickerFile(packId: String, file: String): File = File(dir(packId), file)

    private fun update(packId: String, change: (Pack) -> Pack) = synchronized(LOCK) {
        val pack = read(packId) ?: return@synchronized
        write(change(pack).let { it.copy(version = pack.version + 1) })
    }

    /** Ícone do pacote: a primeira figurinha em 96×96 PNG (limite do WhatsApp: 50 KB). */
    private fun refreshTray(pack: Pack) {
        val tray = File(dir(pack.id), Pack.TRAY_FILE)
        val first = pack.stickers.firstOrNull() ?: run { tray.delete(); return }
        val src = BitmapFactory.decodeFile(File(dir(pack.id), first.file).path) ?: return
        val out = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, 96, 96),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        tray.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        src.recycle(); out.recycle()
    }

    private fun read(packId: String): Pack? = runCatching {
        val json = JSONObject(File(dir(packId), "pack.json").readText())
        val arr = json.getJSONArray("stickers")
        Pack(
            id = json.getString("id"),
            name = json.getString("name"),
            publisher = json.getString("publisher"),
            stickers = (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                val em = s.optJSONArray("emojis") ?: JSONArray()
                Sticker(s.getString("file"), (0 until em.length()).map { em.getString(it) })
            },
            version = json.optInt("version", 1),
        )
    }.getOrNull()

    private fun write(pack: Pack) {
        val json = JSONObject()
            .put("id", pack.id)
            .put("name", pack.name)
            .put("publisher", pack.publisher)
            .put("version", pack.version)
            .put("stickers", JSONArray().apply {
                pack.stickers.forEach { s ->
                    put(JSONObject().put("file", s.file).put("emojis", JSONArray(s.emojis)))
                }
            })
        val target = File(dir(pack.id), "pack.json")
        val tmp = File(dir(pack.id), "pack.json.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(target)
    }

    companion object {
        private val LOCK = Any()

        /** O WhatsApp aceita só letras, números, _ - . e espaço no identificador. */
        fun safeId(id: String): String = id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(64)
    }
}
