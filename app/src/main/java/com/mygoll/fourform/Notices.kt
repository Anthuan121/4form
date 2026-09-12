package com.mygoll.fourform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.mygoll.fourform.agent.ItemAberto
import com.mygoll.fourform.agent.Receipt

/**
 * The notice is always PASSIVE (Anthuan's decision on 09/08): a notification, never a
 * dialog or an overlay screen. The field stays open and the user fills it their own way,
 * with keyboard focus intact.
 */
object Notices {

    private const val CANAL = "preenche"
    private const val ID_RESUMO = 1
    private const val ID_RECIBO = 2

    fun canal(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANAL, "4Form", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun base(ctx: Context): Notification.Builder =
        Notification.Builder(ctx, CANAL).setSmallIcon(android.R.drawable.ic_menu_edit)

    fun texto(ctx: Context, msg: String) {
        canal(ctx)
        ctx.getSystemService(NotificationManager::class.java)
            .notify(ID_RESUMO, base(ctx).setContentTitle("4Form").setContentText(msg).build())
    }

    fun resumo(ctx: Context, escritos: Int, abertos: List<ItemAberto>) {
        canal(ctx)
        val titulo = "Filled $escritos field(s) · ${abertos.size} open"
        val corpo = abertos.take(6).joinToString("\n") { "• ${it.rotulo ?: "unnamed field"}: ${it.motivo}" }
            .ifBlank { "All identified fields were filled." }
        ctx.getSystemService(NotificationManager::class.java).notify(
            ID_RESUMO,
            base(ctx)
                .setContentTitle(titulo)
                .setContentText(abertos.firstOrNull()?.let { "${it.rotulo ?: "field"}: ${it.motivo}" } ?: corpo)
                .setStyle(Notification.BigTextStyle().bigText(corpo))
                .build()
        )
    }

    fun recibo(ctx: Context, r: Receipt) {
        canal(ctx)
        val intent = Intent(ctx, ReceiptActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val titulo = "Receipt: filled ${r.preenchidos.size} · learned ${r.aprendidos.size}"
        ctx.getSystemService(NotificationManager::class.java).notify(
            ID_RECIBO,
            base(ctx)
                .setContentTitle(titulo)
                .setContentText("Tap to see what I learned, and undo it if you want.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
    }
}
