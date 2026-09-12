package com.mygoll.funform

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
import com.mygoll.funform.Ui.botaoPrimario
import com.mygoll.funform.Ui.botaoSecundario
import com.mygoll.funform.Ui.cartao
import com.mygoll.funform.Ui.coluna
import com.mygoll.funform.Ui.dp
import com.mygoll.funform.Ui.explicacao
import com.mygoll.funform.Ui.h2
import com.mygoll.funform.Ui.kicker
import com.mygoll.funform.Ui.linhaNav
import com.mygoll.funform.Ui.rotuloCampo
import com.mygoll.funform.Ui.selo

/**
 * A porta de entrada, no desenho aprovado por ele em 12/09. Responde três perguntas NA
 * ORDEM em que a pessoa faz: o agente está pronto? meu perfil aguenta? como eu começo?
 *
 * 🎓 A ordem é o desenho. Antes esta tela abria com diagnóstico de build e crash, que é
 * informação do DESENVOLVEDOR. Quem abre o app quer saber se dá pra usar agora.
 */
class MainActivity : Activity() {

    private lateinit var caixaPerfil: EditText
    private lateinit var estadoServico: TextView
    private lateinit var estadoPerfil: TextView
    private lateinit var textoBuild: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val (rolo, col) = coluna()

        col.addView(kicker("Preenche"))
        col.addView(h2("O agente que não inventa sobre você"))
        col.addView(
            explicacao(
                "Lê os campos da tela e escreve neles o que você já contou. " +
                    "Field de senha nunca. Nada sai do aparelho."
            )
        )

        // 1 · ESTÁ PRONTO?
        val cartaoEstado = cartao()
        cartaoEstado.addView(rotuloCampo("Estado do agente"))
        estadoServico = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(4), 0, 0)
        }
        cartaoEstado.addView(estadoServico)
        cartaoEstado.addView(
            botaoSecundario("Abrir configurações de acessibilidade") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )
        col.addView(cartaoEstado)

        // 2 · MEU PERFIL AGUENTA?
        val cartaoPerfil = cartao()
        cartaoPerfil.addView(rotuloCampo("Seu perfil"))
        estadoPerfil = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(4), 0, 0)
        }
        cartaoPerfil.addView(estadoPerfil)
        cartaoPerfil.addView(
            selo("Fica só no aparelho. O arquivo do currículo não é guardado.")
        )
        cartaoPerfil.addView(
            botaoPrimario("Carregar de um arquivo") {
                startActivity(Intent(this, ProfileFileActivity::class.java))
            }
        )
        cartaoPerfil.addView(
            explicacao(
                "Ou digite: uma linha por dado, no formato chave: valor. " +
                    "Ex.: nome: Maria da Graça"
            )
        )
        caixaPerfil = EditText(this).apply {
            minHeight = dp(180)
            textSize = 13f
            gravity = android.view.Gravity.TOP
        }
        cartaoPerfil.addView(caixaPerfil)
        cartaoPerfil.addView(botaoSecundario("Salvar o que está escrito") { salvarPerfil(avisar = true) })
        cartaoPerfil.addView(botaoSecundario("Apagar tudo do aparelho") { confirmarApagar() })
        col.addView(cartaoPerfil)

        // 3 · O QUE MAIS EXISTE
        val cartaoNav = cartao()
        cartaoNav.addView(
            linhaNav("Receipt", "o que foi preenchido e o que ficou em branco") {
                startActivity(Intent(this, ReceiptActivity::class.java))
            }
        )
        cartaoNav.addView(
            linhaNav("Aprendidos", "as regras que nasceram das suas correções") {
                startActivity(Intent(this, LearnedActivity::class.java))
            }
        )
        cartaoNav.addView(
            linhaNav("Diagnóstico", "as varreduras, para quando algo não funcionar") {
                startActivity(Intent(this, DiagnosticsActivity::class.java))
            }
        )
        col.addView(cartaoNav)

        // build e crash ficam no RODAPÉ: é informação de manutenção, não de uso
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
        // recarrega do disco: a tela de arquivo pode ter mesclado perfil novo enquanto
        // esta caixa segurava texto velho, e o onPause salvaria o velho por cima
        val texto = Store.perfilTexto(this)
        caixaPerfil.setText(texto)

        val ligado = FunFormService.ativo
        estadoServico.text =
            if (ligado) "Pronto. Use o botão de acessibilidade na tela do formulário."
            else "Desligado. Ative nas configurações e volte."
        estadoServico.setTextColor(
            resources.getColor(if (ligado) R.color.sucesso else R.color.aviso, null)
        )

        val itens = texto.lines().count { it.contains(':') && it.substringBefore(':').isNotBlank() }
        estadoPerfil.text =
            if (itens == 0) "Vazio. Sem perfil o agente não tem o que escrever."
            else "$itens itens. É daqui que sai tudo que ele preenche."
        estadoPerfil.setTextColor(
            resources.getColor(if (itens == 0) R.color.aviso else R.color.texto, null)
        )

        val crash = Store.ultimoCrash(this)
        textoBuild.text = buildString {
            append("build ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            append(if (crash == null) "último crash: nenhum" else "último crash:\n$crash")
        }
    }

    override fun onPause() {
        salvarPerfil(avisar = false)
        super.onPause()
    }

    private fun salvarPerfil(avisar: Boolean) {
        Store.salvarPerfilTexto(this, caixaPerfil.text.toString())
        if (avisar) {
            android.widget.Toast.makeText(this, "Profile salvo no aparelho.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmarApagar() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Apagar perfil")
            .setMessage("Apaga de verdade: o perfil, o que o app aprendeu com você e o último recibo. Não tem desfazer.")
            .setPositiveButton("Apagar") { _, _ ->
                Store.apagarPerfil(this)
                caixaPerfil.setText("")
                android.widget.Toast.makeText(this, "Apagado do aparelho.", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
