package com.mygoll.funform

import android.app.Activity
import android.os.Bundle
import android.text.format.DateFormat
import android.widget.LinearLayout
import com.mygoll.funform.Ui.botaoSecundario
import com.mygoll.funform.Ui.cartao
import com.mygoll.funform.Ui.coluna
import com.mygoll.funform.Ui.explicacao
import com.mygoll.funform.Ui.h2
import com.mygoll.funform.Ui.itemComPonto
import com.mygoll.funform.Ui.kicker
import com.mygoll.funform.Ui.placar
import com.mygoll.funform.Ui.rotuloCampo
import com.mygoll.funform.Ui.selo
import com.mygoll.funform.core.Learned

/**
 * O RECIBO (ideia dele, 09/09): a sessão acabou, o app mostra o que fez e o que aprendeu,
 * com DESFAZER por item. Aprender sem poder desaprender não é controle.
 *
 * Desenho de 12/09. A gramática de cor é a mesma das outras telas e é o miolo do produto:
 * sálvia = sustentado pelo seu perfil · âmbar = LACUNA DECLARADA, ⛔ não erro. A categoria 4
 * da régua dele ("o app não responde e avisa") precisa parecer virtude, porque é o que
 * separa este app dos concorrentes, que chutariam.
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
        col.addView(h2("O que eu fiz na última sessão"))

        val r = Store.lerRecibo(this)
        if (r == null) {
            col.addView(explicacao("Ainda não há recibo: nenhuma sessão preencheu ou aprendeu algo."))
            return
        }

        val total = r.preenchidos.size + r.abertos.size
        val resumo = cartao()
        resumo.addView(placar("${r.preenchidos.size} de $total", "campos preenchidos"))
        if (r.abertos.isNotEmpty()) {
            resumo.addView(
                explicacao("${r.abertos.size} ficaram em branco porque seu perfil não respondia.")
            )
        }
        resumo.addView(selo("Nada inventado nesta candidatura."))
        col.addView(resumo)

        if (r.preenchidos.isNotEmpty()) {
            val c = cartao()
            c.addView(rotuloCampo("Preenchi"))
            r.preenchidos.forEach {
                c.addView(itemComPonto(it.rotulo, "\"${it.valor}\"", R.color.sucesso, it.fonte))
            }
            col.addView(c)
        }

        if (r.abertos.isNotEmpty()) {
            val c = cartao()
            c.addView(rotuloCampo("Deixei em branco, e por quê"))
            r.abertos.forEach {
                c.addView(itemComPonto(it.rotulo ?: "campo sem nome", it.motivo, R.color.aviso))
            }
            col.addView(c)
        }

        if (r.aprendidos.isNotEmpty()) {
            val c = cartao()
            c.addView(rotuloCampo("Aprendi agora"))
            c.addView(explicacao("Veio das suas correções. Vale a partir da próxima vez."))
            r.aprendidos.forEach { a -> c.addView(blocoAprendido(a)) }
            col.addView(c)
        }
    }

    private fun blocoAprendido(a: Learned): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val origem = if (a.origem == "corrigiu") "você corrigiu" else "você preencheu o que faltava"
        val quando = DateFormat.format("dd/MM HH:mm", a.quandoMs)
        val vivo = Store.temAprendido(this, a.rotulo, a.quandoMs)
        row.addView(
            itemComPonto(
                a.rotulo,
                if (vivo) "\"${a.valor}\"" else "desfeito",
                if (vivo) R.color.primaria else R.color.texto_secundario,
                "$origem · $quando",
            )
        )
        if (vivo) {
            row.addView(
                botaoSecundario("Desfazer este aprendizado") {
                    Store.removerAprendido(this, a.rotulo, a.quandoMs)
                    montar()
                }
            )
        }
        return row
    }
}
