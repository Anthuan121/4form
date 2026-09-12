package com.mygoll.fourform

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import com.mygoll.fourform.Ui.botaoSecundario
import com.mygoll.fourform.Ui.cartao
import com.mygoll.fourform.Ui.coluna
import com.mygoll.fourform.Ui.explicacao
import com.mygoll.fourform.Ui.h2
import com.mygoll.fourform.Ui.itemComPonto
import com.mygoll.fourform.Ui.kicker
import com.mygoll.fourform.Ui.linha
import com.mygoll.fourform.Ui.rotuloCampo
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
        col.addView(kicker("Diagnostics"))
        col.addView(h2("The scans"))
        col.addView(
            explicacao(
                "One file per scan: what fields I saw, what name I gave each one, and why. " +
                    "No written values, nothing about passwords."
            )
        )

        val arquivos = Store.listarDiagnosticos(this)
        if (arquivos.isEmpty()) {
            val vazio = cartao()
            vazio.addView(explicacao("No scan recorded yet."))
            col.addView(vazio)
            return
        }

        val recente = cartao()
        recente.addView(rotuloCampo("Most recent"))
        recente.addView(linha(arquivos.first().readText(), mono = true))
        recente.addView(botaoSecundario("Share this one") { compartilhar(arquivos.first()) })
        col.addView(recente)

        val todos = cartao()
        todos.addView(rotuloCampo("All ${arquivos.size}"))
        arquivos.forEach { f ->
            todos.addView(itemComPonto(f.name, "${f.length()} bytes", R.color.contorno))
            todos.addView(botaoSecundario("Share") { compartilhar(f) })
        }
        todos.addView(
            botaoSecundario("Delete all diagnostics") {
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
        startActivity(Intent.createChooser(envio, "Send diagnostic"))
    }
}
