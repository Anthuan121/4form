package com.mygoll.fourform.agent

import com.mygoll.fourform.scan.Learned
import com.mygoll.fourform.scan.PathMemory
import com.mygoll.fourform.scan.Labeler
import com.mygoll.fourform.scan.Field
import com.mygoll.fourform.scan.Choice
import com.mygoll.fourform.scan.Matcher

data class ItemPreenchido(val rotulo: String, val valor: String, val fonte: String)
data class ItemAberto(val rotulo: String?, val motivo: String)

/**
 * Fields that belong to the PERSON, by DECISION, not by missing data. His words on
 * 09/12, about the salary field: "he has to understand he must NOT fill this in, it has
 * to be left for the user." Before this rule the app only left these fields blank by
 * ACCIDENT (it didn't find the word in the profile); the moment the profile gains a
 * "salary expectation: 55k" line, that accident stops protecting anyone. This turns the
 * accident into a rule that survives the profile being filled in.
 *
 * Three families, three different reasons a field can be off limits:
 *  - NEGOTIATION: salary, availability, notice period. Not data about the person, it's
 *    a POSITION that changes per job.
 *  - SENSITIVE IDENTITY: gender, ethnicity, disability, veteran status, orientation.
 *    Never answered on someone's behalf, not even with the value in hand.
 *  - LEGAL DECLARATION: consents, terms, "I declare the information is true". The
 *    signature belongs to the person, not to the agent.
 *
 * Matches by WHOLE WORD through the Matcher that already exists (scan/Matcher.kt), the
 * same rule the rest of the app uses so "Surname" doesn't match the key "name": no new
 * comparison logic here.
 *
 * Deliberately a short, explicit list instead of a dictionary: letting a reserved field
 * slip through costs one blank field the person notices and fills themselves. Blocking a
 * common field by an over-eager word list costs the whole form's trust. The first
 * mistake is cheap, the second one is the one to avoid.
 */
object CampoReservado {

    enum class Familia { NEGOCIACAO, IDENTIDADE, JURIDICO }

    data class Motivo(val familia: Familia, val frase: String)

    private val NEGOCIACAO = listOf(
        "salary expectations", "expected salary", "desired salary", "current salary",
        "compensation expectations", "notice period", "available to start", "when can you start",
        "pretensao salarial", "expectativa salarial", "salario atual", "aviso previo",
        "disponivel para comecar", "data de inicio disponivel",
    )
    private val IDENTIDADE = listOf(
        "gender", "ethnicity", "race", "disability", "veteran status", "sexual orientation",
        "genero", "etnia", "raca", "deficiencia", "condicao de veterano", "orientacao sexual",
    )
    private val JURIDICO = listOf(
        "i declare that", "i certify that", "i consent", "i agree to the terms",
        "declaro que", "certifico que", "eu concordo com os termos",
    )

    /** Pure and testable: label in, reserved family + reason out, or null when it isn't reserved. */
    fun deste(rotulo: String): Motivo? = when {
        bate(rotulo, NEGOCIACAO) -> Motivo(
            Familia.NEGOCIACAO, "Salary is your call, not mine. I won't guess a number for you.",
        )
        bate(rotulo, IDENTIDADE) -> Motivo(
            Familia.IDENTIDADE, "This is yours to state. I won't answer it on your behalf.",
        )
        bate(rotulo, JURIDICO) -> Motivo(
            Familia.JURIDICO, "That's your signature, not mine to give.",
        )
        else -> null
    }

    private fun bate(rotulo: String, termos: List<String>): Boolean =
        termos.any { Matcher.casa(rotulo, it) }
}

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
        // reservado: true only for CampoReservado's motive, kept separate from a plain
        // "sem dado" (no data) DeixarAberto so the two can be counted apart (a rule
        // refusing the field is not the same signal as the app simply not knowing).
        data class DeixarAberto(val motivo: String, val reservado: Boolean = false) : Decisao()
    }

    data class Registro(
        val campo: Field,
        val rotulo: String?,
        val origemRotulo: String?,
        var acao: String, // "preencheu" | "aberto"
        var valorEscrito: String? = null,
        var fonte: String? = null,
        var motivo: String? = null,
        var reservado: Boolean = false,
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
        // reserved beats everything below: even when the profile HAS the value, this
        // field isn't the agent's to fill. Checked before the profile lookup on purpose,
        // so a reserved field never gets the chance to look "found" to begin with.
        CampoReservado.deste(r.first)?.let { return Decisao.DeixarAberto(it.frase, reservado = true) }
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
            is Decisao.DeixarAberto -> {
                reg.motivo = decisao.motivo
                reg.reservado = decisao.reservado
            }
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
