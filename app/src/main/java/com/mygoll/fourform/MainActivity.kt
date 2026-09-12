package com.mygoll.fourform

import com.mygoll.fourform.scan.Learned
import com.mygoll.fourform.scan.Entrada
import com.mygoll.fourform.scan.Fontes
import com.mygoll.fourform.scan.Matcher
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.mygoll.fourform.Ui.botaoPrimario
import com.mygoll.fourform.Ui.botaoSecundario
import com.mygoll.fourform.Ui.cartao
import com.mygoll.fourform.Ui.coluna
import com.mygoll.fourform.Ui.dp
import com.mygoll.fourform.Ui.explicacao
import com.mygoll.fourform.Ui.h2
import com.mygoll.fourform.Ui.kicker
import com.mygoll.fourform.Ui.linhaNav
import com.mygoll.fourform.Ui.pilares
import com.mygoll.fourform.Ui.rotuloCampo
import com.mygoll.fourform.Ui.selo

/**
 * The entry point, in the design approved by him on 09/12. Answers three questions IN
 * THE ORDER a person asks them: is the agent ready? does my profile hold up? how do I start?
 *
 * 🎓 The order is the design. This screen used to open with build diagnostics and crash
 * info, which is DEVELOPER information. Whoever opens the app wants to know if they can
 * use it right now.
 */
class MainActivity : Activity() {

    private lateinit var caixaPerfil: EditText
    private lateinit var estadoServico: TextView
    private lateinit var estadoPerfil: TextView
    private lateinit var pilaresPerfil: LinearLayout
    private lateinit var textoBuild: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val (rolo, col) = coluna()

        col.addView(kicker("4Form"))
        col.addView(h2("The agent that doesn't make things up about you"))
        col.addView(
            explicacao(
                "Reads the fields on screen and writes in them what you've already told it. " +
                    "Password fields, never. Nothing leaves your device."
            )
        )

        // 1 · IS IT READY?
        val cartaoEstado = cartao()
        cartaoEstado.addView(rotuloCampo("Agent status"))
        estadoServico = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(4), 0, 0)
        }
        cartaoEstado.addView(estadoServico)
        cartaoEstado.addView(
            botaoSecundario("Open accessibility settings") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )
        col.addView(cartaoEstado)

        // 2 · DOES MY PROFILE HOLD UP?
        val cartaoPerfil = cartao()
        cartaoPerfil.addView(rotuloCampo("Your profile"))
        estadoPerfil = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(4), 0, 0)
        }
        cartaoPerfil.addView(estadoPerfil)
        // Preenchido/limpo a cada onResume: vazio some da tela em vez de virar 4 colunas
        // zeradas (ordem dele: "gráfico de nada é pior que texto").
        pilaresPerfil = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cartaoPerfil.addView(pilaresPerfil)
        cartaoPerfil.addView(
            selo("Stays only on your device. The resume file itself isn't kept.")
        )
        cartaoPerfil.addView(
            botaoPrimario("Load from a file") {
                startActivity(Intent(this, ProfileFileActivity::class.java))
            }
        )
        cartaoPerfil.addView(
            explicacao(
                "Or type it in: one line per fact, in the format key: value. " +
                    "Ex.: name: Maria da Graça"
            )
        )
        caixaPerfil = EditText(this).apply {
            minHeight = dp(180)
            textSize = 13f
            gravity = android.view.Gravity.TOP
        }
        cartaoPerfil.addView(caixaPerfil)
        cartaoPerfil.addView(botaoSecundario("Save what's written") { salvarPerfil(avisar = true) })
        cartaoPerfil.addView(botaoSecundario("Erase everything from this device") { confirmarApagar() })
        col.addView(cartaoPerfil)

        // 3 · WHAT ELSE EXISTS
        val cartaoNav = cartao()
        cartaoNav.addView(
            linhaNav("Receipt", "what got filled and what stayed blank") {
                startActivity(Intent(this, ReceiptActivity::class.java))
            }
        )
        cartaoNav.addView(
            linhaNav("Learned", "the rules born from your corrections") {
                startActivity(Intent(this, LearnedActivity::class.java))
            }
        )
        cartaoNav.addView(
            linhaNav("Sources", "which files fed your profile, and when") {
                startActivity(Intent(this, SourcesActivity::class.java))
            }
        )
        cartaoNav.addView(
            linhaNav("Diagnostics", "the scans, for when something doesn't work") {
                startActivity(Intent(this, DiagnosticsActivity::class.java))
            }
        )
        col.addView(cartaoNav)

        // build and crash stay in the FOOTER: it's maintenance information, not usage info
        textoBuild = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(resources.getColor(R.color.texto_secundario, null))
            setPadding(0, dp(20), 0, 0)
        }
        col.addView(textoBuild)

        setContentView(rolo)
    }

    override fun onResume() {
        super.onResume()
        // reload from disk: the file screen may have merged a new profile while this
        // box was holding old text, and onPause would save the old one right over it
        val texto = Store.perfilTexto(this)
        caixaPerfil.setText(texto)

        val ligado = FourFormService.ativo
        estadoServico.text =
            if (ligado) "Ready. Use the accessibility button on the form's screen."
            else "Off. Turn it on in settings and come back."
        estadoServico.setTextColor(
            resources.getColor(if (ligado) R.color.sucesso else R.color.aviso, null)
        )

        val itens = texto.lines().count { it.contains(':') && it.substringBefore(':').isNotBlank() }
        estadoPerfil.text =
            if (itens == 0) "Empty. Without a profile the agent has nothing to write."
            else "$itens items. This is where everything it fills comes from."
        estadoPerfil.setTextColor(
            resources.getColor(if (itens == 0) R.color.aviso else R.color.texto, null)
        )

        pilaresPerfil.removeAllViews()
        PerfilCompletude.calcular(texto)?.let { p ->
            pilaresPerfil.addView(pilares(p.itens))
            pilaresPerfil.addView(selo("${p.lacunas} known gaps", R.color.aviso))
        }

        val crash = Store.ultimoCrash(this)
        textoBuild.text = buildString {
            append("build ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            append(if (crash == null) "last crash: none" else "last crash:\n$crash")
        }
    }

    override fun onPause() {
        salvarPerfil(avisar = false)
        super.onPause()
    }

    /**
     * The free-text box is its own source, "typed by hand" (brief 258). Only lines whose
     * value actually changed become a new entry in the log: otherwise reopening the app and
     * backgrounding it again would pile up one identical entry per pause.
     * ponytail: a line the person deletes from the box isn't removed from the log (it can
     * resurface if a source gets removed later and the key has no newer entry); fixing that
     * needs a diff against the PREVIOUS box content, out of scope for this brief.
     */
    private fun salvarPerfil(avisar: Boolean) {
        val texto = caixaPerfil.text.toString()
        val pares = texto.lines().mapNotNull { linha ->
            val i = linha.indexOf(':')
            if (i <= 0) return@mapNotNull null
            val chave = linha.substring(0, i).trim()
            val valor = linha.substring(i + 1).trim()
            if (chave.isBlank() || valor.isBlank()) null else chave to valor
        }
        val efetivos = Fontes.efetivo(Store.fontes(this)).associateBy { Matcher.normalizar(it.chave) }
        val mudados = pares.filter { (chave, valor) -> efetivos[Matcher.normalizar(chave)]?.valor != valor }
        if (mudados.isEmpty()) {
            Store.salvarPerfilTexto(this, texto)
        } else {
            val agora = System.currentTimeMillis()
            Store.adicionarFontes(this, mudados.map { (chave, valor) -> Entrada(chave, valor, "typed by hand", agora) })
        }
        if (avisar) {
            android.widget.Toast.makeText(this, "Profile saved on this device.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmarApagar() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Erase profile")
            .setMessage("This really erases: the profile, what the app learned about you, and the last receipt. No undo.")
            .setPositiveButton("Erase") { _, _ ->
                Store.apagarPerfil(this)
                caixaPerfil.setText("")
                android.widget.Toast.makeText(this, "Erased from this device.", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
