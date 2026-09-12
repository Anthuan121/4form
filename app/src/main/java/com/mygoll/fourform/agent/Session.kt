package com.mygoll.fourform.agent

import com.mygoll.fourform.scan.Learned
import com.mygoll.fourform.scan.PathMemory
import com.mygoll.fourform.scan.Labeler
import com.mygoll.fourform.scan.Field
import com.mygoll.fourform.scan.Choice

data class ItemPreenchido(val rotulo: String, val valor: String, val fonte: String)
data class ItemAberto(val rotulo: String?, val motivo: String)

data class Receipt(
    val preenchidos: List<ItemPreenchido>,
    val abertos: List<ItemAberto>,
    val aprendidos: List<Learned>,
)

/**
 * A fill session: born on the tap of the accessibility button, observes the user
 * typing/correcting ONLY while active, and dies on screen change.
 * Once closed, it stops observing: that's the lock that keeps the app from becoming
 * permanent listening.
 *
 * nivelPreferido is the path-learning shortcut (PathMemory.nivelPreferido for the
 * package): tried first in the Labeler's ladder, never a hard stop.
 */
class Session(
    private val perfil: Profile,
    private val nivelPreferido: String? = null,
    private val agoraMs: () -> Long = { System.currentTimeMillis() },
) {

    sealed class Decisao {
        data class Preencher(val valor: String, val fonte: String) : Decisao()
        data class DeixarAberto(val motivo: String) : Decisao()
    }

    data class Registro(
        val campo: Field,
        val rotulo: String?,
        val origemRotulo: String?,
        var acao: String, // "preencheu" (filled) | "aberto" (open)
        var valorEscrito: String? = null,
        var fonte: String? = null,
        var motivo: String? = null,
    )

    var ativa = true
        private set

    private val porChave = LinkedHashMap<String, Registro>()
    private val pendentes = LinkedHashMap<String, Learned>()

    fun decidir(campo: Field): Decisao {
        if (campo.senha) return Decisao.DeixarAberto("password field: I never read or write it")
        if (!campo.textoAtual.isNullOrBlank()) {
            return Decisao.DeixarAberto("already has text: I don't overwrite what I didn't put there")
        }
        val r = Labeler.rotulo(campo, nivelPreferido)
            ?: return Decisao.DeixarAberto("I couldn't identify the field")
        // a raw viewId ("question 66138698") is not a question, it's that job's id:
        // matching it against the profile would be exactly the invention this product
        // promises not to do.
        if (r.second == "viewId-cru") {
            return Decisao.DeixarAberto("I don't know what this field is asking")
        }
        val achado = perfil.valorPara(r.first)
            ?: return Decisao.DeixarAberto("\"${r.first}\": I don't have this in your profile")
        return Decisao.Preencher(achado.valor, achado.fonte)
    }

    /**
     * The same decision, for a choice option: check it ONLY when the profile already
     * declares it. His words on 09/12, about the option that stayed blank: *"that's an
     * obvious choice, if it were in my profile it would know which of the two to pick"*.
     * The rule is the profile, not probability. DeixarAberto here ⛔ asks the person
     * nothing and ⛔ doesn't freeze: the loop moves to the next item and the choice
     * becomes a summary line at the end.
     */
    fun decidirEscolha(escolha: Choice): Decisao {
        if (escolha.marcada) return Decisao.DeixarAberto("already checked: I won't uncheck it")
        if (!escolha.clicavel) return Decisao.DeixarAberto("the accessibility tree won't let me click this option")
        val rotulo = escolha.rotulo?.takeIf { it.isNotBlank() }
            ?: return Decisao.DeixarAberto("option with no label: I don't know what it states")
        // 1st rung: the group's question matches the profile and the option is the
        // answer ("Do you reside in a European country?" + "reside em país europeu: sim"
        // + option "Yes").
        escolha.pergunta?.let { p ->
            perfil.respostaParaEscolha(p, rotulo)?.let {
                return Decisao.Preencher(it.valor, it.fonte)
            }
        }
        // 2nd rung: with no readable question, the option itself needs to be declared.
        val achado = perfil.opcaoBateComPerfil(rotulo)
            ?: return Decisao.DeixarAberto("\"$rotulo\": your profile doesn't declare this")
        return Decisao.Preencher(achado.valor, achado.fonte)
    }

    /** The service calls this after trying to write; escreveu=false when the field refused. */
    fun registrar(campo: Field, decisao: Decisao, escreveu: Boolean = true) {
        val r = Labeler.rotulo(campo, nivelPreferido)
        val reg = Registro(campo, r?.first, r?.second, "aberto")
        when (decisao) {
            is Decisao.Preencher ->
                if (escreveu) {
                    reg.acao = "preencheu"
                    reg.valorEscrito = decisao.valor
                    reg.fonte = decisao.fonte
                } else {
                    reg.motivo = "the field refused the write"
                }
            is Decisao.DeixarAberto -> reg.motivo = decisao.motivo
        }
        porChave[campo.chave] = reg
    }

    /**
     * The user touched a tracked field. Becomes a pending learned entry when: the
     * session is active, the field isn't a password, it has a label, the text isn't
     * empty, and it isn't the echo of our own write. Letter-by-letter typing only
     * updates the pending entry (the last value wins); learning is only finalized in fechar().
     */
    fun textoMudou(chave: String, novoTexto: String): Boolean {
        if (!ativa) return false
        val reg = porChave[chave] ?: return false
        if (reg.campo.senha) return false
        val rotulo = reg.rotulo ?: return false
        if (novoTexto.isBlank()) {
            pendentes.remove(chave)
            return false
        }
        if (novoTexto == reg.valorEscrito) return false
        val origem = if (reg.acao == "preencheu") "corrigiu" else "preencheu"
        pendentes[chave] = Learned(rotulo, novoTexto, agoraMs(), origem)
        return true
    }

    /** Undo from the panel: the filled item goes back to open (the service clears the node). */
    fun desfazer(chave: String): Boolean {
        val reg = porChave[chave] ?: return false
        if (reg.acao != "preencheu") return false
        reg.acao = "aberto"
        reg.valorEscrito = null
        reg.fonte = null
        reg.motivo = "you undid it"
        pendentes.remove(chave)
        return true
    }

    /**
     * The user typed the value of an open item RIGHT THERE IN THE PANEL. Marks it filled
     * and turns it into a learned entry right away (the ACTION_SET_TEXT echo doesn't
     * duplicate it: textoMudou ignores text equal to valorEscrito). A raw viewId has no
     * reliable label: it writes, it doesn't learn.
     */
    fun escreverAgora(chave: String, valor: String, fonte: String = "you, in the panel"): Boolean {
        val reg = porChave[chave] ?: return false
        if (reg.campo.senha || valor.isBlank()) return false
        reg.acao = "preencheu"
        reg.valorEscrito = valor
        reg.fonte = fonte
        reg.motivo = null
        val rotulo = reg.rotulo
        if (rotulo != null && reg.origemRotulo != "viewId-cru") {
            pendentes[chave] = Learned(rotulo, valor, agoraMs(), "preencheu")
        }
        return true
    }

    fun registros(): List<Registro> = porChave.values.toList()

    fun abertosAgora(): List<ItemAberto> =
        porChave.values.filter { it.acao == "aberto" }.map { ItemAberto(it.rotulo, it.motivo ?: "") }

    fun houveAtividade(): Boolean =
        pendentes.isNotEmpty() || porChave.values.any { it.acao == "preencheu" }

    /**
     * End of session (the screen changed). Returns the receipt, or NULL when nothing was
     * filled or learned: a receipt with no content is noise, not feedback.
     */
    fun fechar(): Receipt? {
        if (!ativa) return null
        ativa = false
        if (!houveAtividade()) return null
        return Receipt(
            preenchidos = porChave.values.filter { it.acao == "preencheu" }
                .map { ItemPreenchido(it.rotulo ?: "?", it.valorEscrito ?: "", it.fonte ?: "") },
            abertos = abertosAgora(),
            aprendidos = pendentes.values.toList(),
        )
    }
}
