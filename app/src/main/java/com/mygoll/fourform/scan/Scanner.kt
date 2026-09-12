package com.mygoll.fourform.scan

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.mygoll.fourform.scan.Box
import com.mygoll.fourform.scan.Matcher
import com.mygoll.fourform.scan.Field
import com.mygoll.fourform.scan.Choice
import com.mygoll.fourform.scan.Labeler

/**
 * Percorre a árvore de acessibilidade UMA vez e devolve: os campos editáveis (como Field,
 * puro e testável), os textos visíveis (candidatos a rótulo vizinho), o nó real de cada
 * campo (para o ACTION_SET_TEXT) e o nó ROLÁVEL de maior área (para o laço do Engine pedir
 * ACTION_SCROLL_FORWARD — ação da própria árvore, não gesto por coordenada, de propósito:
 * gesto quebra em tela de tamanho diferente). Só nós visíveis: o que o usuário não vê,
 * o app não toca.
 */
object Scanner {

    // ponytail: teto de nós contra árvore patológica (página web infinita); se estourar,
    // o campo fora do teto simplesmente não entra na rodada.
    private const val MAX_NOS = 1500

    class Saida(
        val campos: List<Field>,
        val nos: Map<String, AccessibilityNodeInfo>,
        val rolavel: AccessibilityNodeInfo?,
        val totalNos: Int,
        /** Censo dos campos de ESCOLHA: só contagem e rótulo, nenhuma ação (ver Choice). */
        val escolhas: List<Choice> = emptyList(),
        /** Nó real de cada escolha, para o ACTION_CLICK disparado pela pessoa no painel. */
        val nosEscolha: Map<String, AccessibilityNodeInfo> = emptyMap(),
        /**
         * Impressão digital desta varredura: quantos nós, e o que foi visto. Serve à régua
         * de parada do Engine (a tela parou de se mover?). Só identidade estrutural, nenhum
         * valor digitado entra aqui.
         */
        val assinatura: String = "",
    )

    /**
     * Classifica um nó NÃO editável que ainda assim é um campo respondível.
     * Fontes (brief 247, leitura do fonte do Chromium): checkbox/radio chegam com
     * isCheckable propagado do Blink; <select> fechado chega como Spinner e é sempre folha
     * (as <option> nunca viram filhos); <input type=date> chega com inputType DATETIME.
     * Devolve null para o resto, que é texto comum de página.
     */
    private fun tipoDeEscolha(no: AccessibilityNodeInfo): String? {
        val classe = no.className?.toString() ?: ""
        return when {
            no.isCheckable && classe.contains("RadioButton") -> "radio"
            no.isCheckable && classe.contains("Switch") -> "switch"
            no.isCheckable -> "checkbox"
            classe.contains("Spinner") -> "select"
            no.isClickable && (no.inputType and INPUT_TYPE_DATETIME) != 0 -> "data"
            else -> null
        }
    }

    // android.text.InputType.TYPE_CLASS_DATETIME
    private const val INPUT_TYPE_DATETIME = 0x00000004

    fun varrer(raiz: AccessibilityNodeInfo): Saida {
        val brutos = mutableListOf<Field>()
        val escolhasBrutas = mutableListOf<Choice>()
        val nosEscolha = mutableMapOf<String, AccessibilityNodeInfo>()
        val nos = mutableMapOf<String, AccessibilityNodeInfo>()
        val textos = mutableListOf<Pair<String, Box>>()
        var rolavel: AccessibilityNodeInfo? = null
        var areaRolavel = 0L
        var visitados = 0

        fun caixaDe(no: AccessibilityNodeInfo): Box {
            val r = Rect()
            no.getBoundsInScreen(r)
            return Box(r.left, r.top, r.right, r.bottom)
        }

        fun anda(no: AccessibilityNodeInfo?, dentroWeb: Boolean) {
            if (no == null || visitados >= MAX_NOS) return
            visitados++
            val ehWeb = dentroWeb || no.className?.contains("WebView") == true
            if (no.isVisibleToUser) {
                // o rolável de MAIOR área tende a ser o contêiner principal da página,
                // não um carrossel pequeno no meio dela
                if (no.isScrollable) {
                    val c = caixaDe(no)
                    val area = (c.dir - c.esq).toLong() * (c.baixo - c.topo).toLong()
                    if (area > areaRolavel) {
                        areaRolavel = area
                        rolavel = no
                    }
                }
                if (no.isEditable) {
                    val senha = no.isPassword
                    val hint = no.hintText?.toString()?.takeIf { it.isNotBlank() }
                    val descricao = no.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    val viewId = no.viewIdResourceName
                    val caixa = caixaDe(no)
                    // senha: o texto NUNCA é lido; hint mostrado como texto também não conta
                    val texto = when {
                        senha -> null
                        no.isShowingHintText -> null
                        else -> no.text?.toString()
                    }
                    // labeledBy: o rótulo que o AUTOR da página declarou para este campo
                    val noRotulo = no.labeledBy
                    val labeledBy = noRotulo?.let {
                        it.text?.toString()?.takeIf { t -> t.isNotBlank() }
                            ?: it.contentDescription?.toString()?.takeIf { t -> t.isNotBlank() }
                    }
                    val campo = Field(
                        chave = chaveEstavel(viewId, hint, descricao, caixa),
                        inputType = no.inputType,
                        hint = hint,
                        descricao = descricao,
                        viewId = viewId,
                        senha = senha,
                        caixa = caixa,
                        textoAtual = texto,
                        dentroDeWebView = ehWeb,
                        labeledBy = labeledBy?.trim(),
                        labeledByPresente = noRotulo != null,
                        rotuloIrmao = if (ehWeb) rotuloIrmao(no) else null,
                    )
                    brutos.add(campo)
                    nos[campo.chave] = no
                } else {
                    val t = no.text?.toString()
                    // continua alimentando os candidatos a rótulo ANTES de classificar:
                    // um checkbox costuma carregar o próprio texto, e ele já servia de
                    // rótulo para os vizinhos. Mexer nisso seria regressão silenciosa.
                    if (!t.isNullOrBlank()) textos.add(t to caixaDe(no))
                    tipoDeEscolha(no)?.let { tipo ->
                        val e = Choice(
                            tipo = tipo,
                            rotulo = t?.takeIf { it.isNotBlank() }
                                ?: no.contentDescription?.toString()?.takeIf { it.isNotBlank() },
                            viewId = no.viewIdResourceName,
                            caixa = caixaDe(no),
                            dentroDeWebView = ehWeb,
                            // ACTION_CLICK exige isClickable; um radio dentro de <label>
                            // às vezes chega não clicável e quem responde é o pai.
                            clicavel = no.isClickable || no.parent?.isClickable == true,
                            // já marcada (pela pessoa ou pelo padrão do site) sai do laço
                            // sem ação: clicar aqui DESMARCARIA o que ela escolheu.
                            marcada = no.isChecked,
                        )
                        escolhasBrutas.add(e)
                        nosEscolha[e.chave] = if (no.isClickable) no else (no.parent ?: no)
                    }
                }
            }
            for (i in 0 until no.childCount) anda(no.getChild(i), ehWeb)
        }

        anda(raiz, false)
        val hintsRepetidos = brutos.mapNotNull { it.hint?.trim() }
            .groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys
        val campos = brutos.map {
            it.copy(
                rotuloVizinho = Labeler.vizinhoMaisProximo(it.caixa, textos),
                hintRepetidoNaTela = it.hint?.trim() in hintsRepetidos,
            )
        }
        // escolha sem texto próprio herda o vizinho geométrico, mesma escada dos editáveis
        val rotulosDeOpcao = escolhasBrutas.mapNotNull { it.rotulo?.let(Matcher::normalizar) }.toSet()
        val escolhas = escolhasBrutas.map {
            val comRotulo =
                if (it.rotulo != null) it
                else it.copy(rotulo = Labeler.vizinhoMaisProximo(it.caixa, textos)?.texto)
            comRotulo.copy(
                pergunta = Labeler.perguntaAcima(it.caixa, textos, rotulosDeOpcao),
            )
        }
        // a assinatura inclui a POSIÇÃO (caixa.topo) de propósito: rolar meia tela mantém os
        // mesmos campos na árvore, só que mais acima. Sem a posição, "rolou meia tela" seria
        // lido como "nada mudou" e o laço morreria antes do fim do formulário.
        val assinatura = buildString {
            append(visitados).append('#')
            campos.forEach { append(it.chave).append(':').append(it.caixa.topo).append(',') }
            append('#')
            escolhas.forEach { append(it.chave).append(':').append(it.caixa.topo).append(',') }
        }
        return Saida(campos, nos, rolavel, visitados, escolhas, nosEscolha, assinatura)
    }

    /**
     * Rótulo ESTRUTURAL em WebView: o <label> costuma virar nó irmão do <input> no mesmo
     * pai. Anda dos irmãos anteriores, do mais próximo para o mais longe, e pega o
     * primeiro com texto — sem geometria, então sobrevive a layout apertado.
     */
    private fun rotuloIrmao(no: AccessibilityNodeInfo): String? {
        val pai = no.parent ?: return null
        var indice = -1
        for (i in 0 until pai.childCount) {
            if (pai.getChild(i) == no) {
                indice = i
                break
            }
        }
        if (indice <= 0) return null
        for (i in indice - 1 downTo 0) {
            val irmao = pai.getChild(i) ?: continue
            if (irmao.isEditable) continue // outro campo não é rótulo
            val t = irmao.text?.toString()?.takeIf { it.isNotBlank() }
                ?: irmao.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            if (t != null) return t.trim()
        }
        return null
    }

    /**
     * Identidade estável de um campo entre a varredura e os eventos de texto que chegam
     * depois. viewId > hint > descrição > caixa; a caixa é o pior sinal porque muda
     * quando o teclado abre e a tela rola — por isso o Engine tem a 2ª defesa de dedupe
     * por rótulo+texto para o campo re-visto depois da rolagem.
     */
    fun chaveEstavel(viewId: String?, hint: String?, descricao: String?, caixa: Box): String =
        viewId?.takeIf { it.isNotBlank() }
            ?: hint?.takeIf { it.isNotBlank() }?.let { "hint:$it" }
            ?: descricao?.takeIf { it.isNotBlank() }?.let { "desc:$it" }
            ?: "caixa:${caixa.esq},${caixa.topo},${caixa.dir},${caixa.baixo}"
}
