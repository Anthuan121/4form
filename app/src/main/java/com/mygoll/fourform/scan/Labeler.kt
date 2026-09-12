package com.mygoll.fourform.scan

/**
 * Names a field. The ladder tries the most RELIABLE signal before the noisiest one,
 * and the returned origin says which level resolved it (it's the input for the
 * diagnostic and for path learning):
 *
 *   1. labeledBy  - label declared by the page's author (<label for>, aria-label)
 *   2. hint
 *   3. descricao  - the node's own contentDescription
 *   4. viewId     - ONLY when readable; "question 66138698" isn't a name, it's a job id
 *   5. irmao      - sibling text in the same parent (WebView), structural label
 *   6. vizinho    - geometry: the closest text above/to the left, within the radius
 *   7. viewId-cru - last resort, and the Session NEVER matches this against the profile
 *
 * The list is extensible on purpose (vision/OCR is the next step, out of scope for this brief).
 */
object Labeler {

    // ponytail: 240px inherited from form-agent-core; it's a guess documented there, not
    // a measurement. Only the on-device test, with a real form, calibrates this number.
    const val RAIO_PX = 240

    /** Origins that count as "the level that resolved it" (viewId-cru is excluded on purpose). */
    val NIVEIS = listOf("labeledBy", "hint", "descricao", "viewId", "irmao", "vizinho")

    /**
     * A placeholder that just says TYPE isn't the field's name. Measured on his device on
     * 09/12, on Ashby (jobs.ashbyhq.com, a Ceartas job): the four fields on screen had the
     * same hint, "Type here...". Since hint is level 2 of the ladder, the app named all
     * four "Type here", matched nothing against the profile, and even sent "Type here" to
     * the AI. 4 network calls to ask the model what a placeholder is. The real question
     * was right above, at level 6, and the ladder never got there.
     */
    private val PLACEHOLDER = Regex(
        "^(type|write|enter|start typing|your answer|answer|escreva|digite|sua resposta|" +
            "resposta|escribe|introduce|tu respuesta)\\b.*",
        RegexOption.IGNORE_CASE,
    )

    fun hintGenerico(hint: String): Boolean = PLACEHOLDER.matches(hint.trim())

    /**
     * A "raw" viewId is an instance id, not a field name: "question 66138698" identifies
     * THAT specific board's job, and another job produces a different number (same lesson
     * from Binspector: an anchor is text, not an id). Rule: a stretch of 2+ digits in a
     * row gives away the raw id; "address line 1" (1 digit) is still readable.
     */
    fun viewIdCru(nome: String): Boolean = Regex("\\d{2,}").containsMatchIn(nome)

    /**
     * (label text, origin). nivelPreferido is the path-learning SHORTCUT: try that level
     * first and, if it doesn't resolve on this field, walk the whole ladder. A shortcut,
     * never a hard stop.
     */
    fun rotulo(c: Field, nivelPreferido: String? = null): Pair<String, String>? {
        if (nivelPreferido != null) porNivel(c, nivelPreferido)?.let { return it }
        for (nivel in NIVEIS) {
            if (nivel == nivelPreferido) continue // already tried above
            porNivel(c, nivel)?.let { return it }
        }
        // end of the ladder: the raw viewId comes out as a DISPLAY label ("I don't know
        // what this field is asking" comes from Session), never as a matching key against the profile.
        c.viewId?.takeIf { it.isNotBlank() }?.let { id ->
            nomeDoViewId(id)?.let { return it to "viewId-cru" }
        }
        return null
    }

    private fun porNivel(c: Field, nivel: String): Pair<String, String>? = when (nivel) {
        "labeledBy" -> c.labeledBy?.takeIf { it.isNotBlank() }?.let { it.trim() to "labeledBy" }
        // a hint repeated across several fields on the same screen doesn't identify any
        // field, and this defense is the one that crosses languages: it works for "Type
        // here" and for the equivalent in any language, without depending on the pattern
        // list above.
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
     * The closest neighbor ABOVE (with horizontal overlap) or TO THE LEFT (with vertical
     * overlap), measured in edge-to-edge pixels. It's how a human finds a field's label in
     * a form. NO radius cap here: rotulo() is what cuts it off, so the diagnostic can
     * still record the real distance of the text that got left out.
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
     * The question for a group of options: the closest text ABOVE that ⛔ isn't the label
     * of another option in the same group. Above only, on purpose. In a form the question
     * sits on top of the group, and a radio's side neighbor is almost always the radio
     * next to it, which is an answer, not a question.
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
            // the question usually starts to the left of the group, so text that starts
            // before the option and doesn't go past it also counts: without this, a long
            // question disappears.
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
