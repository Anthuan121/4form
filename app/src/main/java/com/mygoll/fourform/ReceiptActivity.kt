package com.mygoll.fourform

import com.mygoll.fourform.scan.Learned
import android.app.Activity
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.LinearLayout
import com.mygoll.fourform.Ui.botaoSecundario
import com.mygoll.fourform.Ui.cartao
import com.mygoll.fourform.Ui.coluna
import com.mygoll.fourform.Ui.explicacao
import com.mygoll.fourform.Ui.h2
import com.mygoll.fourform.Ui.itemComPonto
import com.mygoll.fourform.Ui.kicker
import com.mygoll.fourform.Ui.placar
import com.mygoll.fourform.Ui.rotuloCampo
import com.mygoll.fourform.Ui.selo

/**
 * THE RECEIPT (his idea, 09/09): the session is over, the app shows what it did and what
 * it learned, with UNDO per item. Learning without being able to unlearn isn't control.
 *
 * Design from 09/12. The color grammar is the same as the other screens and it's the
 * product's core: sage = backed by your profile · amber = DECLARED GAP, ⛔ not an error.
 * Category 4 of his rule ("the app doesn't answer and says so") needs to look like a
 * virtue, because that's what separates this app from competitors, which would guess.
 */
class ReceiptActivity : Activity() {

    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (rolo, c) = coluna()
        col = c
        setContentView(rolo)
    }

    override fun onResume() {
        super.onResume()
        montar()
    }

    private fun montar() {
        col.removeAllViews()
        col.addView(kicker("Receipt"))
        col.addView(h2("What I did in the last session"))

        val r = Store.lerRecibo(this)
        if (r == null) {
            col.addView(explicacao("No receipt yet: no session has filled or learned anything."))
            return
        }

        val total = r.preenchidos.size + r.abertos.size
        val resumo = cartao()
        resumo.addView(placar("${r.preenchidos.size} of $total", "fields filled"))
        if (r.abertos.isNotEmpty()) {
            resumo.addView(
                explicacao("${r.abertos.size} stayed blank because your profile didn't answer.")
            )
        }
        resumo.addView(selo("Nothing invented in this application."))
        col.addView(resumo)

        if (r.preenchidos.isNotEmpty()) {
            val c = cartao()
            c.addView(rotuloCampo("I filled"))
            r.preenchidos.forEach {
                c.addView(itemComPonto(it.rotulo, "\"${it.valor}\"", R.color.sucesso, it.fonte))
            }
            col.addView(c)
        }

        if (r.abertos.isNotEmpty()) {
            val c = cartao()
            c.addView(rotuloCampo("I left blank, and why"))
            r.abertos.forEach {
                c.addView(itemComPonto(it.rotulo ?: "unnamed field", it.motivo, R.color.aviso))
            }
            col.addView(c)
        }

        if (r.aprendidos.isNotEmpty()) {
            val c = cartao()
            c.addView(rotuloCampo("I just learned"))
            c.addView(explicacao("Came from your corrections. Applies from next time on."))
            r.aprendidos.forEach { a -> c.addView(blocoAprendido(a)) }
            col.addView(c)
        }
    }

    private fun blocoAprendido(a: Learned): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val origem = if (a.origem == "corrigiu") "you corrected it" else "you filled in what was missing"
        val quando = DateFormat.format("dd/MM HH:mm", a.quandoMs)
        val vivo = Store.temAprendido(this, a.rotulo, a.quandoMs)
        row.addView(
            itemComPonto(
                a.rotulo,
                if (vivo) "\"${a.valor}\"" else "undone",
                if (vivo) R.color.primaria else R.color.texto_secundario,
                "$origem · $quando",
            )
        )
        if (vivo) {
            row.addView(
                botaoSecundario("Undo this learned rule") {
                    Store.removerAprendido(this, a.rotulo, a.quandoMs)
                    montar()
                }
            )
        }
        return row
    }
}
