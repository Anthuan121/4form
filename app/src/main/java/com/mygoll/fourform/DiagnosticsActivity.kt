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
 * The screen that pulls the log off the device (brief 242, part C. His request: "bring
 * that back to us so we can test it here... and you're not flying blind"). One tap on
 * Share sends the .json through any app, and the file ⛔ contains no written value and
 * nothing about passwords: it's label, level, and decision, nothing about the person.
 *
 * 🎓 It's the ONLY deliberately raw screen, and that's a design decision, not an oversight:
 * it's for the developer, not the user. Dressing up a log gets in the way of reading it.
 */
class DiagnosticsActivity : Activity() {

    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // the one raw screen, per the approved design: no dreamy gradient here,
        // "nobody is dreaming on this screen, they're debugging"
        val (rolo, c) = coluna(onirico = false)
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
