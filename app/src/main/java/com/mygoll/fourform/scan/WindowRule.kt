package com.mygoll.fourform.scan

/**
 * Decide se uma mudança de janela ENCERRA a sessão de preenchimento.
 * Regra do Anthuan (09/09): tela mudou = em tese submeteu; formulário de várias páginas
 * salva a cada página. Por isso mudança DENTRO do mesmo app também encerra.
 * O que NÃO encerra: o teclado abrindo (dispara o mesmo evento) e as telas do próprio app.
 */
object WindowRule {

    // ponytail: teclado detectado por nome de pacote/classe; cobre Gboard, Samsung,
    // SwiftKey e AOSP. Teclado exótico fora da lista só faria a sessão fechar cedo demais,
    // e o teste de aparelho mede exatamente isso.
    private val TECLADOS = listOf("inputmethod", "keyboard", "honeyboard", "swiftkey")

    fun encerraSessao(pacoteEvento: String?, classeEvento: String?, meuPacote: String): Boolean {
        val pacote = pacoteEvento ?: return false
        if (pacote == meuPacote) return false
        if (TECLADOS.any { pacote.contains(it, ignoreCase = true) }) return false
        if (classeEvento?.contains("SoftInput", ignoreCase = true) == true) return false
        return true
    }
}
