package com.trevisolli.conselheirosticker.whatsapp

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.trevisolli.conselheirosticker.data.Pack

object WhatsApp {
    private const val CONSUMER = "com.whatsapp"
    private const val BUSINESS = "com.whatsapp.w4b"

    fun installed(context: Context): List<String> = listOf(CONSUMER, BUSINESS).filter {
        try {
            context.packageManager.getPackageInfo(it, 0); true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Pergunta ao WhatsApp (e ao WhatsApp Business) se o pacote já foi adicionado, pelo provider
     * oficial "sticker_whitelist_check". Chamar fora da thread principal.
     */
    fun isPackAdded(context: Context, packId: String): Boolean = installed(context).any { pkg ->
        val authority = "$pkg.provider.sticker_whitelist_check"
        runCatching {
            if (context.packageManager.resolveContentProvider(authority, 0) == null) return@runCatching false
            val uri = Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(authority)
                .appendPath("is_whitelisted")
                .appendQueryParameter("authority", StickerContentProvider.authority(context.packageName))
                .appendQueryParameter("identifier", packId)
                .build()
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow("result")) == 1
            } ?: false
        }.getOrDefault(false)
    }

    /** Intent oficial "adicionar pacote"; o WhatsApp mostra a tela de confirmação. */
    fun addPackIntent(context: Context, pack: Pack, targetPackage: String?): Intent =
        Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").apply {
            putExtra("sticker_pack_id", pack.id)
            putExtra("sticker_pack_authority", StickerContentProvider.authority(context.packageName))
            putExtra("sticker_pack_name", pack.name)
            if (targetPackage != null) setPackage(targetPackage)
        }

    /** Mensagem amigável para o resultado da tela do WhatsApp. */
    fun describeResult(resultCode: Int, data: Intent?): String? = when {
        resultCode == Activity.RESULT_OK -> "Pacote adicionado ao WhatsApp 🎉"
        data?.getStringExtra("validation_error") != null ->
            "O WhatsApp recusou: " + data.getStringExtra("validation_error")
        else -> null // cancelado pelo usuário ou já adicionado
    }
}
