package com.trevisolli.agendawidget

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Contato do desenvolvedor, política de privacidade e denúncia de respostas da IA. */
object Support {
    const val EMAIL = "gustavotrevisolli@gmail.com"
    const val PRIVACY_URL = "https://trevisollinux.github.io/conselheiro-ia/privacidade.html"

    fun contact(context: Context, subject: String, body: String) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Escreva para $EMAIL", Toast.LENGTH_LONG).show()
        }
    }

    /** Denunciar uma resposta da IA (exigência da Play Store para apps com IA generativa). */
    fun report(context: Context, answer: String) = contact(
        context,
        "Consigliere: denúncia de resposta da IA",
        "Conte o que houve de errado (opcional):\n\n\n--- Resposta denunciada ---\n$answer\n\nVersão ${BuildConfig.VERSION_NAME}",
    )
}
