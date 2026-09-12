package com.mygoll.funform.core

/**
 * Dá nome a um campo. A escada tenta o sinal mais CONFIÁVEL antes do mais barulhento,
 * e a origem devolvida diz qual nível resolveu (é o insumo do diagnóstico e do
 * aprendizado de caminho):
 *
 *   1. labeledBy  — rótulo declarado pelo autor da página (<label for>, aria-label)
 *   2. hint
 *   3. descricao  — contentDescription do próprio nó
 *   4. viewId     — SÓ quando legível; "question 66138698" não é nome, é id de vaga
 *   5. irmao      — texto de irmão no mesmo pai (WebView), rótulo estrutural
 *   6. vizinho    — geometria: o texto mais próximo acima/à esquerda, dentro do raio
 *   7. viewId-cru — último recurso, e a Session NUNCA casa este com o perfil
 *
 * A lista é extensível de propósito (visão/OCR é o degrau seguinte, fora deste brief).
 */
object Labeler {

    // ponytail: 240px herdado do form-agent-core; é chute documentado lá, não medida.
    // Só o teste no aparelho, com formulário real, calibra este número.
    const val RAIO_PX = 240

    /** Origens que contam como "nível que resolveu" (viewId-cru fica de fora de propósito). */
    val NIVEIS = listOf("labeledBy", "hint", "descricao", "viewId", "irmao", "vizinho")

    /**
     * Placeholder que manda DIGITAR não é o nome do campo. Medido no aparelho dele em
     * 12/09, no Ashby (jobs.ashbyhq.com, vaga da Ceartas): os quatro campos da tela tinham
     * o mesmo hint, "Type here...". Como hint é o nível 2 da escada, o app batizava os
     * quatro de "Type here", não casava nada com o perfil e ainda mandava "Type here" para
     * a IA — 4 chamadas de rede para perguntar ao modelo o que é um placeholder. A pergunta
     * de verdade estava logo acima, no nível 6, e a escada nunca chegava lá.
     */
    private val PLACEHOLDER = Regex(
        "^(type|write|enter|start typing|your answer|answer|escreva|digite|sua resposta|" +
            "resposta|escribe|introduce|tu respuesta)\\b.*",
        RegexOption.IGNORE_CASE,
    )

    fun hintGenerico(hint: String): Boolean = PLACEHOLDER.matches(hint.trim())

    /**
     * viewId "cru" é id de instância, não nome de campo: "question 66138698" identifica
     * AQUELA vaga do board, e outra vaga produz outro número (mesma lição do Binspector:
     * âncora é texto, não id). Régua: um trecho de 2+ dígitos seguidos denuncia o cru;
     * "address line 1" (1 dígito) continua legível.
     */
    fun viewIdCru(nome: String): Boolean = Regex("\\d{2,}").containsMatchIn(nome)

    /**
     * (texto do rótulo, origem). nivelPreferido é o ATALHO do aprendizado de caminho:
     * tenta aquele nível primeiro e, se ele não resolver neste campo, percorre a escada
     * inteira — atalho, nunca trava.
     */
    fun rotulo(c: Field, nivelPreferido: String? = null): Pair<String, String>? {
        if (nivelPreferido != null) porNivel(c, nivelPreferido)?.let { return it }
        for (nivel in NIVEIS) {
            if (nivel == nivelPreferido) continue // já tentado acima
            porNivel(c, nivel)?.let { return it }
        }
        // fim da escada: o viewId cru sai como rótulo de EXIBIÇÃO ("não sei o que este
        // campo pergunta" vem da Session), nunca como chave de casamento com o perfil.
        c.viewId?.takeIf { it.isNotBlank() }?.let { id ->
            nomeDoViewId(id)?.let { return it to "viewId-cru" }
        }
        return null
    }

    private fun porNivel(c: Field, nivel: String): Pair<String, String>? = when (nivel) {
        "labeledBy" -> c.labeledBy?.takeIf { it.isNotBlank() }?.let { it.trim() to "labeledBy" }
        // hint repetido em vários campos da mesma tela não identifica campo nenhum, e essa
        // defesa é a que atravessa idioma: vale para "Type here" e para o equivalente em
        // qualquer língua, sem depender da lista de padrões acima.
        "hint" -> c.hint
            ?.takeIf { it.isNotBlank() && !c.hintRepetidoNaTela && !hintGenerico(it) }
            ?.let { it.trim() to "hint" }
        "descricao" -> c.descricao?.takeIf { it.isNotBlank() }?.let { it.trim() to "descricao" }
        "viewId" -> c.viewId?.takeIf { it.isNotBlank() }?.let { id ->
            nomeDoViewId(id)?.takeIf { !viewIdCru(it) }?.let { it to "viewId" }
        }
        "irmao" -> c.rotuloIrmao?.takeIf { it.isNotBlank() }?.let { it.trim() to "irmao" }
        "vizinho" -> c.rotuloVizinho?.takeIf { it.distanciaPx <= RAIO_PX }
            ?.let { it.texto to "vizinho:${it.distanciaPx}px" }
        else -> null
    }

    private fun nomeDoViewId(id: String): String? =
        id.substringAfterLast('/').replace('_', ' ').replace('-', ' ').trim().ifBlank { null }

    /**
     * O vizinho mais próximo ACIMA (com sobreposição horizontal) ou À ESQUERDA (com
     * sobreposição vertical), medido em pixels de borda a borda. É como um humano acha
     * o rótulo de um campo num formulário. SEM teto de raio aqui: quem corta é rotulo(),
     * para o diagnóstico poder registrar a distância real do texto que ficou de fora.
     */
    fun vizinhoMaisProximo(campo: Box, textos: List<Pair<String, Box>>): RotuloVizinho? {
        var melhor: RotuloVizinho? = null
        for ((texto, t) in textos) {
            if (texto.isBlank()) continue
            val dist = distancia(campo, t) ?: continue
            if (melhor == null || dist < melhor.distanciaPx) {
                melhor = RotuloVizinho(texto.trim(), dist)
            }
        }
        return melhor
    }

    /**
     * A pergunta de um grupo de opções: o texto mais próximo ACIMA que ⛔ não seja rótulo de
     * outra opção do mesmo grupo. Só acima de propósito — num formulário a pergunta fica em
     * cima do grupo, e o vizinho lateral de um radio é quase sempre o radio do lado, que é
     * resposta e não pergunta.
     */
    fun perguntaAcima(
        opcao: Box,
        textos: List<Pair<String, Box>>,
        rotulosDeOpcao: Set<String>,
    ): String? {
        var melhor: Pair<String, Int>? = null
        for ((texto, t) in textos) {
            val limpo = texto.trim()
            if (limpo.isBlank() || Matcher.normalizar(limpo) in rotulosDeOpcao) continue
            if (t.baixo > opcao.topo) continue
            val sobrepoe = t.esq < opcao.dir && t.dir > opcao.esq
            // a pergunta costuma começar à esquerda do grupo, então vale também o texto que
            // começa antes da opção e não a ultrapassa: sem isso, pergunta longa some.
            if (!sobrepoe && !(t.esq <= opcao.esq && t.dir > opcao.esq)) continue
            val dist = opcao.topo - t.baixo
            if (melhor == null || dist < melhor.second) melhor = limpo to dist
        }
        return melhor?.first
    }

    private fun distancia(campo: Box, t: Box): Int? {
        val sobrepoeHorizontal = t.esq < campo.dir && t.dir > campo.esq
        val sobrepoeVertical = t.topo < campo.baixo && t.baixo > campo.topo
        return when {
            t.baixo <= campo.topo && sobrepoeHorizontal -> campo.topo - t.baixo
            t.dir <= campo.esq && sobrepoeVertical -> campo.esq - t.dir
            else -> null
        }
    }
}
