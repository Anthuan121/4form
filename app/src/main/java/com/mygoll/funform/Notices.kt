package com.mygoll.funform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.mygoll.funform.core.ItemAberto
import com.mygoll.funform.core.Receipt

/**
 * O aviso é sempre PASSIVO (decisão do Anthuan 08/09): notificação, nunca diálogo nem
 * tela por cima. O campo fica aberto e o usuário preenche do jeito dele, com o foco do
 * teclado intacto.
 */
object Notices {

    private const val CANAL = "preenche"
    private const val ID_RESUMO = 1
    private const val ID_RECIBO = 2

    fun canal(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CANAL, "Preenche", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun base(ctx: Context): Notification.Builder =
        Notification.Builder(ctx, CANAL).setSmallIcon(android.R.drawable.ic_menu_edit)

    fun texto(ctx: Context, msg: String) {
        canal(ctx)
        ctx.getSystemService(NotificationManager::class.java)
            .notify(ID_RESUMO, base(ctx).setContentTitle("Preenche").setContentText(msg).build())
    }

    fun resumo(ctx: Context, escritos: Int, abertos: List<ItemAberto>) {
        canal(ctx)
        val titulo = "Preencheu $escritos campo(s) · ${abertos.size} aberto(s)"
        val corpo = abertos.take(6).joinToString("\n") { "• ${it.rotulo ?: "campo sem nome"}: ${it.motivo}" }
            .ifBlank { "Todos os campos identificados foram preenchidos." }
        ctx.getSystemService(NotificationManager::class.java).notify(
            ID_RESUMO,
            base(ctx)
                .setContentTitle(titulo)
                .setContentText(abertos.firstOrNull()?.let { "${it.rotulo ?: "campo"}: ${it.motivo}" } ?: corpo)
                .setStyle(Notification.BigTextStyle().bigText(corpo))
                .build()
        )
    }

    fun recibo(ctx: Context, r: Receipt) {
        canal(ctx)
        val intent = Intent(ctx, ReceiptActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val titulo = "Receipt: preencheu ${r.preenchidos.size} · aprendeu ${r.aprendidos.size}"
        ctx.getSystemService(NotificationManager::class.java).notify(
            ID_RECIBO,
            base(ctx)
                .setContentTitle(titulo)
                .setContentText("Toque para ver o que aprendi e desfazer se quiser.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
    }
}
