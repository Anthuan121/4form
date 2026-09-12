package com.mygoll.funform

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.EditText
import android.widget.LinearLayout
import com.mygoll.funform.Ui.botaoSecundario
import com.mygoll.funform.Ui.cartao
import com.mygoll.funform.Ui.coluna
import com.mygoll.funform.Ui.explicacao
import com.mygoll.funform.Ui.h2
import com.mygoll.funform.Ui.itemComPonto
import com.mygoll.funform.Ui.kicker
import com.mygoll.funform.Ui.placar
import com.mygoll.funform.core.Learned

/**
 * Onde o perfil aprendido VIVE (pedido dele, 09/09): tudo que o app sabe por observação,
 * com editar e apagar. É a tela que responde "o que esse app sabe sobre mim?".
 *
 * 🎓 Por que editar e apagar são obrigatórios e não features: um app que aprende observando
 * e não deixa desaprender é vigilância. O botão de apagar é o que transforma observação em
 * acordo.
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
        col.addView(kicker("Aprendidos"))
        col.addView(h2("O que eu sei sobre você"))
        col.addView(explicacao("Cada regra nasceu de uma correção sua. Edite ou apague quando quiser."))

        val lista = Store.aprendidos(this).sortedByDescending { it.quandoMs }
        if (lista.isEmpty()) {
            val vazio = cartao()
            vazio.addView(
                explicacao(
                    "Nada ainda. O app aprende quando você corrige um campo " +
                        "ou preenche um que ele deixou aberto."
                )
            )
            col.addView(vazio)
            return
        }

        val resumo = cartao()
        resumo.addView(placar("${lista.size}", if (lista.size == 1) "regra ativa" else "regras ativas"))
        col.addView(resumo)

        lista.forEach { a ->
            val c = cartao()
            val origem = if (a.origem == "corrigiu") "você corrigiu" else "você preencheu o que faltava"
            val quando = DateFormat.format("dd/MM HH:mm", a.quandoMs)
            c.addView(itemComPonto(a.rotulo, "\"${a.valor}\"", R.color.primaria, "$origem · $quando"))
            val acoes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            acoes.addView(
                botaoSecundario("Editar") { editar(a) }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            )
            acoes.addView(
                botaoSecundario("Apagar") {
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
            .setPositiveButton("Salvar") { _, _ ->
                Store.editarAprendido(this, a.rotulo, a.quandoMs, caixa.text.toString())
                montar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
