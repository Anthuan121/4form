package com.mygoll.fourform.scan

/** A fact extracted from the file, ALWAYS with the line it came from: this is what lets
 *  the person check it in seconds on the confirmation screen, instead of rereading the whole file. */
data class ParExtraido(val chave: String, val valor: String, val linha: Int, val linhaTexto: String)

data class LinhaNaoEntendida(val linha: Int, val texto: String)

data class Extracao(val pares: List<ParExtraido>, val naoEntendi: List<LinhaNaoEntendida>)

/**
 * Turns file text (.md/.txt) into key: value pairs WITHOUT MAKING THINGS UP: only what
 * has a recognizable shape comes out (email, phone, linkedin), an explicit "key: value"
 * pair, and a name only on the first content line or the first level-1 heading.
 * Everything else falls into "didn't understand", for the person to decide. Takes a
 * String and returns data: by construction this class has no access to any storage.
 * The confirmation screen is what saves it, after the person's OK.
 *
 * Bilingual keys via a FIXED TABLE (telefone<->phone, cidade<->city, nome<->name), never
 * via guessed translation: the table only links words whose meaning is the same piece of
 * data, decided here in code and tested.
 */
object Extractor {

    private val RE_EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val RE_LINKEDIN =
        Regex("(?:https?://)?(?:[a-z]{2,3}\\.)?linkedin\\.com/in/[A-Za-z0-9\\-_%.]+", RegexOption.IGNORE_CASE)
    private val RE_FONE = Regex("[+(]?[0-9][0-9 ()\\-./]{5,}[0-9]")

    // "2020 - 2024" has 8 digits and would pass as a phone number; a resume date range
    // isn't a phone. (the regex also matches the long dash characters real resumes use in date ranges.)
    private val RE_PERIODO =
        Regex("^(?:19|20)\\d{2}\\s*(?:[-–—]|a|to|até)\\s*(?:19|20)\\d{2}$", RegexOption.IGNORE_CASE)
    private val RE_DATA = Regex("^\\d{1,4}[./-]\\d{1,2}[./-]\\d{1,4}$")

    // Fixed equivalence table. The canonical key generates the twins; the Matcher does
    // NOT match "phone" with "telefone" on its own (different languages), which is why
    // both entries exist.
    private val GEMEAS = mapOf(
        "email" to listOf("email"),
        "fone" to listOf("phone", "telefone"),
        "nome" to listOf("nome", "name"),
        "cidade" to listOf("city", "cidade"),
        "linkedin" to listOf("linkedin"),
        "primeiro" to listOf("first name", "primeiro nome"),
        "ultimo" to listOf("last name", "sobrenome"),
        // Added on 09/10 from his REAL test on Greenhouse: the form asked "How many years
        // of professional UX/UI experience" and "Are you currently located in an EU
        // country?" and the profile answered in Portuguese. Matcher.kt matches word by
        // word; without the English twin, data that EXISTS turns into "I don't have this data".
        "anos" to listOf("years of experience", "anos de experiencia"),
        "pais" to listOf("country", "pais"),
        "cargo" to listOf("job title", "cargo"),
        "portfolio" to listOf("portfolio", "portfolio url"),
        "autorizacao" to listOf("work authorisation", "autorizacao de trabalho"),
        "nacionalidade" to listOf("nationality", "nacionalidade"),
        "idiomas" to listOf("languages", "idiomas"),
        "area" to listOf("industry", "area de atuacao"),
        "empresa" to listOf("company", "empresa"),
        "formacao" to listOf("education", "formacao"),
    )

    // What an explicit key written in the file means (normalized by the Matcher).
    private val CHAVE_PARA_CANONICA = mapOf(
        "email" to "email", "e mail" to "email",
        "phone" to "fone", "telefone" to "fone", "celular" to "fone", "mobile" to "fone",
        "nome" to "nome", "name" to "nome", "nome completo" to "nome", "full name" to "nome",
        "city" to "cidade", "cidade" to "cidade",
        "linkedin" to "linkedin",
        "first name" to "primeiro", "primeiro nome" to "primeiro",
        "last name" to "ultimo", "sobrenome" to "ultimo", "surname" to "ultimo",
        "anos de experiencia" to "anos", "anos experiencia" to "anos", "experiencia" to "anos",
        "years of experience" to "anos", "years" to "anos", "anos_experiencia" to "anos",
        "pais" to "pais", "country" to "pais",
        "cargo" to "cargo", "job title" to "cargo", "title" to "cargo", "role" to "cargo",
        "portfolio" to "portfolio", "website" to "portfolio", "site" to "portfolio",
        "autorizacao de trabalho" to "autorizacao", "autorizacao_trabalho" to "autorizacao",
        "work authorisation" to "autorizacao", "work authorization" to "autorizacao",
        "visto" to "autorizacao", "visa" to "autorizacao",
        "nacionalidade" to "nacionalidade", "nationality" to "nacionalidade",
        "idiomas" to "idiomas", "languages" to "idiomas", "idioma" to "idiomas",
        "area de atuacao" to "area", "area_atuacao" to "area", "industry" to "area",
        "empresa" to "empresa", "company" to "empresa",
        "formacao" to "formacao", "education" to "formacao",
    )

    /**
     * The twins of a key written by hand or proposed by the AI. Outside the table,
     * returns the key itself (⛔ never translates on its own: guessed translation invents data).
     *
     * Exists as public because of 09/10: the AI reads an English resume and proposes
     * Portuguese keys (`anos_experiencia`), and the form asks in English. Without going
     * through here, the pair enters the profile and never matches the field.
     */
    fun bilingue(chave: String): List<String> {
        val canonica = CHAVE_PARA_CANONICA[Matcher.normalizar(chave)] ?: return listOf(chave)
        return GEMEAS[canonica] ?: listOf(chave)
    }

    private val CONECTIVOS = setOf("da", "de", "do", "dos", "das", "e", "van", "von", "del", "di", "la")

    // The first line of many resumes is the document's title, not the person.
    // A document function word in ANY position disqualifies it ("Portfolio da Maria").
    private val NAO_E_NOME = setOf(
        "curriculum", "vitae", "curriculum vitae", "resume", "curriculo", "cv",
        "portfolio", "perfil", "profile",
    )

    fun extrair(texto: String): Extracao {
        val linhas = texto.lines()
        // normalized key -> pair (first one wins; correcting is the confirmation screen's job)
        val pares = LinkedHashMap<String, ParExtraido>()
        val naoEntendi = mutableListOf<LinhaNaoEntendida>()
        val consumidas = mutableSetOf<Int>()

        // name first: the rule is POSITIONAL (first content line or first level-1
        // heading) and needs to run before the line becomes "didn't understand".
        acharNome(linhas)?.let { (idx, nomeCompleto) ->
            consumidas.add(idx)
            emitir(pares, "nome", nomeCompleto, idx + 1, linhas[idx])
            val palavras = nomeCompleto.split(' ').filter { it.isNotBlank() }
            if (palavras.size >= 2) {
                emitir(pares, "primeiro", palavras.first(), idx + 1, linhas[idx])
                emitir(pares, "ultimo", palavras.last(), idx + 1, linhas[idx])
            }
        }

        linhas.forEachIndexed { idx, original ->
            if (idx in consumidas) return@forEachIndexed
            val bruta = original.trim()
            if (bruta.isEmpty() || bruta.startsWith("```")) return@forEachIndexed
            if (bruta.none { it.isLetterOrDigit() }) return@forEachIndexed // a rule made of dashes, dots, or asterisks

            val limpa = semMarcadores(bruta)
            var consumiu = false

            RE_EMAIL.find(limpa)?.let { consumiu = emitir(pares, "email", it.value, idx + 1, original) || consumiu }
            RE_LINKEDIN.find(limpa)?.let { consumiu = emitir(pares, "linkedin", it.value, idx + 1, original) || consumiu }
            acharFone(limpa)?.let { consumiu = emitir(pares, "fone", it, idx + 1, original) || consumiu }

            if (!consumiu && !bruta.startsWith(">") && !bruta.startsWith("#")) {
                parExplicito(limpa)?.let { (chave, valor) ->
                    val canonica = CHAVE_PARA_CANONICA[Matcher.normalizar(chave)]
                    consumiu = if (canonica != null) {
                        emitir(pares, canonica, valor, idx + 1, original)
                    } else {
                        val norm = Matcher.normalizar(chave)
                        if (norm.isNotEmpty() && norm !in pares && pareceDado(valor)) {
                            pares[norm] = ParExtraido(chave, valor, idx + 1, original)
                            true
                        } else false
                    }
                }
            }
            if (!consumiu) naoEntendi.add(LinhaNaoEntendida(idx + 1, original))
        }
        return Extracao(pares.values.toList(), naoEntendi)
    }

    /** Emits the canonical key and the table's twins. true if at least one went in (an
     *  identical value already present counts as going in: the line was understood, just repeated). */
    private fun emitir(
        pares: LinkedHashMap<String, ParExtraido>,
        canonica: String,
        valor: String,
        linha: Int,
        linhaTexto: String,
    ): Boolean {
        var entrou = false
        for (chave in GEMEAS.getValue(canonica)) {
            val norm = Matcher.normalizar(chave)
            val existente = pares[norm]
            when {
                existente == null -> {
                    pares[norm] = ParExtraido(chave, valor.trim(), linha, linhaTexto)
                    entrou = true
                }
                existente.valor == valor.trim() -> entrou = true
            }
        }
        return entrou
    }

    private fun acharNome(linhas: List<String>): Pair<Int, String>? {
        var primeiraConteudo: Int? = null
        var primeiroH1: Int? = null
        for ((idx, l) in linhas.withIndex()) {
            val t = l.trim()
            if (t.isEmpty() || t.startsWith("```") || t.startsWith(">")) continue
            if (t.none { it.isLetterOrDigit() }) continue
            if (primeiroH1 == null && t.startsWith("# ")) primeiroH1 = idx
            if (primeiraConteudo == null) primeiraConteudo = idx
            // only these TWO positions can yield a name; from the middle of the text would be guessing
            if (primeiroH1 != null) break
        }
        for (idx in listOfNotNull(primeiraConteudo, primeiroH1).distinct()) {
            val limpo = semMarcadores(linhas[idx].trim())
            if (pareceNome(limpo)) return idx to limpo
        }
        return null
    }

    /** The shape of a person's name, strict on purpose: only letters (with accents),
     *  spaces, and an initial's period; 2 to 5 words; each one capitalized, all-caps, or
     *  a connective. Erring toward under-matching falls into "didn't understand"; erring
     *  toward over-matching invents. */
    fun pareceNome(s: String): Boolean {
        val t = s.trim().removeSuffix(".")
        if (t.any { it.isDigit() } || t.contains('@')) return false
        if (!t.all { it.isLetter() || it == ' ' || it == '.' }) return false
        val palavras = t.split(' ').filter { it.isNotBlank() }
        if (palavras.size !in 2..5) return false
        if (Matcher.normalizar(t) in NAO_E_NOME) return false
        if (palavras.any { Matcher.normalizar(it) in NAO_E_NOME }) return false
        return palavras.all { p ->
            p.lowercase() in CONECTIVOS || p.first().isUpperCase()
        }
    }

    private fun acharFone(linha: String): String? {
        for (m in RE_FONE.findAll(linha)) {
            val candidato = m.value.trim()
            val digitos = candidato.count { it.isDigit() }
            if (digitos !in 8..15) continue
            if (RE_PERIODO.matches(candidato)) continue // "2020 - 2024"
            if (RE_DATA.matches(candidato)) continue // "09/10/2026"
            return candidato
        }
        return null
    }

    /** GENERAL RULE (brief 244): a FREE key pair only comes out if the VALUE looks like
     *  data. Up to 5 words, no sentence or list punctuation (comma, semicolon,
     *  parenthesis, ·, period followed by a space). Resume prose with a colon in the
     *  middle falls entirely into "didn't understand", for the person to decide; a
     *  CANONICAL key is exempt because naming the field ("cidade:") is explicit intent,
     *  not a sentence's accident. */
    private fun pareceDado(valor: String): Boolean {
        if (valor.any { it in ",;()·" }) return false
        if (valor.contains(". ")) return false
        return valor.split(' ').count { it.isNotBlank() } <= 5
    }

    /** Explicit "key: value", with guards against a sentence with a colon and a URL. */
    private fun parExplicito(linha: String): Pair<String, String>? {
        val i = linha.indexOf(':')
        if (i !in 1..40) return null
        val chave = linha.substring(0, i).trim()
        val valor = linha.substring(i + 1).trim()
        if (chave.isBlank() || valor.isBlank()) return null
        if (valor.startsWith("//")) return null // "https://..." isn't a pair
        if (valor.length > 200) return null // a paragraph with a colon isn't profile data
        if (chave.split(' ').filter { it.isNotBlank() }.size > 5) return null
        if (chave.contains(". ")) return null // end of a sentence inside the "key" = prose, not data
        if (chave.none { it.isLetter() }) return null // "14:30" isn't a pair
        if (!chave.all { it.isLetter() || it.isDigit() || it == ' ' || it == '-' || it == '_' || it == '.' }) return null
        return chave to valor
    }

    /** Strips markdown decoration that isn't data: list marker, emphasis, heading. */
    private fun semMarcadores(linha: String): String =
        linha
            .removePrefix("- ").removePrefix("* ").removePrefix("• ").removePrefix("· ")
            .replace("*", "").replace("`", "")
            .removePrefix("# ").removePrefix("## ").removePrefix("### ")
            .trim()
}
