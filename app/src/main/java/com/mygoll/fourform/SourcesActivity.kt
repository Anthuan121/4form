package com.mygoll.fourform

import android.app.Activity
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.LinearLayout
import com.mygoll.fourform.scan.Fontes
import com.mygoll.fourform.Ui.botaoSecundario
import com.mygoll.fourform.Ui.cartao
import com.mygoll.fourform.Ui.coluna
import com.mygoll.fourform.Ui.explicacao
import com.mygoll.fourform.Ui.h2
import com.mygoll.fourform.Ui.itemComPonto
import com.mygoll.fourform.Ui.kicker
import com.mygoll.fourform.Ui.placar

/**
 * Brief 258, item 3: "what does this app know about me, and where from". Every file (or the
 * typed-by-hand box) that ever fed the profile shows up here, with when it was last used and
 * how many of the CURRENT keys still stand on it. Removing a source only drops what came SOLELY
 * from it, a key that another source also provided keeps its value (Fontes.removerFonte).
 */
class SourcesActivity : Activity() {

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
        col.addView(kicker("Sources"))
        col.addView(h2("What fed your profile"))
        col.addView(explicacao("Each file you imported (or what you typed by hand), with when and how much of it still stands."))

        val entradas = Store.fontes(this)
        val resumo = Fontes.resumoPorFonte(entradas)
        if (resumo.isEmpty()) {
            col.addView(cartao().apply { addView(explicacao("Nothing imported yet. Load a file or type your profile in.")) })
            return
        }

        val conflitos = Fontes.conflitos(entradas)
        if (conflitos.isNotEmpty()) {
            val aviso = cartao()
            aviso.addView(placar("${conflitos.size}", if (conflitos.size == 1) "key in conflict" else "keys in conflict"))
            aviso.addView(explicacao("Two sources disagree on this key. The most recent one wins; check Learned or the profile box to see the value in use."))
            col.addView(aviso)
        }

        resumo.forEach { r ->
            val c = cartao()
            val quando = DateFormat.format("dd/MM HH:mm", r.ultimoUsoMs)
            c.addView(
                itemComPonto(
                    titulo = r.fonte,
                    detalhe = "${r.itensAtivos} ${if (r.itensAtivos == 1) "item" else "items"} in use",
                    corDoPonto = R.color.primaria,
                    procedencia = "last read $quando",
                )
            )
            c.addView(
                botaoSecundario("Remove this source") {
                    Store.removerFonte(this, r.fonte)
                    montar()
                }
            )
            col.addView(c)
        }
    }
}
