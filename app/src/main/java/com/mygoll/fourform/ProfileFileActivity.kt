package com.mygoll.fourform

import com.mygoll.fourform.scan.LinhaNaoEntendida
import com.mygoll.fourform.scan.Learned
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.TextView
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.mygoll.fourform.Ui.ancora
import com.mygoll.fourform.Ui.botao
import com.mygoll.fourform.Ui.botaoPrimario
import com.mygoll.fourform.Ui.cartao
import com.mygoll.fourform.Ui.explicacao
import com.mygoll.fourform.Ui.h2
import com.mygoll.fourform.Ui.kicker
import com.mygoll.fourform.Ui.rotuloCampo
import com.mygoll.fourform.Ui.coluna
import com.mygoll.fourform.Ui.dp
import com.mygoll.fourform.Ui.linha
import com.mygoll.fourform.Ui.secao
import com.mygoll.fourform.Ui.titulo
import com.mygoll.fourform.scan.Extractor
import com.mygoll.fourform.agent.Llm
import com.mygoll.fourform.scan.Entrada
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * Profile intake by file (.md/.txt/.pdf) and by share, with a MANDATORY confirmation
 * screen: the app extracts, SHOWS what it understood with the source line next to it, the
 * person edits, deletes and confirms; only the "Save profile" button writes to disk. No
 * confirmation, nothing is saved. The original file is never kept: it is read, extracted
 * and discarded from memory. PDF (brief 254) follows the same contract: pdfbox-android
 * extracts the text locally, off the main thread, and the text enters the SAME Extractor
 * that already reads .md/.txt.
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
                mostrarErro("Nothing readable came through the share.")
            } else {
                lerUri(uri, ::tratarLeitura)
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
        lerUri(uri, ::tratarLeitura)
    }

    private fun tratarLeitura(leitura: Leitura) {
        when (leitura) {
            is Leitura.Ok -> montarConfirmacao(leitura.texto)
            is Leitura.PdfSemTexto -> mostrarPdfSemTexto()
            is Leitura.Erro -> mostrarErro(leitura.msg)
        }
    }

    // ---- leitura ----

    /** Async result of reading a file: text ready, PDF with no selectable text (scanned as
     *  an image), or error. Async because extracting text from a PDF cannot run on the
     *  main thread (a 2 page resume would freeze the screen). */
    private sealed class Leitura {
        data class Ok(val texto: String) : Leitura()
        object PdfSemTexto : Leitura()
        data class Erro(val msg: String) : Leitura()
    }

    /** Só o NOME, para a tela dizer o que leu. ⛔ O arquivo em si nunca é guardado. */
    private var nomeDoArquivo: String? = null
    private var pdfBoxIniciado = false

    /** Reads the file content and calls `aoTerminar` with the result. For .md/.txt the
     *  callback fires synchronously (same thread); for PDF the extraction runs on its own
     *  thread and comes back through the UI handler, the same pattern already used by the
     *  "Let the AI read" button. */
    private fun lerUri(uri: Uri, aoTerminar: (Leitura) -> Unit) {
        nomeDoArquivo = runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return aoTerminar(Leitura.Erro("Couldn't open that file."))
        if (bytes.size > LIMITE_BYTES) {
            return aoTerminar(
                Leitura.Erro("File too big (${bytes.size / 1024} KB; the limit is 512 KB). Send just the part with your data.")
            )
        }
        if (bytes.size >= 4 && bytes.decodeToString(0, 4) == "%PDF") {
            Thread {
                val texto = runCatching { extrairTextoDoPdf(bytes) }.getOrElse { "" }
                runOnUiThread {
                    aoTerminar(if (texto.isBlank()) Leitura.PdfSemTexto else Leitura.Ok(texto))
                }
            }.start()
            return
        }
        if (bytes.any { it == 0.toByte() }) {
            return aoTerminar(Leitura.Erro("That doesn't look like text (.md, .txt or .pdf). I don't know how to read this format."))
        }
        aoTerminar(Leitura.Ok(bytes.decodeToString()))
    }

    /** Local text extraction from a PDF. ⛔ The PDF bytes do not survive past this
     *  function: they come in, become text, the ByteArray goes out of scope.
     *  `PDFBoxResourceLoader.init` loads the fallback fonts pdfbox-android needs and only
     *  needs to run once. */
    private fun extrairTextoDoPdf(bytes: ByteArray): String {
        if (!pdfBoxIniciado) {
            PDFBoxResourceLoader.init(applicationContext)
            pdfBoxIniciado = true
        }
        return PDDocument.load(bytes).use { PDFTextStripper().getText(it) }
    }

    // ---- a tela de confirmação ----

    private fun montarConfirmacao(texto: String) {
        val extracao = Extractor.extrair(texto)
        col.removeAllViews()
        vivos.clear()

        // Estrutura do desenho aprovado por ele em 12/09 (Fable, tela 2). O título fala na
        // PRIMEIRA PESSOA de propósito: esta é a tela onde a confiança nasce ou morre, porque
        // é onde o app mostra o que entendeu ANTES de usar em nome da pessoa.
        col.addView(kicker("Profile · resume"))
        col.addView(h2("Here's what I understood"))
        col.addView(explicacao("Check it before I use it. Each item shows where in the file it came from."))

        val cartaoArquivo = cartao()
        cartaoArquivo.addView(rotuloCampo("File read"))
        cartaoArquivo.addView(
            TextView(this).apply {
                text = nomeDoArquivo ?: "unnamed file"
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(2), 0, 0)
            }
        )
        cartaoArquivo.addView(
            TextView(this).apply {
                text = "${extracao.pares.size} items extracted · the file itself ⛔ isn't kept in the app"
                textSize = 12f
                setTextColor(resources.getColor(R.color.sucesso, null))
                setPadding(0, dp(4), 0, 0)
            }
        )
        col.addView(cartaoArquivo)

        val cartaoPares = cartao()
        cartaoPares.addView(
            TextView(this).apply {
                text = "Confirm the items"
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        )
        cartaoPares.addView(explicacao("Tap an item to correct it. Anything you correct here becomes a rule in Learned."))
        caixaDePares = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cartaoPares.addView(caixaDePares)
        col.addView(cartaoPares)
        if (extracao.pares.isEmpty()) {
            caixaDePares.addView(
                linha("Nothing with a recognizable shape: email, phone, LinkedIn, a name at the top, or \"key: value\" lines.")
            )
        }
        extracao.pares.forEach { adicionarPar(it.chave, it.valor, "line ${it.linha}: ${it.linhaTexto.trim()}") }

        if (extracao.naoEntendi.isNotEmpty()) {
            col.addView(secao("I didn't understand these lines (${extracao.naoEntendi.size})"))
            col.addView(linha("They didn't become a field on purpose: guessing here would be inventing about you. Tap a line to turn it into a pair."))
            val caixaIgnoradas = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = android.view.View.GONE
            }
            val alternar = botao("Show the ${extracao.naoEntendi.size} lines") {}
            alternar.setOnClickListener {
                val aberta = caixaIgnoradas.visibility == android.view.View.VISIBLE
                caixaIgnoradas.visibility = if (aberta) android.view.View.GONE else android.view.View.VISIBLE
                alternar.text = if (aberta) "Show the ${extracao.naoEntendi.size} lines" else "Hide"
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
                        adicionarPar("", ig.texto.trim(), "line ${ig.linha}, you chose this yourself")
                        Toast.makeText(this, "Turned into a pair up above; write in the key.", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }

        col.addView(explicacao("Goes on top of the profile you already have, key by key. What the app learned from your corrections still stands."))
        col.addView(botaoPrimario("Confirm ${extracao.pares.size} items") { salvar() })
        col.addView(botao("Cancel (saves nothing)") { finish() })
    }

    /**
     * "Deixar a IA ler". Thread própria (rede na main thread lança
     * NetworkOnMainThreadException e trava a tela), botão desabilitado enquanto roda para
     * não disparar duas vezes, e falha em silêncio útil: sem rede ou resposta ilegível, a
     * tela fica EXATAMENTE como está e as linhas seguem promovíveis a dedo.
     */
    private fun botaoIa(naoEntendi: List<com.mygoll.fourform.scan.LinhaNaoEntendida>) =
        botao("Let the AI read these ${naoEntendi.size} lines") {}.apply {
            setOnClickListener {
                isEnabled = false
                text = "Reading..."
                Thread {
                    val resultado = runCatching { LlmBridge.chamar(Llm.corpoPerfil(naoEntendi)) }
                    val propostos = Llm.avaliarPerfil(resultado, naoEntendi)
                    runOnUiThread {
                        isEnabled = true
                        if (propostos.isEmpty()) {
                            text = "The AI didn't find anything new. Try again"
                            Toast.makeText(
                                this@ProfileFileActivity,
                                "Nothing came back from the AI. The lines are still there for you to promote.",
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            text = "The AI proposed ${propostos.size}. Check them up above"
                            propostos.forEach {
                                adicionarPar(it.chave, it.valor, "line ${it.linha} · proposed by the AI: ${it.origem.take(60)}")
                            }
                            Toast.makeText(
                                this@ProfileFileActivity,
                                "${propostos.size} proposals added. Nothing has been saved yet.",
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
            hint = "key (e.g.: email)"
            setText(chave)
            textSize = 11f
            setTextColor(resources.getColor(R.color.texto_secundario, null))
            background = null // o contêiner já separa os itens; borda por campo polui
            setPadding(0, 0, 0, 0)
        }
        val caixaValor = EditText(this).apply {
            hint = "value"
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
            botao("Delete this") {
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
            Toast.makeText(this, "Nothing to save: all pairs are empty or deleted.", Toast.LENGTH_SHORT).show()
            return
        }
        val fonte = nomeDoArquivo ?: "typed by hand"
        val agora = System.currentTimeMillis()
        Store.adicionarFontes(this, confirmados.map { (chave, valor) -> Entrada(chave, valor, fonte, agora) })
        Toast.makeText(this, "Profile saved: ${confirmados.size} items. Nothing left this device.", Toast.LENGTH_LONG).show()
        finish()
    }

    /** PDF with NO selectable text at all: a resume scanned as an image. ⛔ Does not lock
     *  up, ⛔ is not a technical error: offers pasting the text by hand and goes through
     *  the SAME Extractor. */
    private fun mostrarPdfSemTexto() {
        col.removeAllViews()
        col.addView(titulo("This PDF has no text I can read"))
        col.addView(linha("It looks like a scan or a photo turned into a PDF, with no selectable text inside. Paste the resume text below instead."))
        val caixaTexto = EditText(this).apply {
            hint = "Paste your resume text here"
            minLines = 6
            gravity = android.view.Gravity.TOP
        }
        col.addView(caixaTexto)
        col.addView(
            botaoPrimario("Use this text") {
                val texto = caixaTexto.text.toString()
                if (texto.isBlank()) {
                    Toast.makeText(this, "Paste something first.", Toast.LENGTH_SHORT).show()
                } else {
                    montarConfirmacao(texto)
                }
            }
        )
        col.addView(botao("Choose another file") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(i, PEDIDO_ARQUIVO)
        })
        col.addView(botao("Back") { finish() })
    }

    private fun mostrarErro(msg: String) {
        col.removeAllViews()
        col.addView(titulo("Didn't work"))
        col.addView(linha(msg))
        col.addView(botao("Choose another file") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(i, PEDIDO_ARQUIVO)
        })
        col.addView(botao("Back") { finish() })
    }
}
