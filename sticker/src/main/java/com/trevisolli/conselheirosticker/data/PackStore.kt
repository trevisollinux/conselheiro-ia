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
    /** Pacote de figurinhas animadas (o WhatsApp não aceita misturar com paradas). */
    val animated: Boolean = false,
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

    /**
     * Dados para reeditar (foto original, máscara, textos) ficam fora de packs/: são pesados e
     * não entram no backup automático do Android (limite de 25 MB). Vão no backup em arquivo.
     */
    val editRoot = File(context.applicationContext.filesDir, "edits").apply { mkdirs() }

    fun editPackDir(packId: String): File = File(editRoot, safeId(packId))

    fun dir(packId: String): File = File(root, safeId(packId))

    fun list(): List<Pack> = synchronized(LOCK) {
        root.listFiles()?.mapNotNull { read(it.name) }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    fun get(packId: String): Pack? = synchronized(LOCK) { read(packId) }

    fun create(name: String, publisher: String, animated: Boolean = false): Pack = synchronized(LOCK) {
        val pack = Pack(
            id = "p" + UUID.randomUUID().toString().replace("-", "").take(16),
            name = name.trim().take(128).ifBlank { "Meu pacote" },
            publisher = publisher.trim().take(128).ifBlank { "Eu" },
            stickers = emptyList(),
            version = 1,
            animated = animated,
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

    fun delete(packId: String) = synchronized(LOCK) {
        dir(packId).deleteRecursively()
        editPackDir(packId).deleteRecursively()
    }

    /**
     * Instala um pacote vindo de um backup (já extraído em [packSrc] e [editSrc]), substituindo
     * o de mesmo identificador. A versão sobe para o WhatsApp reler as imagens.
     */
    fun installFrom(packSrc: File, editSrc: File?): Pack? = synchronized(LOCK) {
        val raw = readFile(File(packSrc, "pack.json")) ?: return@synchronized null
        val id = safeId(raw.id)
        if (id.isEmpty()) return@synchronized null
        val incoming = raw.copy(id = id, stickers = raw.stickers.filter { STICKER_NAME.matches(it.file) })
        val old = read(incoming.id)
        dir(incoming.id).deleteRecursively()
        editPackDir(incoming.id).deleteRecursively()
        if (!packSrc.renameTo(dir(incoming.id))) {
            packSrc.copyRecursively(dir(incoming.id), overwrite = true)
        }
        if (editSrc != null && editSrc.exists() && !editSrc.renameTo(editPackDir(incoming.id))) {
            editSrc.copyRecursively(editPackDir(incoming.id), overwrite = true)
        }
        // Só fica o que o pacote declara (nada de arquivos soltos vindos do zip).
        val keep = incoming.stickers.filter { File(dir(incoming.id), it.file).isFile }
        val pack = incoming.copy(
            stickers = keep,
            version = maxOf(incoming.version, old?.version ?: 0) + 1,
        )
        write(pack)
        refreshTray(pack)
        pack
    }

    /** Salva (ou substitui, se [replaceFile] vier) uma figurinha já no formato do WhatsApp. */
    /**
     * [still] é o primeiro quadro de uma figurinha animada: vira a miniatura e o ícone do pacote
     * (nem todo Android abre WebP animado como imagem parada).
     */
    fun putSticker(
        packId: String, webp: ByteArray, emojis: List<String>, replaceFile: String?, still: Bitmap? = null,
    ): String =
        synchronized(LOCK) {
            val pack = read(packId) ?: error("Pacote não existe")
            // Nome novo a cada versão: o WhatsApp guarda as imagens em cache pelo nome.
            val file = "s" + UUID.randomUUID().toString().replace("-", "").take(12) + ".webp"
            File(dir(packId), file).writeBytes(webp)
            if (still != null) thumbFile(packId, file).outputStream().use { still.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val sticker = Sticker(file, emojis)
            val list = pack.stickers.toMutableList()
            val idx = list.indexOfFirst { it.file == replaceFile }
            if (idx >= 0) {
                File(dir(packId), list[idx].file).delete()
                thumbFile(packId, list[idx].file).delete()
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
        thumbFile(packId, file).delete()
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
    fun editDir(packId: String, stickerFile: String): File {
        val name = stickerFile.removeSuffix(".webp")
        val target = File(editPackDir(packId), name)
        // Versões até a 0.3 guardavam dentro de packs/<id>/edit/: move na primeira vez.
        val legacy = File(dir(packId), "edit/$name")
        if (legacy.exists() && !target.exists()) {
            target.parentFile?.mkdirs()
            legacy.renameTo(target)
        }
        return target
    }

    fun stickerFile(packId: String, file: String): File = File(dir(packId), file)

    /** Miniatura parada (PNG) de uma figurinha animada; não existe nas paradas. */
    fun thumbFile(packId: String, file: String): File = File(dir(packId), "thumb_" + file.removeSuffix(".webp") + ".png")

    private fun update(packId: String, change: (Pack) -> Pack) = synchronized(LOCK) {
        val pack = read(packId) ?: return@synchronized
        write(change(pack).let { it.copy(version = pack.version + 1) })
    }

    /** Ícone do pacote: a primeira figurinha em 96×96 PNG (limite do WhatsApp: 50 KB). */
    private fun refreshTray(pack: Pack) {
        val tray = File(dir(pack.id), Pack.TRAY_FILE)
        val first = pack.stickers.firstOrNull() ?: run { tray.delete(); return }
        val thumb = thumbFile(pack.id, first.file)
        val src = (if (thumb.exists()) BitmapFactory.decodeFile(thumb.path) else null)
            ?: BitmapFactory.decodeFile(File(dir(pack.id), first.file).path) ?: return
        val out = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, Rect(0, 0, src.width, src.height), Rect(0, 0, 96, 96),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        tray.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        src.recycle(); out.recycle()
    }

    private fun read(packId: String): Pack? = readFile(File(dir(packId), "pack.json"))

    private fun readFile(file: File): Pack? = runCatching {
        val json = JSONObject(file.readText())
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
            animated = json.optBoolean("animated", false),
        )
    }.getOrNull()

    private fun write(pack: Pack) {
        val json = JSONObject()
            .put("id", pack.id)
            .put("name", pack.name)
            .put("publisher", pack.publisher)
            .put("version", pack.version)
            .put("animated", pack.animated)
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
        private val STICKER_NAME = Regex("[A-Za-z0-9_-]{1,64}\\.webp")

        /** O WhatsApp aceita só letras, números, _ - . e espaço no identificador. */
        fun safeId(id: String): String = id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(64)
    }
}
