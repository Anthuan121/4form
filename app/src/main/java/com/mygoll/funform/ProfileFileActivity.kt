package com.mygoll.funform

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.TextView
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.mygoll.funform.Ui.ancora
import com.mygoll.funform.Ui.botao
import com.mygoll.funform.Ui.botaoPrimario
import com.mygoll.funform.Ui.cartao
import com.mygoll.funform.Ui.explicacao
import com.mygoll.funform.Ui.h2
import com.mygoll.funform.Ui.kicker
import com.mygoll.funform.Ui.rotuloCampo
import com.mygoll.funform.Ui.coluna
import com.mygoll.funform.Ui.dp
import com.mygoll.funform.Ui.linha
import com.mygoll.funform.Ui.secao
import com.mygoll.funform.Ui.titulo
import com.mygoll.funform.core.Extractor
import com.mygoll.funform.core.Llm
import com.mygoll.funform.core.Merger

/**
 * A entrada do perfil por arquivo (.md/.txt) e por compartilhar, com a tela de confirmação
 * OBRIGATÓRIA: o app extrai, MOSTRA o que achou com a linha de origem do lado, a pessoa
 * edita, apaga e confirma; só o botão "Salvar perfil" grava. Sem confirmação, nada é salvo.
 * O arquivo original não fica guardado: é lido, extraído e descartado da memória.
 * PDF ficou fora desta versão: extrair texto de PDF exige biblioteca de 3 MB ou mais e o
 * app abre mão do recurso pra continuar mínimo (medição no brief 243).
 */
class ProfileFileActivity : Activity() {

    private companion object {
        const val PEDIDO_ARQUIVO = 1
        const val LIMITE_BYTES = 512 * 1024 // currículo é KB; acima disso não é perfil
    }

    private lateinit var col: LinearLayout
    private lateinit var caixaDePares: LinearLayout
    private val vivos = mutableListOf<Par>()

    private class Par(val bloco: LinearLayout, val chave: EditText, val valor: EditText) {
        var apagado = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (rolo, c) = coluna()
        col = c
        setContentView(rolo)

        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let {
                montarConfirmacao(it)
                return
            }
            val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (uri == null) {
                mostrarErro("Não veio nada legível no compartilhar.")
            } else {
                val (texto, erro) = lerUri(uri)
                if (texto == null) mostrarErro(erro) else montarConfirmacao(texto)
            }
        } else {
            // veio da tela principal: abre o seletor do sistema na hora. type "*/*" de
            // propósito: .md chega como text/markdown, text/plain ou octet-stream
            // dependendo do app de origem, e filtrar por MIME cortaria o caso principal.
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(i, PEDIDO_ARQUIVO)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PEDIDO_ARQUIVO) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            finish() // a pessoa desistiu no seletor; nada a fazer
            return
        }
        val (texto, erro) = lerUri(uri)
        if (texto == null) mostrarErro(erro) else montarConfirmacao(texto)
    }

    // ---- leitura ----

    /** Lê o conteúdo pra memória. Devolve (texto, "") no sucesso ou (null, motivo). */
    /** Só o NOME, para a tela dizer o que leu. ⛔ O arquivo em si nunca é guardado. */
    private var nomeDoArquivo: String? = null

    private fun lerUri(uri: Uri): Pair<String?, String> {
        nomeDoArquivo = runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null to "Não consegui abrir esse arquivo."
        if (bytes.size > LIMITE_BYTES) {
            return null to "Arquivo grande demais (${bytes.size / 1024} KB; o limite é 512 KB). Mande só a parte com os seus dados."
        }
        if (bytes.size >= 4 && bytes.decodeToString(0, 4) == "%PDF") {
            return null to (
                "PDF ficou fora desta versão: ler texto de PDF exigiria uma biblioteca de 3 MB ou mais " +
                    "dentro do app. Peça o currículo em .md ou .txt (uma IA converte na hora), " +
                    "ou compartilhe o texto direto pro Preenche."
                )
        }
        if (bytes.any { it == 0.toByte() }) {
            return null to "Isso não parece texto (.md ou .txt). Não sei ler esse formato."
        }
        return bytes.decodeToString() to ""
    }

    // ---- a tela de confirmação ----

    private fun montarConfirmacao(texto: String) {
        val extracao = Extractor.extrair(texto)
        col.removeAllViews()
        vivos.clear()

        // Estrutura do desenho aprovado por ele em 12/09 (Fable, tela 2). O título fala na
        // PRIMEIRA PESSOA de propósito: esta é a tela onde a confiança nasce ou morre, porque
        // é onde o app mostra o que entendeu ANTES de usar em nome da pessoa.
        col.addView(kicker("Profile · currículo"))
        col.addView(h2("Foi isto que eu entendi"))
        col.addView(explicacao("Confira antes de eu usar. Cada item mostra de onde no arquivo ele veio."))

        val cartaoArquivo = cartao()
        cartaoArquivo.addView(rotuloCampo("Arquivo lido"))
        cartaoArquivo.addView(
            TextView(this).apply {
                text = nomeDoArquivo ?: "arquivo sem nome"
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(2), 0, 0)
            }
        )
        cartaoArquivo.addView(
            TextView(this).apply {
                text = "${extracao.pares.size} itens extraídos · o arquivo em si ⛔ não fica guardado no app"
                textSize = 12f
                setTextColor(resources.getColor(R.color.sucesso, null))
                setPadding(0, dp(4), 0, 0)
            }
        )
        col.addView(cartaoArquivo)

        val cartaoPares = cartao()
        cartaoPares.addView(
            TextView(this).apply {
                text = "Confirme os itens"
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        )
        cartaoPares.addView(explicacao("Toque num item pra corrigir. O que você corrigir aqui vira regra em Aprendidos."))
        caixaDePares = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cartaoPares.addView(caixaDePares)
        col.addView(cartaoPares)
        if (extracao.pares.isEmpty()) {
            caixaDePares.addView(
                linha("Nada com forma reconhecível: email, telefone, linkedin, nome no topo ou linhas \"chave: valor\".")
            )
        }
        extracao.pares.forEach { adicionarPar(it.chave, it.valor, "linha ${it.linha}: ${it.linhaTexto.trim()}") }

        if (extracao.naoEntendi.isNotEmpty()) {
            col.addView(secao("Não entendi estas linhas (${extracao.naoEntendi.size})"))
            col.addView(linha("Não viram campo de propósito: chutar aqui seria inventar sobre você. Toque numa linha pra virar par."))
            val caixaIgnoradas = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = android.view.View.GONE
            }
            val alternar = botao("Mostrar as ${extracao.naoEntendi.size} linhas") {}
            alternar.setOnClickListener {
                val aberta = caixaIgnoradas.visibility == android.view.View.VISIBLE
                caixaIgnoradas.visibility = if (aberta) android.view.View.GONE else android.view.View.VISIBLE
                alternar.text = if (aberta) "Mostrar as ${extracao.naoEntendi.size} linhas" else "Ocultar"
            }
            // Degrau opcional (10/09): a IA lê essas linhas e propõe pares. ⛔ Não substitui
            // a régua do Extractor — ela continua sendo a rede quando a IA está fora do ar.
            // Tudo cai nesta MESMA tela: nada é salvo sem o botão Salvar.
            col.addView(botaoIa(extracao.naoEntendi))
            col.addView(alternar)
            col.addView(caixaIgnoradas)
            extracao.naoEntendi.forEach { ig ->
                caixaIgnoradas.addView(
                    botao("L${ig.linha} · ${ig.texto.trim().take(80)}") {
                        adicionarPar("", ig.texto.trim(), "linha ${ig.linha}, você mesmo escolheu")
                        Toast.makeText(this, "Virou par lá em cima; escreva a chave.", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        col.addView(explicacao("Entra por cima do perfil que já existe, chave a chave. O que o app aprendeu com as suas correções continua valendo."))
        col.addView(botaoPrimario("Confirmar ${extracao.pares.size} itens") { salvar() })
        col.addView(botao("Cancelar (não salva nada)") { finish() })
    }

    /**
     * "Deixar a IA ler". Thread própria (rede na main thread lança
     * NetworkOnMainThreadException e trava a tela), botão desabilitado enquanto roda para
     * não disparar duas vezes, e falha em silêncio útil: sem rede ou resposta ilegível, a
     * tela fica EXATAMENTE como está e as linhas seguem promovíveis a dedo.
     */
    private fun botaoIa(naoEntendi: List<com.mygoll.funform.core.LinhaNaoEntendida>) =
        botao("Deixar a IA ler estas ${naoEntendi.size} linhas") {}.apply {
            setOnClickListener {
                isEnabled = false
                text = "Lendo..."
                Thread {
                    val resultado = runCatching { LlmBridge.chamar(Llm.corpoPerfil(naoEntendi)) }
                    val propostos = Llm.avaliarPerfil(resultado, naoEntendi)
                    runOnUiThread {
                        isEnabled = true
                        if (propostos.isEmpty()) {
                            text = "A IA não achou nada novo. Tentar de novo"
                            Toast.makeText(
                                this@ProfileFileActivity,
                                "Nada veio da IA. As linhas continuam aí pra você promover.",
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            text = "A IA propôs ${propostos.size}. Confira lá em cima"
                            propostos.forEach {
                                adicionarPar(it.chave, it.valor, "linha ${it.linha} · proposto pela IA: ${it.origem.take(60)}")
                            }
                            Toast.makeText(
                                this@ProfileFileActivity,
                                "${propostos.size} propostas adicionadas. Nada foi salvo ainda.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }.start()
            }
        }

    private fun adicionarPar(chave: String, valor: String, origem: String) {
        val bloco = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val caixaChave = EditText(this).apply {
            hint = "chave (ex.: email)"
            setText(chave)
            textSize = 11f
            setTextColor(resources.getColor(R.color.texto_secundario, null))
            background = null // o contêiner já separa os itens; borda por campo polui
            setPadding(0, 0, 0, 0)
        }
        val caixaValor = EditText(this).apply {
            hint = "valor"
            setText(valor)
            textSize = 15f
            background = null
            setPadding(0, dp(1), 0, 0)
        }
        bloco.addView(caixaChave)
        bloco.addView(caixaValor)
        bloco.addView(ancora(origem))
        val par = Par(bloco, caixaChave, caixaValor)
        bloco.addView(
            botao("Apagar este") {
                par.apagado = true
                caixaDePares.removeView(bloco)
            }
        )
        caixaDePares.addView(bloco)
        vivos.add(par)
    }

    /** O ÚNICO caminho do arquivo até o disco passa por este botão. */
    private fun salvar() {
        val confirmados = vivos
            .filterNot { it.apagado }
            .map { it.chave.text.toString().trim() to it.valor.text.toString().trim() }
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
        if (confirmados.isEmpty()) {
            Toast.makeText(this, "Nada pra salvar: todos os pares estão vazios ou apagados.", Toast.LENGTH_SHORT).show()
            return
        }
        Store.salvarPerfilTexto(this, Merger.mesclar(Store.perfilTexto(this), confirmados))
        Toast.makeText(this, "Profile salvo: ${confirmados.size} dados. Nada saiu do aparelho.", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun mostrarErro(msg: String) {
        col.removeAllViews()
        col.addView(titulo("Não deu"))
        col.addView(linha(msg))
        col.addView(botao("Escolher outro arquivo") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(i, PEDIDO_ARQUIVO)
        })
        col.addView(botao("Voltar") { finish() })
    }
}
