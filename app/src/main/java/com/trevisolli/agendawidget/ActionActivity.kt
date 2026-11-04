package com.trevisolli.agendawidget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.trevisolli.agendawidget.smart.QuickAction
import com.trevisolli.agendawidget.smart.QuickActions
import org.json.JSONObject

/** Ponte invisível: botão de notificação → ação de um toque (WhatsApp, ligar, rota, rastrear). */
class ActionActivity : Activity() {
    companion object {
        private const val EXTRA = "action"

        fun intent(context: Context, a: QuickAction): Intent = Intent(context, ActionActivity::class.java)
            .putExtra(EXTRA, a.toJson().toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra(EXTRA)?.let { raw ->
            runCatching { QuickActions.launch(this, QuickAction.fromJson(JSONObject(raw))) }
        }
        finish()
    }
}
