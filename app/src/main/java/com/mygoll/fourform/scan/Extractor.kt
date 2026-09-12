package com.mygoll.fourform.scan

/** Um dado extraído do arquivo, SEMPRE com a linha de onde saiu: é o que deixa a pessoa
 *  conferir em segundos na tela de confirmação, em vez de reler o arquivo inteiro. */
data class ParExtraido(val chave: String, val valor: String, val linha: Int, val linhaTexto: String)

data class LinhaNaoEntendida(val linha: Int, val texto: String)

data class Extracao(val pares: List<ParExtraido>, val naoEntendi: List<LinhaNaoEntendida>)

/**
 * Transforma texto de arquivo (.md/.txt) em pares chave: valor SEM INVENTAR: só sai o que
 * tem forma reconhecível (email, telefone, linkedin), par explícito "chave: valor", e nome
 * apenas na primeira linha de conteúdo ou no primeiro título nível 1. Todo o resto cai em
 * "não entendi", para a pessoa decidir. Recebe String e devolve dados: por construção esta
 * classe não tem acesso a armazenamento nenhum — quem salva é a tela de confirmação, depois
 * do OK da pessoa.
 *
 * Chaves bilíngues por TABELA FIXA (telefone↔phone, cidade↔city, nome↔name), nunca por
 * tradução adivinhada: a tabela só liga palavras cujo significado é o mesmo dado, decidido
 * aqui em código e testado.
 */
object Extractor {

    private val RE_EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val RE_LINKEDIN =
        Regex("(?:https?://)?(?:[a-z]{2,3}\\.)?linkedin\\.com/in/[A-Za-z0-9\\-_%.]+", RegexOption.IGNORE_CASE)
    private val RE_FONE = Regex("[+(]?[0-9][0-9 ()\\-./]{5,}[0-9]")

    // "2020 - 2024" tem 8 dígitos e passaria por telefone; período de currículo não é fone.
    // (–/— são os traços longos que currículo real usa em período.)
    private val RE_PERIODO =
        Regex("^(?:19|20)\\d{2}\\s*(?:[-–—]|a|to|até)\\s*(?:19|20)\\d{2}$", RegexOption.IGNORE_CASE)
    private val RE_DATA = Regex("^\\d{1,4}[./-]\\d{1,2}[./-]\\d{1,4}$")

    // Tabela fixa de equivalência. A chave canônica gera as gêmeas; o Matcher NÃO casa
    // "phone" com "telefone" sozinho (idiomas diferentes), por isso as duas linhas existem.
    private val GEMEAS = mapOf(
        "email" to listOf("email"),
        "fone" to listOf("phone", "telefone"),
        "nome" to listOf("nome", "name"),
        "cidade" to listOf("city", "cidade"),
        "linkedin" to listOf("linkedin"),
        "primeiro" to listOf("first name", "primeiro nome"),
        "ultimo" to listOf("last name", "sobrenome"),
        // Entraram em 10/09 pelo teste REAL dele no Greenhouse: o formulário perguntava
        // "How many years of professional UX/UI experience" e "Are you currently located
        // in an EU country?" e o perfil respondia em português. Matcher.kt casa palavra a
        // palavra; sem a gêmea em inglês, dado que EXISTE vira "não tenho esse dado".
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

    // O que uma chave explícita escrita no arquivo significa (normalizada pelo Matcher).
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
     * As gêmeas de uma chave escrita à mão ou proposta pela IA. Fora da tabela, devolve a
     * própria chave (⛔ nunca traduz por conta própria: tradução adivinhada inventa dado).
     *
     * Existe pública por causa do 10/09: a IA lê um currículo em inglês e propõe chaves em
     * português (`anos_experiencia`), e o formulário pergunta em inglês. Sem passar por
     * aqui, o par entra no perfil e nunca casa com o campo.
     */
    fun bilingue(chave: String): List<String> {
        val canonica = CHAVE_PARA_CANONICA[Matcher.normalizar(chave)] ?: return listOf(chave)
        return GEMEAS[canonica] ?: listOf(chave)
    }

    private val CONECTIVOS = setOf("da", "de", "do", "dos", "das", "e", "van", "von", "del", "di", "la")

    // A primeira linha de muito currículo é o título do documento, não a pessoa.
    // Palavra funcional de documento em QUALQUER posição derruba ("Portfolio da Maria").
    private val NAO_E_NOME = setOf(
        "curriculum", "vitae", "curriculum vitae", "resume", "curriculo", "cv",
        "portfolio", "perfil", "profile",
    )

    fun extrair(texto: String): Extracao {
        val linhas = texto.lines()
        // chave normalizada -> par (primeiro vence; corrigir é papel da tela de confirmação)
        val pares = LinkedHashMap<String, ParExtraido>()
        val naoEntendi = mutableListOf<LinhaNaoEntendida>()
        val consumidas = mutableSetOf<Int>()

        // nome primeiro: a regra é POSICIONAL (primeira linha de conteúdo ou primeiro
        // título nível 1) e precisa rodar antes de a linha virar "não entendi".
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
            if (bruta.none { it.isLetterOrDigit() }) return@forEachIndexed // ───, ---, ***

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

    /** Emite a chave canônica e as gêmeas da tabela. true se ao menos uma entrou (valor
     *  igual já presente conta como entrou: a linha foi entendida, só era repetida). */
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
            // só estas DUAS posições podem dar nome; do meio do texto seria adivinhação
            if (primeiroH1 != null) break
        }
        for (idx in listOfNotNull(primeiraConteudo, primeiroH1).distinct()) {
            val limpo = semMarcadores(linhas[idx].trim())
            if (pareceNome(limpo)) return idx to limpo
        }
        return null
    }

    /** Forma de nome de gente, estrita de propósito: só letras (com acento), espaço e
     *  ponto de inicial; 2 a 5 palavras; cada uma capitalizada, toda maiúscula, ou
     *  conectivo. Errar pra menos cai em "não entendi"; errar pra mais inventa. */
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
            if (RE_DATA.matches(candidato)) continue // "10/09/2026"
            return candidato
        }
        return null
    }

    /** RÉGUA GERAL (brief 244): par de chave LIVRE só sai se o VALOR tiver cara de dado —
     *  até 5 palavras, sem pontuação de frase nem de lista (vírgula, ponto e vírgula,
     *  parêntese, ·, ponto final seguido de espaço). Prosa de currículo com dois-pontos
     *  no meio cai inteira em "não entendi", para a pessoa decidir; chave CANÔNICA fica
     *  isenta porque nomear o campo ("cidade:") é intenção explícita, não acaso de frase. */
    private fun pareceDado(valor: String): Boolean {
        if (valor.any { it in ",;()·" }) return false
        if (valor.contains(". ")) return false
        return valor.split(' ').count { it.isNotBlank() } <= 5
    }

    /** "chave: valor" explícito, com guardas contra frase com dois-pontos e URL. */
    private fun parExplicito(linha: String): Pair<String, String>? {
        val i = linha.indexOf(':')
        if (i !in 1..40) return null
        val chave = linha.substring(0, i).trim()
        val valor = linha.substring(i + 1).trim()
        if (chave.isBlank() || valor.isBlank()) return null
        if (valor.startsWith("//")) return null // "https://..." não é par
        if (valor.length > 200) return null // parágrafo com dois-pontos não é dado de perfil
        if (chave.split(' ').filter { it.isNotBlank() }.size > 5) return null
        if (chave.contains(". ")) return null // fim de frase dentro da "chave" = prosa, não dado
        if (chave.none { it.isLetter() }) return null // "14:30" não é par
        if (!chave.all { it.isLetter() || it.isDigit() || it == ' ' || it == '-' || it == '_' || it == '.' }) return null
        return chave to valor
    }

    /** Tira decoração de markdown que não é dado: marcador de lista, ênfase, título. */
    private fun semMarcadores(linha: String): String =
        linha
            .removePrefix("- ").removePrefix("* ").removePrefix("• ").removePrefix("· ")
            .replace("*", "").replace("`", "")
            .removePrefix("# ").removePrefix("## ").removePrefix("### ")
            .trim()
}
