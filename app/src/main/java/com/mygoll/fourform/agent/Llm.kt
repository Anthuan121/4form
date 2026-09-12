package com.mygoll.fourform.agent

import com.mygoll.fourform.scan.Json
import com.mygoll.fourform.scan.LinhaNaoEntendida
import com.mygoll.fourform.scan.Learned
import com.mygoll.fourform.scan.Matcher
import com.mygoll.fourform.scan.Extractor

/**
 * The open-question answer (brief 245): a field the ladder identified but the profile
 * doesn't answer no longer dies as "I don't have this data" and instead becomes a
 * question to the house LLM, ANCHORED to the profile. The anti-invention rule is the
 * product, not the brake: an answer with no anchor, or with an anchor that doesn't exist
 * in the profile, is treated as invention and downgraded to an open field. And an LLM
 * answer is NEVER written without the person's tap.
 *
 * This file is PURE (testable in a JVM): candidacy, request body, response validation,
 * and applying the verdict. Networking lives in LlmBridge.kt, thin on purpose.
 */
object Llm {

    const val MODELO = "nina-default"

    // ⛔ THE BRIDGE URL NO LONGER LIVES HERE (09/10, the eve of submission). It carries the
    // secret that authenticates the app, and the repo can go public. It now comes from
    // local.properties (which is in .gitignore) -> BuildConfig -> LlmBridge.kt, which is app code.
    //
    // 🎓 Why moving it to a constant in another file isn't enough: `nucleo/` is compiled
    // and tested WITHOUT the Android framework, and that's what makes the 120 tests run
    // in milliseconds. A reference to BuildConfig here would drag Android into the core.
    // So whoever already talks to the network knows the URL: LlmBridge.

    data class Sugestao(val resposta: String, val ancora: String, val confianca: String)

    sealed class Veredito {
        data class Responder(val sugestao: Sugestao) : Veredito()

        /** doModelo=true when the LLM ITSELF said "can't": then its reason becomes the field's reason. */
        data class NaoResponder(val motivo: String, val doModelo: Boolean) : Veredito()
    }

    /** What from the episode goes into the diagnostic: outcome, confidence, and time. NEVER the answer or the anchor. */
    data class LlmDiagnostico(val desfecho: String, val confianca: String?, val latenciaMs: Long?)

    /**
     * What can become a question: a field that stayed OPEN with a reliable label and no
     * data in the profile. Password and a field with typed text NEVER (not even the
     * password's label goes up); a raw viewId isn't a question; a field that refused a
     * write isn't worth answering; and whatever the person undid doesn't come back
     * through the AI's door.
     */
    fun candidato(r: Session.Registro): Boolean =
        r.acao == "aberto" &&
            !r.campo.senha &&
            r.campo.textoAtual.isNullOrBlank() &&
            r.rotulo != null &&
            r.origemRotulo != null && r.origemRotulo != "viewId-cru" &&
            r.motivo != "the field refused the write" &&
            r.motivo != "you undid it"

    fun candidatos(registros: List<Session.Registro>): List<Session.Registro> =
        registros.filter { candidato(it) }

    /**
     * The profile lines that go into the prompt: learned entries (the most recent per
     * label) WIN and HIDE the equivalent base line. If the person corrected "cidade"
     * once, the LLM never sees the old value again. It's the 3rd act's precedence,
     * carried into the prompt.
     */
    fun linhasDePerfil(textoBase: String, aprendidos: List<Learned>): List<String> {
        val vivos = aprendidos.sortedByDescending { it.quandoMs }
            .distinctBy { Matcher.normalizar(it.rotulo) }
        val base = textoBase.lines().mapNotNull { linha ->
            val i = linha.indexOf(':')
            if (i <= 0) return@mapNotNull null
            val chave = linha.substring(0, i).trim()
            val valor = linha.substring(i + 1).trim()
            if (chave.isBlank() || valor.isBlank()) null else chave to valor
        }.filter { (chave, _) -> vivos.none { Matcher.casa(chave, it.rotulo) } }
        return vivos.map { "${it.rotulo}: ${it.valor}" } + base.map { "${it.first}: ${it.second}" }
    }

    /**
     * The POST body. Receives ONLY the field's label (public: it's written on the page)
     * and the profile lines the person confirmed. By construction, a value typed on
     * screen and anything from a password have no way to get in here.
     */
    fun corpo(rotulo: String, linhasDePerfil: List<String>): String {
        val prompt = buildString {
            append("Você é o Preenche, um agente que preenche formulários SEM INVENTAR nada sobre a pessoa.\n")
            append("Profile confirmado pela pessoa, uma linha \"chave: valor\" por dado:\n")
            linhasDePerfil.forEach { append(it).append('\n') }
            append("\nCampo do formulário a responder: \"").append(rotulo).append("\"\n\n")
            append("Responda SOMENTE com um JSON, sem texto em volta:\n")
            append("{\"pode_responder\": true|false, \"resposta\": \"...\", \"ancora\": \"...\", \"confianca\": \"alta|media|baixa\"}\n")
            append("- \"resposta\": o texto pronto para entrar no campo, redigido SÓ a partir do perfil, no idioma do campo.\n")
            // His finding on 09/10: the profile can be in one language and the form in
            // another (profile in Portuguese, Greenhouse in English). What decides is the
            // field's LABEL, not the profile's language or the device's. The same profile
            // serves any country.
            append("- IDIOMA: responda no idioma do RÓTULO do campo, mesmo que o perfil esteja em outro. ")
            append("Se o perfil diz \"nao\" e o campo pergunta em inglês, a resposta é \"No\".\n")
            append("- ⛔ NUNCA traduza nome de pessoa, e-mail, telefone, URL, nome de empresa ou de instituição: ")
            append("copie exatamente como estão no perfil. Traduza só o CONTEÚDO (cargo, descrição, sim/não, texto aberto).\n")
            append("- \"ancora\": cópia EXATA da linha do perfil de onde a resposta saiu. Obrigatória quando pode_responder é true.\n")
            append("- Se o perfil não tem o dado, pode_responder é false e \"resposta\" explica em poucas palavras o que falta.\n")
            append("- Nunca invente fato que não esteja no perfil.")
        }
        return "{\"model\": ${Json.str(MODELO)}, \"max_tokens\": 400, " +
            "\"messages\": [{\"role\": \"user\", \"content\": ${Json.str(prompt)}}]}"
    }

    /**
     * From the HTTP response (or the failure) to the verdict. NEVER throws: anything
     * unreadable, cut off, or with no anchor degrades to NaoResponder. The field stays
     * open, exactly as it does today. Tolerant of a ```json fence and surrounding text
     * (MEASURED in the real 09/10 test, not a hypothesis).
     */
    fun avaliar(resultadoHttp: Result<String>, linhasDePerfil: List<String>): Veredito {
        val bruto = resultadoHttp.getOrElse {
            return Veredito.NaoResponder("the AI didn't respond (${it.message ?: it.javaClass.simpleName})", doModelo = false)
        }
        return runCatching { avaliarConteudo(bruto, linhasDePerfil) }
            .getOrElse { Veredito.NaoResponder("the AI's response was unreadable", doModelo = false) }
    }

    private fun avaliarConteudo(bruto: String, linhas: List<String>): Veredito {
        // OpenAI envelope: choices[0].message.content. A truncated cut is partially saved
        // (exigirFim=false) because strict validation happens on the JSON inside
        val conteudo = lerString(bruto, "content", exigirFim = false)
            ?: return Veredito.NaoResponder("the AI responded with no content", doModelo = false)
        val i = conteudo.indexOf('{')
        if (i < 0) return Veredito.NaoResponder("the AI didn't return the expected JSON", doModelo = false)
        val json = conteudo.substring(i)
        val pode = Regex("\"pode_responder\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1)
            ?: return Veredito.NaoResponder("the AI didn't return the expected JSON", doModelo = false)
        if (pode == "false") {
            val motivo = lerString(json, "resposta", exigirFim = true)?.takeIf { it.isNotBlank() }
            return Veredito.NaoResponder(motivo ?: "the AI said your profile doesn't have this", doModelo = true)
        }
        val resposta = lerString(json, "resposta", exigirFim = true)?.takeIf { it.isNotBlank() }
            ?: return Veredito.NaoResponder("the AI's answer came back empty or cut off", doModelo = false)
        val ancora = lerString(json, "ancora", exigirFim = true)?.takeIf { it.isNotBlank() }
            ?: return Veredito.NaoResponder("answer with no anchor in your profile: treated as invention", doModelo = false)
        if (!ancoraExiste(ancora, linhas)) {
            return Veredito.NaoResponder("the cited anchor doesn't exist in your profile: treated as invention", doModelo = false)
        }
        val confianca = lerString(json, "confianca", exigirFim = true)?.lowercase()
            ?.takeIf { it == "alta" || it == "media" || it == "baixa" } ?: "baixa"
        return Veredito.Responder(Sugestao(resposta, ancora, confianca))
    }

    /**
     * Applies the verdict to the record and returns the outcome (input for the
     * diagnostic). ⛔ NEVER writes to the field or changes acao: writing is the person's
     * decision, in the panel. A network/parse failure leaves the field EXACTLY as it was
     * (original reason untouched).
     */
    fun aplicar(reg: Session.Registro, veredito: Veredito, sugestoes: MutableMap<String, Sugestao>): String =
        when (veredito) {
            is Veredito.Responder -> {
                sugestoes[reg.campo.chave] = veredito.sugestao
                "sugeriu"
            }
            is Veredito.NaoResponder ->
                if (veredito.doModelo) {
                    reg.motivo = "AI: ${veredito.motivo}"
                    "modelo_nao_respondeu"
                } else {
                    "falhou"
                }
        }

    // ─────────────────────────────────────────────────────────────────────────────
    // RESUME READING (09/10, his finding on the device: 55 lines in "didn't understand")
    //
    // The Extractor (244) is conservative on purpose: only turns into a pair what LOOKS
    // LIKE data (short, no comma, no period). That dropped the junk from 5 pairs per
    // resume to 0, and in the same move left "Product Designer · Banking · Insurance"
    // and "EU citizen (Italian)" stuck in "didn't understand". The rule doesn't tell the
    // two apart: that's the boundary where only interpretation separates data from prose.
    //
    // ⛔ The Extractor's rule STAYS. It's the safety net when the AI is offline. This here
    // is an OPTIONAL step on top, and it still lands on the same confirmation screen.
    // ─────────────────────────────────────────────────────────────────────────────

    /** A pair the AI proposed from a line in the document. `linha` is the source, for review. */
    data class ParProposto(val chave: String, val valor: String, val linha: Int, val origem: String)

    /** Line cap per call: a large resume doesn't become a giant request or a cut-off response. */
    const val MAX_LINHAS_PERFIL = 120

    fun corpoPerfil(linhas: List<LinhaNaoEntendida>): String {
        val usadas = linhas.take(MAX_LINHAS_PERFIL)
        val prompt = buildString {
            append("Você lê currículos e extrai DADOS DE CADASTRO. Não resuma, não escreva prosa.\n")
            append("Abaixo, linhas numeradas de um currículo que o extrator automático não classificou.\n\n")
            usadas.forEach { append("L").append(it.linha).append(": ").append(it.texto.trim()).append('\n') }
            append("\nDevolva SOMENTE este JSON, sem texto em volta:\n")
            append("{\"pares\": [{\"chave\": \"...\", \"valor\": \"...\", \"linha\": 0}]}\n")
            append("- Extraia só o que é DADO da pessoa: cargo, anos de experiência, cidade, país, ")
            append("nacionalidade, autorização de trabalho, idiomas, ferramentas, área de atuação, formação.\n")
            append("- \"valor\": copie do texto da linha. ⛔ NÃO invente, ⛔ não deduza, ⛔ não traduza, ⛔ não resuma.\n")
            append("- \"linha\": o número L da linha de onde o valor saiu. Obrigatório.\n")
            append("- Linha que é frase de marketing, título de seção, data, separador ou descrição de tarefa: ")
            append("simplesmente NÃO devolva. Devolver menos é melhor que devolver errado.\n")
            append("- Uma chave por dado, em minúsculas, curta (ex: \"cargo\", \"cidade\", \"anos de experiencia\").\n")
            append("- Se duas linhas trazem valores diferentes para a MESMA chave, devolva as duas: ")
            append("a pessoa decide na tela. ⛔ Não escolha por ela e ⛔ não junte as duas num valor só.")
        }
        return "{\"model\": ${Json.str(MODELO)}, \"max_tokens\": 1200, " +
            "\"messages\": [{\"role\": \"user\", \"content\": ${Json.str(prompt)}}]}"
    }

    /**
     * From the HTTP response to the list of proposed pairs. NEVER throws: any failure
     * returns an empty list and the screen stays exactly as it is today (the "didn't
     * understand" lines, promotable by hand). Same anti-invention rule as the anchor:
     * **the value has to exist in the line the AI cited**. If it doesn't, it's
     * fabrication and the pair is discarded.
     */
    fun avaliarPerfil(resultadoHttp: Result<String>, linhas: List<LinhaNaoEntendida>): List<ParProposto> =
        runCatching { avaliarPerfilConteudo(resultadoHttp.getOrThrow(), linhas) }.getOrElse { emptyList() }

    private fun avaliarPerfilConteudo(bruto: String, linhas: List<LinhaNaoEntendida>): List<ParProposto> {
        val conteudo = lerString(bruto, "content", exigirFim = false) ?: return emptyList()
        val porNumero = linhas.associateBy { it.linha }
        val vistos = LinkedHashSet<String>()
        val saida = mutableListOf<ParProposto>()
        // one object per pair; the last one can arrive cut off and is simply ignored
        for (m in Regex("\\{[^{}]*\\}").findAll(conteudo)) {
            val bloco = m.value
            val chave = lerString(bloco, "chave", exigirFim = true)?.trim()?.lowercase() ?: continue
            val valor = lerString(bloco, "valor", exigirFim = true)?.trim() ?: continue
            val numero = Regex("\"linha\"\\s*:\\s*(\\d+)").find(bloco)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (chave.isEmpty() || valor.isEmpty()) continue
            val origem = porNumero[numero] ?: continue // cited a line we didn't send: discard
            if (!valorSaiuDaLinha(valor, origem.texto)) continue // fabrication: discard
            // The AI reads an English resume and proposes a Portuguese key (MEASURED on
            // 09/10: "anos_experiencia" came out of a resume entirely in English). The
            // form asks in English and the Matcher compares word by word: without the
            // twin, data that EXISTS turns into "I don't have this data". A FIXED table,
            // never guessed translation.
            for (nome in Extractor.bilingue(chave)) {
                if (!vistos.add(Matcher.normalizar(nome) + " " + Matcher.normalizar(valor))) continue
                saida.add(ParProposto(nome, valor, numero, origem.texto.trim()))
            }
        }
        return saida
    }

    /** The value needs to be INSIDE the cited line's text. Same proof as the anchor, applied to reading. */
    private fun valorSaiuDaLinha(valor: String, textoDaLinha: String): Boolean {
        val v = Matcher.normalizar(valor)
        val l = Matcher.normalizar(textoDaLinha)
        return v.isNotEmpty() && l.isNotEmpty() && l.contains(v)
    }

    /** The anchor has to EXIST in the profile (either direction, with some shape slack): otherwise it's fabrication. */
    private fun ancoraExiste(ancora: String, linhas: List<String>): Boolean {
        val a = Matcher.normalizar(ancora)
        if (a.isEmpty()) return false
        return linhas.any {
            val l = Matcher.normalizar(it)
            l.isNotEmpty() && (l.contains(a) || a.contains(l))
        }
    }

    /**
     * Reads the string value from the FIRST occurrence of "key": "..." respecting escapes.
     * exigirFim=false returns what was accumulated when the string doesn't close
     * (truncated JSON); exigirFim=true returns null. Half an answer never goes into a form.
     */
    private fun lerString(texto: String, chave: String, exigirFim: Boolean): String? {
        val m = Regex("\"${Regex.escape(chave)}\"\\s*:\\s*\"").find(texto) ?: return null
        val sb = StringBuilder()
        var i = m.range.last + 1
        while (i < texto.length) {
            val c = texto[i]
            if (c == '"') return sb.toString()
            if (c == '\\' && i + 1 < texto.length) {
                when (val e = texto[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'u' -> {
                        if (i + 5 < texto.length) {
                            texto.substring(i + 2, i + 6).toIntOrNull(16)?.let { sb.append(it.toChar()) }
                            i += 4
                        }
                    }
                    else -> sb.append(e) // \" \\ \/ and any unknown escape: the literal
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return if (exigirFim) null else sb.toString()
    }
}
