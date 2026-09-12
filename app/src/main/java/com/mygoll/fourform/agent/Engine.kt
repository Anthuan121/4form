package com.mygoll.fourform.agent

import com.mygoll.fourform.scan.Labeler
import com.mygoll.fourform.scan.Field
import com.mygoll.fourform.scan.Choice

/**
 * O laço da rodada (brief 242): varre a tela → age campo a campo, de cima para baixo →
 * rola → varre de novo → repete até o formulário parar de crescer. A árvore de
 * acessibilidade só contém o que está RENDERIZADO (Chrome e Android não criam nó para o
 * que está fora da viewport), então varrer parado nunca vê um formulário de 3 telas.
 *
 * Puro e síncrono de propósito: o TEMPO (pausa entre campos, espera da rolagem assentar)
 * vive no serviço; aqui só a decisão do próximo passo, provável em teste JVM. As quatro
 * paradas obrigatórias: rolagem sem campo novo, teto de voltas, mudança de pacote, e
 * tela que não rola mais.
 */
class Engine(private val tetoDeVoltas: Int = TETO_DE_VOLTAS) {

    companion object {
        // ponytail: 15 rolagens cobre um formulário de ~5 telas com folga; o teto existe
        // contra página infinita, não contra formulário grande. Estourou em form real → sobe.
        const val TETO_DE_VOLTAS = 15
    }

    sealed class Passo {
        /** Decidir e escrever ESTE campo agora; confirmar com campoTratado(). */
        data class Agir(val campo: Field) : Passo()

        /**
         * Decidir ESTA escolha agora (marcar ou deixar em branco) e seguir. Régua dele,
         * 12/09: "se vai marcar, marcou e o jogo segue; se não vai marcar, segue pro
         * próximo campo, não precisa me travar ali". Por isso escolha é um PASSO do laço,
         * na mesma fila e na mesma ordem visual dos campos de texto, e ⛔ não uma lista de
         * pendências no fim. O laço percorre item por item até o fim do formulário.
         */
        data class Escolher(val escolha: Choice) : Passo()

        object Rolar : Passo()
        data class Fim(val motivo: String) : Passo()
    }

    /** false = a rodada inteira não viu campo nenhum; o painel de resultado NÃO aparece. */
    var achouAlgumCampo = false
        private set

    var voltasDeRolagem = 0
        private set

    private var houveVarredura = false
    private var pacoteInicial: String? = null
    private var mudouDePacote = false
    private var semRolagem = false
    private val chavesVistas = mutableSetOf<String>()
    private val rotulosVistos = mutableSetOf<String>()

    // Fila ÚNICA de passos, ordenada pelo topo na tela: texto e escolha convivem nela na
    // ordem em que a pessoa vê. Antes eram duas listas (campos no laço, escolhas num painel
    // no fim) e era isso que produzia o travamento — o app preenchia 3 campos, chegava no
    // checkbox e parava pra perguntar. Régua dele, 12/09: "marcou, o jogo segue; não
    // marcou, segue pro próximo campo".
    private val fila = ArrayDeque<Passo>()

    private fun chaveDe(p: Passo): String? = when (p) {
        is Passo.Agir -> p.campo.chave
        is Passo.Escolher -> p.escolha.chave
        else -> null
    }

    /**
     * Alimenta o motor com o que uma varredura viu. Devolve quantos campos NOVOS entraram.
     * Da 2ª varredura em diante cada chamada conta como uma volta de rolagem concluída,
     * e voltar sem nada novo é a parada natural do laço.
     */
    fun receberVarredura(
        pacote: String?,
        campos: List<Field>,
        // Quantos campos de ESCOLHA inéditos esta varredura trouxe. Entra na conta da
        // parada porque a régua antiga media "campo de TEXTO novo", e isso matava o laço
        // cedo: medido em 12/09 num formulário de vaga real, rolar trouxe 7 radios e zero
        // caixas de texto, o laço leu isso como "cheguei ao fim" e parou na 1ª volta.
        // Rolagem que revela QUALQUER campo respondível é rolagem que valeu a pena.
        escolhasNovas: List<Choice> = emptyList(),
        // Impressão digital do que ESTA varredura viu na tela. A régua de parada passou a
        // ser a dele (12/09) e é a concepção certa: "o formulário só acaba quando a
        // rolagem chega ao fim da tela". Rolar e não achar campo novo ⛔ não é fim: um
        // formulário real tem blocos gigantes no meio (o upload do currículo, um texto de
        // consentimento) e depois volta a ter campo. Quem para no primeiro vazio perde a
        // metade de baixo. Então o único fim legítimo é a tela não se mover mais, e é
        // isso que a assinatura mede: igual duas vezes = a rolagem não moveu nada.
        assinatura: String = "",
    ): Int {
        if (!houveVarredura) {
            houveVarredura = true
            pacoteInicial = pacote
        } else {
            voltasDeRolagem++
            if (pacote != pacoteInicial) {
                mudouDePacote = true
                return 0
            }
        }
        if (campos.isNotEmpty() || escolhasNovas.isNotEmpty()) achouAlgumCampo = true
        val novos = campos.filter { !jaVisto(it) }
        for (c in novos) {
            chavesVistas.add(c.chave)
            Labeler.rotulo(c)?.let { rotulosVistos.add(it.first) }
        }
        // ordem VISUAL de cima para baixo: é o ritmo que a pessoa acompanha na tela, e é o
        // que faz texto e escolha se intercalarem como no formulário de verdade.
        val passos = novos.map { it.caixa.topo to (Passo.Agir(it) as Passo) } +
            escolhasNovas.map { it.caixa.topo to (Passo.Escolher(it) as Passo) }
        fila.addAll(passos.sortedBy { it.first }.map { it.second })
        if (voltasDeRolagem > 0 && assinatura.isNotEmpty() && assinatura == assinaturaAnterior) {
            telaNaoSeMoveu = true
        }
        assinaturaAnterior = assinatura
        return novos.size
    }

    private var assinaturaAnterior: String? = null

    /** true = a última rolagem pediu e a árvore voltou idêntica: aquele meio não move a tela. */
    var telaNaoSeMoveu = false
        private set

    /**
     * Dá mais uma chance ao laço com OUTRO meio de rolagem. Medido em 12/09 no Edge:
     * ACTION_SCROLL_FORWARD numa WebView retorna sucesso e ⛔ não move nada (207 nós antes
     * e 207 depois). Sem esta porta, "a ação da árvore não funciona neste app" e "o
     * formulário acabou" viram a mesma coisa, e o laço morre no meio do formulário.
     */
    fun tentarOutroMeioDeRolagem() {
        telaNaoSeMoveu = false
        assinaturaAnterior = null
    }

    /**
     * O mesmo campo não pode ser preenchido duas vezes quando reaparece depois da rolagem.
     * 1ª defesa: a chave estável. 2ª defesa: a chave degrada para a CAIXA quando o campo
     * não tem viewId/hint/descrição, e a caixa muda a cada rolagem — então um "novo" campo
     * com rótulo já visto E texto dentro é quase certamente um já tratado que voltou com
     * outra caixa. Descartar não perde nada: com texto ele viraria "não sobrescrevo" mesmo.
     */
    private fun jaVisto(c: Field): Boolean {
        if (c.chave in chavesVistas) return true
        if (!c.textoAtual.isNullOrBlank()) {
            val r = Labeler.rotulo(c)?.first
            if (r != null && r in rotulosVistos) return true
        }
        return false
    }

    fun proximoPasso(): Passo = when {
        mudouDePacote -> Passo.Fim("the screen changed to another app mid-round")
        fila.isNotEmpty() -> fila.first()
        // tela de ENTRADA sem nada respondível não é formulário: para na hora, em vez de
        // rolar 15 vezes atrás de algo que não existe. Depois da 1ª volta a régua inverte,
        // porque aí já se sabe que É um formulário e o vazio pode ser só um bloco do meio.
        !achouAlgumCampo && voltasDeRolagem == 0 -> Passo.Fim("I didn't find an answerable field on this screen")
        telaNaoSeMoveu && !achouAlgumCampo -> Passo.Fim("I scrolled through the whole screen and didn't find an answerable field")
        telaNaoSeMoveu -> Passo.Fim("scrolled all the way: the screen doesn't move anymore")
        semRolagem -> Passo.Fim("the screen doesn't scroll anymore")
        voltasDeRolagem >= tetoDeVoltas -> Passo.Fim("hit the cap of $tetoDeVoltas scrolls: stopping as a safety measure")
        else -> Passo.Rolar
    }

    /**
     * O serviço confirma que tratou o item da frente — preencheu, marcou, ou decidiu não
     * mexer. Vale para campo e para escolha: tratar é sair da fila, e "não marquei" também
     * é ter tratado. É essa indiferença que garante que nada trava o laço.
     */
    fun campoTratado(chave: String) {
        if (chaveDe(fila.firstOrNull() ?: return) == chave) fila.removeFirst()
        else fila.removeAll { chaveDe(it) == chave }
    }

    /** ACTION_SCROLL_FORWARD recusado ou nenhum nó rolável: a rolagem acabou para sempre. */
    fun rolagemFalhou() {
        semRolagem = true
    }
}
