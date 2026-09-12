package com.mygoll.funform

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import com.mygoll.funform.Ui.botaoSecundario
import com.mygoll.funform.Ui.cartao
import com.mygoll.funform.Ui.coluna
import com.mygoll.funform.Ui.explicacao
import com.mygoll.funform.Ui.h2
import com.mygoll.funform.Ui.itemComPonto
import com.mygoll.funform.Ui.kicker
import com.mygoll.funform.Ui.linha
import com.mygoll.funform.Ui.rotuloCampo
import java.io.File

/**
 * A tela que tira o log do aparelho (brief 242, parte C — pedido dele: "traz isso pra gente
 * testar aqui... e você não ficar cego"). Um toque em Compartilhar manda o .json por
 * qualquer app, e o arquivo ⛔ não contém valor escrito nem nada de senha: é rótulo, nível
 * e decisão, nada da pessoa.
 *
 * 🎓 É a ÚNICA tela deliberadamente crua, e isso é decisão de desenho, não descuido: ela é
 * do desenvolvedor, não do usuário. Enfeitar log é atrapalhar quem lê log.
 */
class DiagnosticsActivity : Activity() {

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
        col.addView(kicker("Diagnóstico"))
        col.addView(h2("As varreduras"))
        col.addView(
            explicacao(
                "Um arquivo por varredura: que campos vi, que nome dei a cada um e por quê. " +
                    "Sem os valores escritos, sem nada de senha."
            )
        )

        val arquivos = Store.listarDiagnosticos(this)
        if (arquivos.isEmpty()) {
            val vazio = cartao()
            vazio.addView(explicacao("Nenhuma varredura registrada ainda."))
            col.addView(vazio)
            return
        }

        val recente = cartao()
        recente.addView(rotuloCampo("Mais recente"))
        recente.addView(linha(arquivos.first().readText(), mono = true))
        recente.addView(botaoSecundario("Compartilhar este") { compartilhar(arquivos.first()) })
        col.addView(recente)

        val todos = cartao()
        todos.addView(rotuloCampo("Todas as ${arquivos.size}"))
        arquivos.forEach { f ->
            todos.addView(itemComPonto(f.name, "${f.length()} bytes", R.color.contorno))
            todos.addView(botaoSecundario("Compartilhar") { compartilhar(f) })
        }
        todos.addView(
            botaoSecundario("Apagar todos os diagnósticos") {
                Store.apagarDiagnosticos(this)
                montar()
            }
        )
        col.addView(todos)
    }

    private fun compartilhar(f: File) {
        val envio = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, FileProvider.uriPara(f))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(envio, "Enviar diagnóstico"))
    }
}
