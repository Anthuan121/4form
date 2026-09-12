package com.mygoll.fourform.agent

import com.mygoll.fourform.scan.Json
import com.mygoll.fourform.scan.LinhaNaoEntendida
import com.mygoll.fourform.scan.Choice
import com.mygoll.fourform.scan.Learned
import com.mygoll.fourform.scan.Matcher
import com.mygoll.fourform.scan.Extractor

/**
 * A resposta de pergunta aberta (brief 245): campo que a escada identificou mas o perfil
 * não responde deixa de morrer em "não tenho esse dado" e vira pergunta à LLM da casa,
 * ANCORADA no perfil. A régua anti-invenção é o produto, não o freio: resposta sem
 * âncora, ou com âncora que não existe no perfil, é tratada como invenção e rebaixada
 * para campo aberto. E resposta de LLM NUNCA é escrita sem toque da pessoa.
 *
 * (English, brief 259): this is the most important rule change in the product. The anchor
 * stopped being TEXT EQUALITY ("the value appears literally in the profile") and became
 * PROVENANCE ("which profile line the AI derived the answer from, and that line is real").
 * Why: a real form had 30 readable options and the app checked ZERO, because "5+ years"
 * does not exist literally in a CV that says "12 years... the last 7+...". The AI is now
 * allowed to INTERPRET (convert a unit, pick the covering range, translate a level, decide
 * yes/no from a stated fact). What the app validates is whether the cited source line is
 * real, not whether the answer is a copy of it.
 *
 * Este arquivo é PURO (testável em JVM): candidatura, corpo da requisição, validação da
 * resposta e aplicação do veredito. A rede vive em LlmBridge.kt, fina de propósito.
 */
object Llm {

    const val MODELO = "nina-default"

    // ⛔ A URL DA PONTE NÃO MORA MAIS AQUI (10/09, véspera da submissão). Ela carrega o
    // segredo que autentica o app, e o repo pode ir público. Agora vem de local.properties
    // (que está no .gitignore) → BuildConfig → LlmBridge.kt, que é código de app.
    //
    // 🎓 Por que não basta mover para uma constante em outro arquivo: `nucleo/` é compilado
    // e testado SEM o framework Android, e é isso que faz os 120 testes rodarem em
    // milissegundos. Uma referência a BuildConfig aqui arrastaria o Android para dentro do
    // núcleo. Então quem conhece a URL é quem já fala com a rede: LlmBridge.

    /**
     * (English) `literal=true` when the answer is a copy of the cited line itself;
     * `false` when the AI DERIVED it (converted a unit, picked a range, translated a
     * level, answered yes/no from a stated fact). Brief 259: the rule stopped requiring
     * equality and started requiring PROVENANCE; this field is only what the diagnostic
     * uses to measure the effect of the change, NEVER a second validation gate (the gate
     * is `ancoraExiste`).
     */
    data class Sugestao(val resposta: String, val ancora: String, val confianca: String, val literal: Boolean = true)

    sealed class Veredito {
        data class Responder(val sugestao: Sugestao) : Veredito()

        /** doModelo=true quando foi a PRÓPRIA LLM que disse "não dá": aí o motivo dela vira o motivo do campo. */
        data class NaoResponder(val motivo: String, val doModelo: Boolean) : Veredito()
    }

    /** O que do episódio entra no diagnóstico: desfecho, confiança e tempo. NUNCA a resposta nem a âncora. */
    data class LlmDiagnostico(val desfecho: String, val confianca: String?, val latenciaMs: Long?)

    /**
     * Quem pode virar pergunta: campo que ficou ABERTO com rótulo confiável e sem dado no
     * perfil. Senha e campo com texto digitado NUNCA (nem o rótulo da senha sobe);
     * viewId-cru não é pergunta; campo que recusou escrita não adianta responder; e o que
     * a pessoa desfez não volta pela porta da IA.
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
     * As linhas de perfil que sobem no prompt: aprendidos (o mais recente por rótulo)
     * VENCEM e ESCONDEM a linha base equivalente — se a pessoa corrigiu "cidade" uma vez,
     * a LLM nunca mais vê o valor velho. É a precedência do 3º ato dentro do prompt.
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
     * O corpo do POST. Recebe SÓ o rótulo do campo (público: está escrito na página) e as
     * linhas do perfil que a pessoa confirmou — por construção, valor digitado na tela e
     * qualquer coisa de senha não têm como entrar aqui.
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
            // (English) Brief 259: measured on the real device, a form had 30 readable
            // options and the app checked ZERO because the profile did not have the exact
            // string. "5+ years" is not in the CV, but "12 years... the last 7+..." proves
            // that range. The old gate demanded equality; the new gate demands PROVENANCE
            // (which line the answer came from).
            append("- Você PODE INTERPRETAR o perfil, não só copiar: converter unidade (ex.: \"12 years\" → \"5+ years\"), ")
            append("escolher a faixa/nível mais próxima do que o perfil diz, traduzir um nível de proficiência, ")
            append("ou responder sim/não a partir de um fato declarado. Interpretar não é inventar: a resposta ")
            append("continua tendo que vir de uma linha REAL do perfil, nunca de um fato que não está lá.\n")
            // Found dele em 10/09: o perfil pode estar numa língua e o formulário em outra
            // (perfil em português, Greenhouse em inglês). Quem manda é o RÓTULO do campo,
            // não a língua do perfil nem a do aparelho — o mesmo perfil serve a qualquer país.
            append("- IDIOMA: responda no idioma do RÓTULO do campo, mesmo que o perfil esteja em outro. ")
            append("Se o perfil diz \"nao\" e o campo pergunta em inglês, a resposta é \"No\".\n")
            append("- ⛔ NUNCA traduza nome de pessoa, e-mail, telefone, URL, nome de empresa ou de instituição: ")
            append("copie exatamente como estão no perfil. Traduza só o CONTEÚDO (cargo, descrição, sim/não, texto aberto).\n")
            append("- \"ancora\": cópia EXATA da linha do perfil que SUSTENTA a resposta, mesmo quando a resposta foi ")
            append("interpretada e não copiada (ex.: perfil \"12 years in design, the last 7+ in product\", resposta ")
            append("\"5+ years\", ancora é a linha do perfil, NUNCA a resposta). Obrigatória quando pode_responder é true.\n")
            append("- Se o perfil não tem o dado, pode_responder é false e \"resposta\" explica em poucas palavras o que falta.\n")
            append("- Nunca invente fato que não esteja no perfil.")
        }
        return "{\"model\": ${Json.str(MODELO)}, \"max_tokens\": 400, " +
            "\"messages\": [{\"role\": \"user\", \"content\": ${Json.str(prompt)}}]}"
    }

    /**
     * Da resposta HTTP (ou da falha) ao veredito. NUNCA lança: qualquer coisa ilegível,
     * cortada ou sem âncora degrada para NaoResponder — o campo continua aberto,
     * exatamente como hoje. Tolerante a cerca ```json e a texto em volta (MEDIDO no teste
     * real de 10/09, não hipótese).
     */
    fun avaliar(resultadoHttp: Result<String>, linhasDePerfil: List<String>): Veredito {
        val bruto = resultadoHttp.getOrElse {
            return Veredito.NaoResponder("the AI didn't respond (${it.message ?: it.javaClass.simpleName})", doModelo = false)
        }
        return runCatching { avaliarConteudo(bruto, linhasDePerfil) }
            .getOrElse { Veredito.NaoResponder("the AI's response was unreadable", doModelo = false) }
    }

    private fun avaliarConteudo(bruto: String, linhas: List<String>): Veredito {
        // envelope OpenAI: choices[0].message.content — corte truncado é salvo parcialmente
        // (exigirFim=false) porque a validação estrita acontece no JSON de dentro
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
        val literal = Matcher.normalizar(ancora).contains(Matcher.normalizar(resposta))
        return Veredito.Responder(Sugestao(resposta, ancora, confianca, literal))
    }

    /**
     * Aplica o veredito ao registro e devolve o desfecho (insumo do diagnóstico).
     * ⛔ NUNCA escreve no campo nem muda acao: escrever é decisão da pessoa, no painel.
     * Falha de rede/parse deixa o campo EXATAMENTE como estava (motivo original intocado).
     */
    fun aplicar(reg: Session.Registro, veredito: Veredito, sugestoes: MutableMap<String, Sugestao>): String =
        when (veredito) {
            is Veredito.Responder -> {
                sugestoes[reg.campo.chave] = veredito.sugestao
                // (English) literal vs derived is the MEASUREMENT that brief 259 asks for:
                // without it there is no way to tell whether the new rule (provenance)
                // improved or worsened the answer rate.
                if (veredito.sugestao.literal) "sugeriu_literal" else "sugeriu_derivada"
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
    // CHOICE WITH PROVENANCE (brief 259, the same rule applied to radio/checkbox/select)
    //
    // Difference from corpo()/avaliar(): here there is a SECOND gate that never loosens.
    // The marked option has to be an EXACT copy of one of the options shown on screen.
    // Deriving is allowed for the REASONING ("12 years... 7+..." -> "5+ years"), never for
    // inventing an option the screen did not offer. Both gates (option literally in the
    // list, plus the anchor existing in the profile) are mandatory; the second never
    // replaces the first.
    // ─────────────────────────────────────────────────────────────────────────────


    /**
     * Groups the choices of a screen by the question text above them. The tree gives us no
     * group id, so the question IS the group key: options that answer the same question sit
     * under the same enunciado. Only options we can actually act on enter the group.
     */
    fun agruparPorPergunta(escolhas: List<Choice>): Map<String, List<Choice>> =
        escolhas
            .filter { it.clicavel && !it.marcada && !it.rotulo.isNullOrBlank() && !it.pergunta.isNullOrBlank() }
            .groupBy { it.pergunta!!.trim() }

    fun corpoEscolha(pergunta: String, opcoes: List<String>, linhasDePerfil: List<String>): String {
        val prompt = buildString {
            append("Você é o Preenche, um agente que marca opções de formulário SEM INVENTAR nada sobre a pessoa.\n")
            append("Profile confirmado pela pessoa, uma linha \"chave: valor\" por dado:\n")
            linhasDePerfil.forEach { append(it).append('\n') }
            append("\nPergunta do formulário: \"").append(pergunta).append("\"\n")
            append("Opções desta tela (escolha UMA, cópia EXATA de uma delas):\n")
            opcoes.forEach { append("- ").append(it).append('\n') }
            append("\nResponda SOMENTE com um JSON, sem texto em volta:\n")
            append("{\"pode_responder\": true|false, \"resposta\": \"...\", \"ancora\": \"...\", \"confianca\": \"alta|media|baixa\"}\n")
            append("- \"resposta\": cópia EXATA de uma das opções listadas acima. ⛔ NUNCA escreva uma opção que não está na lista.\n")
            append("- Você PODE INTERPRETAR para escolher a opção certa: converter unidade, escolher a faixa que cobre ")
            append("o valor real do perfil, traduzir nível, ou decidir sim/não a partir de um fato declarado.\n")
            append("- \"ancora\": cópia EXATA da linha do perfil que SUSTENTA a escolha, mesmo quando a opção foi ")
            append("interpretada e não copiada do perfil. Obrigatória quando pode_responder é true.\n")
            append("- Se nenhuma opção pode ser sustentada por uma linha real do perfil, pode_responder é false.\n")
            append("- Nunca invente fato que não esteja no perfil.")
        }
        return "{\"model\": ${Json.str(MODELO)}, \"max_tokens\": 400, " +
            "\"messages\": [{\"role\": \"user\", \"content\": ${Json.str(prompt)}}]}"
    }

    /** Same failure tolerance as avaliar(): anything unreadable degrades to NaoResponder, never throws. */
    fun avaliarEscolha(resultadoHttp: Result<String>, opcoes: List<String>, linhasDePerfil: List<String>): Veredito {
        val bruto = resultadoHttp.getOrElse {
            return Veredito.NaoResponder("the AI didn't respond (${it.message ?: it.javaClass.simpleName})", doModelo = false)
        }
        return runCatching { avaliarEscolhaConteudo(bruto, opcoes, linhasDePerfil) }
            .getOrElse { Veredito.NaoResponder("the AI's response was unreadable", doModelo = false) }
    }

    private fun avaliarEscolhaConteudo(bruto: String, opcoes: List<String>, linhas: List<String>): Veredito {
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
        // gate 1, non-negotiable: the option has to be one the screen actually offered.
        val opcaoBatida = opcoes.firstOrNull { Matcher.normalizar(it) == Matcher.normalizar(resposta) }
            ?: return Veredito.NaoResponder("the AI picked an option that wasn't on screen: treated as invention", doModelo = false)
        val ancora = lerString(json, "ancora", exigirFim = true)?.takeIf { it.isNotBlank() }
            ?: return Veredito.NaoResponder("answer with no anchor in your profile: treated as invention", doModelo = false)
        // gate 2: the declared provenance has to actually exist.
        if (!ancoraExiste(ancora, linhas)) {
            return Veredito.NaoResponder("the cited anchor doesn't exist in your profile: treated as invention", doModelo = false)
        }
        val confianca = lerString(json, "confianca", exigirFim = true)?.lowercase()
            ?.takeIf { it == "alta" || it == "media" || it == "baixa" } ?: "baixa"
        val literal = Matcher.normalizar(ancora).contains(Matcher.normalizar(opcaoBatida))
        return Veredito.Responder(Sugestao(opcaoBatida, ancora, confianca, literal))
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // LEITURA DO CURRÍCULO (10/09, achado dele no aparelho: 55 linhas em "não entendi")
    //
    // O Extractor (244) é conservador de propósito: só vira par o que TEM CARA de dado
    // (curto, sem vírgula, sem ponto final). Isso derrubou o lixo de 5 pares por currículo
    // para 0 — e, no mesmo movimento, deixou "Product Designer · Banking · Insurance" e
    // "EU citizen (Italian)" paradas em "não entendi". Régua não distingue as duas: essa
    // é a fronteira onde só interpretação separa dado de prosa.
    //
    // ⛔ A régua do Extractor FICA. Ela é a rede quando a IA está fora do ar. Isto aqui é
    // um degrau OPCIONAL por cima, e continua caindo na mesma tela de confirmação.
    // ─────────────────────────────────────────────────────────────────────────────

    /** Um par que a IA propôs a partir de uma linha do documento. `linha` é a origem, para conferência. */
    data class ParProposto(val chave: String, val valor: String, val linha: Int, val origem: String)

    /** Teto de linhas por chamada: currículo grande não vira requisição gigante nem resposta cortada. */
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
     * Da resposta HTTP à lista de pares propostos. NUNCA lança: qualquer falha devolve
     * lista vazia e a tela continua exatamente como está hoje (as linhas em "não entendi",
     * promovíveis a dedo). Mesma régua anti-invenção da âncora: **o valor tem que existir
     * na linha que a IA citou**. Se não existe, é fabricação e o par é descartado.
     */
    fun avaliarPerfil(resultadoHttp: Result<String>, linhas: List<LinhaNaoEntendida>): List<ParProposto> =
        runCatching { avaliarPerfilConteudo(resultadoHttp.getOrThrow(), linhas) }.getOrElse { emptyList() }

    private fun avaliarPerfilConteudo(bruto: String, linhas: List<LinhaNaoEntendida>): List<ParProposto> {
        val conteudo = lerString(bruto, "content", exigirFim = false) ?: return emptyList()
        val porNumero = linhas.associateBy { it.linha }
        val vistos = LinkedHashSet<String>()
        val saida = mutableListOf<ParProposto>()
        // um objeto por par; o último pode vir cortado e é simplesmente ignorado
        for (m in Regex("\\{[^{}]*\\}").findAll(conteudo)) {
            val bloco = m.value
            val chave = lerString(bloco, "chave", exigirFim = true)?.trim()?.lowercase() ?: continue
            val valor = lerString(bloco, "valor", exigirFim = true)?.trim() ?: continue
            val numero = Regex("\"linha\"\\s*:\\s*(\\d+)").find(bloco)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (chave.isEmpty() || valor.isEmpty()) continue
            val origem = porNumero[numero] ?: continue // citou linha que não mandamos: descarta
            if (!valorSaiuDaLinha(valor, origem.texto)) continue // fabricação: descarta
            // A IA lê currículo em inglês e propõe chave em português (MEDIDO em 10/09:
            // "anos_experiencia" saiu de um CV inteiro em inglês). O formulário pergunta em
            // inglês e o Matcher compara palavra a palavra: sem a gêmea, dado que EXISTE
            // vira "não tenho esse dado". Tabela FIXA, nunca tradução adivinhada.
            for (nome in Extractor.bilingue(chave)) {
                if (!vistos.add(Matcher.normalizar(nome) + " " + Matcher.normalizar(valor))) continue
                saida.add(ParProposto(nome, valor, numero, origem.texto.trim()))
            }
        }
        return saida
    }

    /** O valor precisa estar DENTRO do texto da linha citada. É a mesma prova da âncora, aplicada à leitura. */
    private fun valorSaiuDaLinha(valor: String, textoDaLinha: String): Boolean {
        val v = Matcher.normalizar(valor)
        val l = Matcher.normalizar(textoDaLinha)
        return v.isNotEmpty() && l.isNotEmpty() && l.contains(v)
    }

    /**
     * (English) This is the entire gate of the new rule (brief 259): it does NOT test
     * whether the ANSWER equals a profile line (that check died today, it is what made
     * "5+ years" fail against a CV that says "12 years... the last 7+..."). It tests
     * whether the declared PROVENANCE actually exists: the line the AI cited as the
     * source has to be in the profile that was sent (checked both ways, with slack for
     * whitespace/punctuation paraphrase). Cited a line that does not exist, it is
     * fabrication, the whole answer is discarded, even if the final text looks plausible.
     */
    private fun ancoraExiste(ancora: String, linhas: List<String>): Boolean {
        val a = Matcher.normalizar(ancora)
        if (a.isEmpty()) return false
        return linhas.any {
            val l = Matcher.normalizar(it)
            l.isNotEmpty() && (l.contains(a) || a.contains(l))
        }
    }

    /**
     * Lê o valor string da PRIMEIRA ocorrência de "chave": "..." respeitando escapes.
     * exigirFim=false devolve o que acumulou quando a string não fecha (JSON truncado);
     * exigirFim=true devolve null — meia resposta nunca entra num formulário.
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
                    else -> sb.append(e) // \" \\ \/ e qualquer escape desconhecido: o literal
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
