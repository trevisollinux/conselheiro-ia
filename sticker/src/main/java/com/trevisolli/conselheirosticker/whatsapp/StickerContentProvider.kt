package com.trevisolli.conselheirosticker.whatsapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.trevisolli.conselheirosticker.data.Pack
import com.trevisolli.conselheirosticker.data.PackStore
import java.io.FileNotFoundException

/**
 * Ponte com o WhatsApp, no formato do app de exemplo oficial
 * (github.com/WhatsApp/stickers). Só o WhatsApp tem a permissão de leitura.
 */
class StickerContentProvider : ContentProvider() {

    private lateinit var store: PackStore
    private lateinit var matcher: UriMatcher
    private lateinit var authority: String

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        store = PackStore(ctx)
        authority = authority(ctx.packageName)
        matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(authority, METADATA, CODE_ALL)
            addURI(authority, "$METADATA/*", CODE_ONE)
            addURI(authority, "$STICKERS/*", CODE_STICKERS)
            addURI(authority, "$STICKERS_ASSET/*/*", CODE_ASSET)
        }
        return true
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor = when (matcher.match(uri)) {
        CODE_ALL -> packsCursor(store.list().filter { it.readyForWhatsApp })
        CODE_ONE -> packsCursor(listOfNotNull(store.get(uri.lastPathSegment.orEmpty())))
        CODE_STICKERS -> stickersCursor(store.get(uri.lastPathSegment.orEmpty()))
        else -> throw IllegalArgumentException("URI desconhecida: $uri")
    }.also { it.setNotificationUri(context!!.contentResolver, uri) }

    private fun packsCursor(packs: List<Pack>): Cursor {
        val c = MatrixCursor(
            arrayOf(
                "sticker_pack_identifier", "sticker_pack_name", "sticker_pack_publisher",
                "sticker_pack_icon", "android_play_store_link", "ios_app_download_link",
                "sticker_pack_publisher_email", "sticker_pack_publisher_website",
                "sticker_pack_privacy_policy_website", "sticker_pack_license_agreement_website",
                "image_data_version", "whatsapp_will_not_cache_stickers", "animated_sticker_pack",
            )
        )
        packs.forEach { p ->
            c.addRow(
                arrayOf<Any>(
                    p.id, p.name, p.publisher, p.trayFile, "", "", "", "", "", "",
                    p.version.toString(), 0, if (p.animated) 1 else 0,
                )
            )
        }
        return c
    }

    private fun stickersCursor(pack: Pack?): Cursor {
        val c = MatrixCursor(arrayOf("sticker_file_name", "sticker_emoji", "sticker_accessibility_text"))
        pack?.stickers?.forEach { s -> c.addRow(arrayOf<Any>(s.file, s.emojis.joinToString(","), "")) }
        return c
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        if (matcher.match(uri) != CODE_ASSET) throw FileNotFoundException(uri.toString())
        val segs = uri.pathSegments
        val packId = segs[segs.size - 2]
        val name = segs.last()
        val pack = store.get(packId) ?: throw FileNotFoundException(packId)
        // Só entrega arquivos que pertencem ao pacote (nada de caminhos arbitrários).
        if (name != pack.trayFile && pack.stickers.none { it.file == name }) throw FileNotFoundException(name)
        val file = if (name == pack.trayFile) store.dir(packId).resolve(name) else store.stickerFile(packId, name)
        if (!file.exists()) throw FileNotFoundException(name)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        CODE_ALL -> "vnd.android.cursor.dir/vnd.$authority.$METADATA"
        CODE_ONE -> "vnd.android.cursor.item/vnd.$authority.$METADATA"
        CODE_STICKERS -> "vnd.android.cursor.dir/vnd.$authority.$STICKERS"
        CODE_ASSET -> if (uri.lastPathSegment.orEmpty().endsWith(".png")) "image/png" else "image/webp"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        const val METADATA = "metadata"
        const val STICKERS = "stickers"
        const val STICKERS_ASSET = "stickers_asset"
        private const val CODE_ALL = 1
        private const val CODE_ONE = 2
        private const val CODE_STICKERS = 3
        private const val CODE_ASSET = 4

        fun authority(packageName: String) = "$packageName.stickercontentprovider"
    }
}
