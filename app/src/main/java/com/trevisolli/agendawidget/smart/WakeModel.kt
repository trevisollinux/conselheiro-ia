package com.trevisolli.agendawidget.smart

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Modelo opcional do Vosk. O italiano contém a palavra exata "consigliere". */
internal object WakeModel {
    private const val MODEL = "vosk-model-small-it-0.22"
    private const val SHA256 = "9ec65e75861d1c6c2e457cccd932705340dcdf233f5b239f00733b4de0bf3267"
    private const val MAX_ARCHIVE_BYTES = 52_000_000L
    private const val MAX_UNPACKED_BYTES = 120_000_000L

    fun path(context: Context) = File(context.noBackupFilesDir, MODEL)
    fun installed(context: Context) = File(path(context), "ready").isFile

    @Synchronized
    fun install(context: Context) {
        if (installed(context)) return
        val target = path(context)
        val stage = File(context.noBackupFilesDir, "$MODEL.tmp")
        val zip = File.createTempFile("wake-model-", ".zip", context.cacheDir)
        try {
            stage.deleteRecursively()
            check(stage.mkdirs()) { "Não consegui preparar o modelo de voz" }
            download(zip)
            var remaining = MAX_UNPACKED_BYTES
            ZipInputStream(zip.inputStream().buffered()).use { entries ->
                while (true) {
                    val entry = entries.nextEntry ?: break
                    if (!entry.name.startsWith("$MODEL/")) throw IOException("Arquivo do modelo inválido")
                    val relative = entry.name.removePrefix("$MODEL/")
                    val output = File(stage, relative).canonicalFile
                    if (output != stage.canonicalFile && !output.path.startsWith(stage.canonicalPath + File.separator)) {
                        throw IOException("Arquivo do modelo inválido")
                    }
                    if (entry.isDirectory) {
                        output.mkdirs()
                    } else {
                        output.parentFile?.mkdirs()
                        output.outputStream().buffered().use { file ->
                            remaining -= writeBounded(entries, file, remaining)
                        }
                    }
                }
            }
            check(File(stage, "am/final.mdl").isFile && File(stage, "graph/HCLr.fst").isFile) {
                "Modelo de voz incompleto"
            }
            File(stage, "ready").createNewFile()
            target.deleteRecursively()
            check(stage.renameTo(target)) { "Não consegui salvar o modelo de voz" }
        } finally {
            zip.delete()
            stage.deleteRecursively()
        }
    }

    private fun download(zip: File) {
        val connection = URL("https://alphacephei.com/vosk/models/$MODEL.zip").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 25_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Site do modelo indisponível")
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(connection.inputStream.buffered(), digest).use { input ->
                zip.outputStream().buffered().use { output -> writeBounded(input, output, MAX_ARCHIVE_BYTES) }
            }
            val checksum = digest.digest().joinToString("") { "%02x".format(it) }
            if (checksum != SHA256) throw IOException("A verificação do arquivo do modelo falhou")
        } finally {
            connection.disconnect()
        }
    }

    private fun writeBounded(input: java.io.InputStream, output: java.io.OutputStream, limit: Long): Long {
        val buffer = ByteArray(32 * 1024)
        var size = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val count = input.read(buffer)
            if (count < 0) return size
            size += count
            if (size > limit) throw IOException("Arquivo do modelo maior que o esperado")
            output.write(buffer, 0, count)
        }
    }
}
