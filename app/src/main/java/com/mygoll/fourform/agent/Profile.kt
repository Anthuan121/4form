package com.mygoll.fourform.agent

import com.mygoll.fourform.scan.Learned
import com.mygoll.fourform.scan.Matcher
import com.mygoll.fourform.scan.Extractor

data class Found(val valor: String, val fonte: String, val chaveCasada: String)

/**
 * What the app knows about the person: text they pasted (one "key: value" line per fact)
 * + what it learned by observing. LEARNED BEATS the base text, and the most recent beats
 * the oldest: a user correction is the strongest signal there is.
 */
class Profile(textoBase: String, private val aprendidos: List<Learned>) {

    private val base: List<Pair<String, String>> = textoBase.lines().mapNotNull { linha ->
        val i = linha.indexOf(':')
        if (i <= 0) return@mapNotNull null
        val chave = linha.substring(0, i).trim()
        val valor = linha.substring(i + 1).trim()
        if (chave.isBlank() || valor.isBlank()) null else chave to valor
    }

    fun valorPara(rotulo: String): Found? {
        aprendidos.sortedByDescending { it.quandoMs }
            .firstOrNull { casaBilingue(rotulo, it.rotulo) }
            ?.let { return Found(it.valor, "aprendido", it.rotulo) }
        base.firstOrNull { casaBilingue(rotulo, it.first) }
            ?.let { return Found(it.second, "perfil", it.first) }
        return null
    }

    /**
     * Matches the profile's key AND its twins in another language. Without this, his
     * profile says "anos de experiencia: 12", Ashby asks "How many years of professional
     * UX/UI or product design experience do you have?", and no word matches: data that
     * EXISTS turns into "I don't have this data", and the field still goes up to the AI for nothing.
     *
     * 🔴 The bilingual table has existed since 09/10 but only ran during profile IMPORT
     * (Llm.kt, when reading the resume). It had never been wired up here, at the match
     * against the field, which is where it decides whether to fill it or not. Measured on
     * his device on 09/12.
     */
    private fun casaBilingue(rotulo: String, chave: String): Boolean =
        Extractor.bilingue(chave).any { Matcher.casa(rotulo, it) }

    /**
     * A choice OPTION (radio, checkbox) is the inverted case: the option's label is
     * already the candidate VALUE, not the question. "UX/UI Specialist" should only be
     * checked if that's declared in the profile, never because it seems likely.
     *
     * Deliberately strict: requires a profile value and the option's label to contain
     * each other after normalization, with at least 4 useful characters. ⛔ Under-checking
     * is a blank field the person resolves in 1 tap; over-checking is a false statement
     * sent in their name, which is exactly what competitors do (246) and what this
     * product promises not to do. The two mistakes ⛔ do NOT have the same cost.
     */
    fun opcaoBateComPerfil(rotuloOpcao: String): Found? {
        val opcao = normalizar(rotuloOpcao)
        if (opcao.length < 4) return null
        aprendidos.sortedByDescending { it.quandoMs }
            .firstOrNull { contemUmAoOutro(opcao, normalizar(it.valor)) }
            ?.let { return Found(it.valor, "aprendido", it.rotulo) }
        base.firstOrNull { contemUmAoOutro(opcao, normalizar(it.second)) }
            ?.let { return Found(it.second, "perfil", it.first) }
        return null
    }

    /**
     * The case he ran head-on into on 09/12: *"Do you reside in a European country?"*
     * with Yes/No options. Here the QUESTION is what matches the profile, and the
     * option's label is just the candidate answer. Inverted from opcaoBateComPerfil,
     * where the option is already the value.
     *
     * Without this, the app would compare "Yes" to the profile and never match: no
     * profile contains "Yes". ⛔ Still doesn't make things up: it only answers when the
     * profile has a line for THIS question. Whatever isn't declared stays blank.
     */
    fun respostaParaEscolha(pergunta: String, rotuloOpcao: String): Found? {
        val achado = valorPara(pergunta) ?: return null
        val valor = normalizar(achado.valor)
        val opcao = normalizar(rotuloOpcao)
        if (opcao.isEmpty()) return null
        val bate = when {
            // yes/no is the most common answer and crosses languages: his profile is in
            // Portuguese and the job form is in English.
            valor in AFIRMATIVO && opcao in AFIRMATIVO -> true
            valor in NEGATIVO && opcao in NEGATIVO -> true
            valor in AFIRMATIVO || valor in NEGATIVO || opcao in AFIRMATIVO || opcao in NEGATIVO ->
                false
            else -> contemUmAoOutro(opcao, valor)
        }
        return if (bate) achado else null
    }

    private companion object {
        val AFIRMATIVO = setOf("sim", "yes", "si", "s", "y", "true", "verdadeiro")
        val NEGATIVO = setOf("nao", "no", "n", "false", "falso")
    }

    private fun contemUmAoOutro(a: String, b: String): Boolean =
        b.length >= 4 && (a.contains(b) || b.contains(a))

    private fun normalizar(s: String): String = s.lowercase()
        .replace("[áàâã]".toRegex(), "a")
        .replace("[éê]".toRegex(), "e")
        .replace("í".toRegex(), "i")
        .replace("[óôõ]".toRegex(), "o")
        .replace("ú".toRegex(), "u")
        .replace("ç".toRegex(), "c")
        .replace("[^a-z0-9 ]".toRegex(), " ")
        .replace(" +".toRegex(), " ")
        .trim()
}
