package com.trevisolli.conselheirosticker.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup sem conta: um .zip com os pacotes (figurinhas prontas + dados para reeditar).
 * O usuário escolhe onde salvar (Drive, Downloads, WhatsApp…) e importa de volta quando quiser.
 *
 * Dentro do zip:
 *   unusual-stickers.json        (formato e lista de pacotes)
 *   packs/<id>/pack.json, *.webp
 *   edits/<id>/<figurinha>/source.png, mask.png, state.json
 */
class Backup(context: Context, private val store: PackStore) {
    private val tmpRoot = File(context.applicationContext.filesDir, "import_tmp")

    data class ImportResult(val packs: List<Pack>, val skipped: Int)

    fun export(packIds: List<String>, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            val manifest = JSONObject()
                .put("format", FORMAT)
                .put("app", "Unusual Stickers")
                .put("packs", JSONArray(packIds))
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
            packIds.forEach { id ->
                // pack.json lido sob o mesmo cadeado que as gravações, para não sair pela metade.
                val pack = store.get(id) ?: return@forEach
                pack.stickers.forEach { store.editDir(pack.id, it.file) } // migra o formato antigo
                addTree(zip, store.dir(pack.id), "packs/${pack.id}")
                addTree(zip, store.editPackDir(pack.id), "edits/${pack.id}")
            }
        }
    }

    private fun addTree(zip: ZipOutputStream, dir: File, prefix: String) {
        if (!dir.exists()) return
        dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { f ->
            val rel = f.relativeTo(dir).invariantSeparatorsPath
            if (rel.startsWith("edit/")) return@forEach // formato antigo, já copiado por edits/
            zip.putNextEntry(ZipEntry("$prefix/$rel"))
            f.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    /** Restaura um backup. Pacote com o mesmo identificador é substituído pelo do arquivo. */
    fun import(input: InputStream): ImportResult {
        tmpRoot.deleteRecursively()
        val tmp = File(tmpRoot, "x").apply { mkdirs() }
        try {
            var total = 0L
            var count = 0
            var sawManifest = false
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory) continue
                    if (e.name == MANIFEST) { sawManifest = true; continue }
                    if (++count > MAX_ENTRIES) error("Arquivo com itens demais")
                    val target = safeTarget(tmp, e.name) ?: continue
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) error("Arquivo grande demais")
                            out.write(buf, 0, n)
                        }
                    }
                }
            }
            if (!sawManifest) error("Este arquivo não é um backup do Unusual Stickers")
            val packsDir = File(tmp, "packs")
            val installed = mutableListOf<Pack>()
            var skipped = 0
            packsDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                val pack = store.installFrom(dir, File(tmp, "edits/${dir.name}"))
                if (pack != null) installed += pack else skipped++
            }
            return ImportResult(installed, skipped)
        } finally {
            tmpRoot.deleteRecursively()
        }
    }

    /** Só aceita caminhos packs/<id>/... e edits/<id>/... sem "..", para não escrever fora da pasta. */
    private fun safeTarget(base: File, name: String): File? {
        val parts = name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.size < 3 || parts[0] !in setOf("packs", "edits")) return null
        if (parts.any { it == ".." || it == "." || it.length > 80 }) return null
        if (parts[1] != PackStore.safeId(parts[1]) || parts[1].isEmpty()) return null
        val f = File(base, parts.joinToString("/"))
        return f.takeIf { it.canonicalPath.startsWith(base.canonicalPath + File.separator) }
    }

    companion object {
        const val FORMAT = 1
        private const val MANIFEST = "unusual-stickers.json"
        private const val MAX_ENTRIES = 20_000
        private const val MAX_BYTES = 1_000_000_000L
    }
}
