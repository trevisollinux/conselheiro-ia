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
    /** WebP animado (GIF, vídeo ou foto com efeito). */
    val animated: Boolean = false,
)

data class Pack(
    val id: String,
    val name: String,
    val publisher: String,
    val stickers: List<Sticker>,
    /** Muda a cada alteração: o WhatsApp só relê as imagens quando este número muda. */
    val version: Int,
    /** Última [version] mandada ao WhatsApp por este app (0 = nunca). */
    val sentVersion: Int = 0,
) {
    /** Mudou alguma coisa depois da última vez que foi mandado ao WhatsApp. */
    val changedSinceSent: Boolean get() = sentVersion > 0 && sentVersion != version

    /**
     * O WhatsApp não aceita misturar paradas e animadas num pacote. Com pelo menos uma animada,
     * o pacote vai como animado e as paradas seguem como animação de 2 quadros iguais.
     */
    val animated: Boolean get() = stickers.any { it.animated }

    /** Nome do arquivo que o WhatsApp recebe para esta figurinha. */
    fun servedFile(s: Sticker): String = if (animated && !s.animated) WRAP_PREFIX + s.file else s.file

    val trayFile: String get() = TRAY_FILE
    val readyForWhatsApp: Boolean get() = stickers.size in MIN_STICKERS..MAX_STICKERS

    companion object {
        const val TRAY_FILE = "tray.png"
        const val WRAP_PREFIX = "w_"
        const val MIN_STICKERS = 3
        const val MAX_STICKERS = 30
    }
}

/**
 * Pacotes guardados em arquivos, sem servidor (w_<figurinha>.webp: versão animada de uma parada):
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
        ensureWrapped(pack)
        pack
    }

    /** Salva (ou substitui, se [replaceFile] vier) uma figurinha já no formato do WhatsApp. */
    /**
     * [still] é o primeiro quadro de uma figurinha animada: vira a miniatura e o ícone do pacote
     * (nem todo Android abre WebP animado como imagem parada).
     */
    fun putSticker(
        packId: String, webp: ByteArray, emojis: List<String>, replaceFile: String?,
        animated: Boolean = false, still: Bitmap? = null,
    ): String =
        synchronized(LOCK) {
            val pack = read(packId) ?: error("Pacote não existe")
            // Nome novo a cada versão: o WhatsApp guarda as imagens em cache pelo nome.
            val file = "s" + UUID.randomUUID().toString().replace("-", "").take(12) + ".webp"
            File(dir(packId), file).writeBytes(webp)
            if (still != null) thumbFile(packId, file).outputStream().use { still.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val sticker = Sticker(file, emojis, animated)
            val list = pack.stickers.toMutableList()
            val idx = list.indexOfFirst { it.file == replaceFile }
            if (idx >= 0) {
                File(dir(packId), list[idx].file).delete()
                File(dir(packId), Pack.WRAP_PREFIX + list[idx].file).delete()
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
            ensureWrapped(updated)
            file
        }

    /** Copia uma figurinha (com os dados de reedição) logo depois dela. Null se o pacote já está cheio. */
    fun duplicateSticker(packId: String, file: String): String? = synchronized(LOCK) {
        val pack = read(packId) ?: return@synchronized null
        if (pack.stickers.size >= Pack.MAX_STICKERS) return@synchronized null
        val idx = pack.stickers.indexOfFirst { it.file == file }
        if (idx < 0) return@synchronized null
        val src = pack.stickers[idx]
        val copy = "s" + UUID.randomUUID().toString().replace("-", "").take(12) + ".webp"
        File(dir(packId), src.file).copyTo(File(dir(packId), copy))
        thumbFile(packId, src.file).takeIf { it.exists() }?.copyTo(thumbFile(packId, copy))
        editDir(packId, src.file).takeIf { it.exists() }?.copyRecursively(editDir(packId, copy))
        val list = pack.stickers.toMutableList().apply { add(idx + 1, src.copy(file = copy)) }
        val updated = pack.copy(stickers = list, version = pack.version + 1)
        write(updated)
        ensureWrapped(updated)
        copy
    }

    /** Marca a versão atual como mandada ao WhatsApp (sem mudar a versão). */
    fun markSent(packId: String) = synchronized(LOCK) {
        val pack = read(packId) ?: return@synchronized
        if (pack.sentVersion != pack.version) write(pack.copy(sentVersion = pack.version))
    }

    fun setEmojis(packId: String, file: String, emojis: List<String>) = update(packId) { p ->
        p.copy(stickers = p.stickers.map { if (it.file == file) it.copy(emojis = emojis) else it })
    }

    fun removeSticker(packId: String, file: String) = synchronized(LOCK) {
        val pack = read(packId) ?: return@synchronized
        File(dir(packId), file).delete()
        File(dir(packId), Pack.WRAP_PREFIX + file).delete()
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

    /**
     * Arquivo que o WhatsApp lê para [served] (o nome que [Pack.servedFile] deu), criando na hora
     * a versão animada de uma parada se ainda não existir. Null se não for deste pacote.
     */
    fun servedPath(packId: String, served: String): File? = synchronized(LOCK) {
        val pack = read(packId) ?: return@synchronized null
        val sticker = pack.stickers.firstOrNull { pack.servedFile(it) == served } ?: return@synchronized null
        if (served != sticker.file) wrap(pack.id, sticker.file)
        File(dir(pack.id), served).takeIf { it.isFile }
    }

    /** Garante a versão animada (2 quadros iguais) das paradas, se o pacote for animado. */
    private fun ensureWrapped(pack: Pack) {
        if (!pack.animated) return
        pack.stickers.filter { !it.animated }.forEach { wrap(pack.id, it.file) }
    }

    private fun wrap(packId: String, file: String) {
        val out = File(dir(packId), Pack.WRAP_PREFIX + file)
        if (out.isFile) return
        val bmp = BitmapFactory.decodeFile(File(dir(packId), file).path) ?: return
        try {
            val frame = if (bmp.width == 512 && bmp.height == 512) bmp
            else Bitmap.createScaledBitmap(bmp, 512, 512, true)
            val r = AnimatedWebp.encodeFitting(listOf(frame, frame), listOf(500, 500))
            val tmp = File(out.path + ".tmp")
            tmp.writeBytes(r.bytes)
            tmp.renameTo(out)
            if (frame !== bmp) frame.recycle()
        } finally {
            bmp.recycle()
        }
    }

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
                // Até a 1.0 o tipo era do pacote inteiro ("animated" no pacote).
                val animated = if (s.has("animated")) s.getBoolean("animated") else json.optBoolean("animated", false)
                Sticker(s.getString("file"), (0 until em.length()).map { em.getString(it) }, animated)
            },
            version = json.optInt("version", 1),
            sentVersion = json.optInt("sentVersion", 0),
        )
    }.getOrNull()

    private fun write(pack: Pack) {
        val json = JSONObject()
            .put("id", pack.id)
            .put("name", pack.name)
            .put("publisher", pack.publisher)
            .put("version", pack.version)
            .put("sentVersion", pack.sentVersion)
            .put("animated", pack.animated)
            .put("stickers", JSONArray().apply {
                pack.stickers.forEach { s ->
                    put(JSONObject().put("file", s.file).put("emojis", JSONArray(s.emojis)).put("animated", s.animated))
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
