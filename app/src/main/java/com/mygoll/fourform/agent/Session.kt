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
 * Uma sessão de preenchimento: nasce no toque do botão de acessibilidade, observa o
 * usuário digitar/corrigir SÓ enquanto está ativa, e morre na mudança de tela.
 * Fechou, parou de observar: é a trava que impede o app de virar escuta permanente.
 *
 * nivelPreferido é o atalho do aprendizado de caminho (PathMemory.nivelPreferido do
 * pacote): tentado primeiro na escada do Labeler, nunca trava.
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
        var acao: String, // "preencheu" | "aberto"
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
        // viewId cru ("question 66138698") não é pergunta, é id daquela vaga: casar isso
        // com o perfil seria exatamente a invenção que o produto promete não fazer.
        if (r.second == "viewId-cru") {
            return Decisao.DeixarAberto("I don't know what this field is asking")
        }
        val achado = perfil.valorPara(r.first)
            ?: return Decisao.DeixarAberto("\"${r.first}\": I don't have this in your profile")
        return Decisao.Preencher(achado.valor, achado.fonte)
    }

    /**
     * A mesma decisão, para uma opção de escolha: marcar SÓ quando o perfil já declara
     * aquilo. Fala dele em 12/09, sobre a opção que ficou em branco: *"essa é uma escolha
     * óbvia, se estivesse no meu perfil saberia qual das duas escolher"* — a régua é o
     * perfil, não a probabilidade. DeixarAberto aqui ⛔ não pede nada à pessoa e ⛔ não
     * trava: o laço segue para o próximo item e a escolha vira uma linha de resumo no fim.
     */
    fun decidirEscolha(escolha: Choice): Decisao {
        if (escolha.marcada) return Decisao.DeixarAberto("already checked: I won't uncheck it")
        if (!escolha.clicavel) return Decisao.DeixarAberto("the accessibility tree won't let me click this option")
        val rotulo = escolha.rotulo?.takeIf { it.isNotBlank() }
            ?: return Decisao.DeixarAberto("option with no label: I don't know what it states")
        // 1ª escada: a pergunta do grupo casa com o perfil e a opção é a resposta ("Você
        // reside num país europeu?" + "reside em país europeu: sim" + opção "Yes").
        escolha.pergunta?.let { p ->
            perfil.respostaParaEscolha(p, rotulo)?.let {
                return Decisao.Preencher(it.valor, it.fonte)
            }
        }
        // 2ª escada: sem pergunta legível, a própria opção precisa estar declarada.
        val achado = perfil.opcaoBateComPerfil(rotulo)
            ?: return Decisao.DeixarAberto("\"$rotulo\": your profile doesn't declare this")
        return Decisao.Preencher(achado.valor, achado.fonte)
    }

    /** O serviço chama depois de tentar escrever; escreveu=false quando o campo recusou. */
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
     * O usuário mexeu num campo rastreado. Vira aprendizado pendente quando:
     * a sessão está ativa, o campo não é senha, tem rótulo, o texto não é vazio e não é
     * o eco da nossa própria escrita. Digitação letra a letra só atualiza o pendente
     * (o último valor vence); o aprendizado só é efetivado no fechar().
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

    /** Desfazer do painel: o item preenchido volta a aberto (o serviço limpa o nó). */
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
     * O usuário digitou o valor de um item aberto ALI NO PAINEL. Marca como preenchido e
     * vira aprendizado na hora (o eco do ACTION_SET_TEXT não duplica: textoMudou ignora
     * texto igual ao valorEscrito). viewId cru não tem rótulo confiável: escreve, não aprende.
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
     * Fim de sessão (a tela mudou). Devolve o recibo, ou NULL quando nada foi preenchido
     * nem aprendido: recibo sem conteúdo é ruído, não feedback.
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
