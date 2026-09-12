package com.mygoll.fourform

import com.mygoll.fourform.scan.Learned
import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.EditText
import android.widget.LinearLayout
import com.mygoll.fourform.Ui.botaoSecundario
import com.mygoll.fourform.Ui.cartao
import com.mygoll.fourform.Ui.coluna
import com.mygoll.fourform.Ui.explicacao
import com.mygoll.fourform.Ui.h2
import com.mygoll.fourform.Ui.itemComPonto
import com.mygoll.fourform.Ui.kicker
import com.mygoll.fourform.Ui.placar

/**
 * Where the learned profile LIVES (his request, 09/09): everything the app knows by
 * observation, with edit and delete. It's the screen that answers "what does this app know
 * about me?".
 *
 * 🎓 Why edit and delete are mandatory and not features: an app that learns by observing
 * and doesn't let you unlearn is surveillance. The delete button is what turns observation
 * into consent.
 */
class LearnedActivity : Activity() {

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
        col.addView(kicker("Learned"))
        col.addView(h2("What I know about you"))
        col.addView(explicacao("Each rule was born from a correction of yours. Edit or delete anytime."))

        val lista = Store.aprendidos(this).sortedByDescending { it.quandoMs }
        if (lista.isEmpty()) {
            val vazio = cartao()
            vazio.addView(
                explicacao(
                    "Nothing yet. The app learns when you correct a field " +
                        "or fill in one it left open."
                )
            )
            col.addView(vazio)
            return
        }

        val resumo = cartao()
        resumo.addView(placar("${lista.size}", if (lista.size == 1) "active rule" else "active rules"))
        col.addView(resumo)

        lista.forEach { a ->
            val c = cartao()
            val origem = if (a.origem == "corrigiu") "you corrected it" else "you filled in what was missing"
            val quando = DateFormat.format("dd/MM HH:mm", a.quandoMs)
            c.addView(itemComPonto(a.rotulo, "\"${a.valor}\"", R.color.primaria, "$origem · $quando"))
            val acoes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            acoes.addView(
                botaoSecundario("Edit") { editar(a) }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            acoes.addView(
                botaoSecundario("Delete") {
                    Store.removerAprendido(this, a.rotulo, a.quandoMs)
                    montar()
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            c.addView(acoes)
            col.addView(c)
        }
    }

    private fun editar(a: Learned) {
        val caixa = EditText(this).apply { setText(a.valor) }
        AlertDialog.Builder(this)
            .setTitle(a.rotulo)
            .setView(caixa)
            .setPositiveButton("Save") { _, _ ->
                Store.editarAprendido(this, a.rotulo, a.quandoMs, caixa.text.toString())
                montar()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
