package com.mygoll.funform.core

data class Found(val valor: String, val fonte: String, val chaveCasada: String)

/**
 * O que o app sabe sobre a pessoa: texto colado por ela (uma linha "chave: valor" por dado)
 * + o que aprendeu observando. O APRENDIDO VENCE o texto base, e o mais recente vence o
 * mais antigo: correção do usuário é o sinal mais forte que existe.
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
     * Casa a chave do perfil E as gêmeas dela em outro idioma. Sem isto, o perfil dele diz
     * "anos de experiencia: 12", o Ashby pergunta "How many years of professional UX/UI or
     * product design experience do you have?" e nenhuma palavra coincide: dado que EXISTE
     * vira "não tenho esse dado", e o campo ainda sobe pra IA de graça.
     *
     * 🔴 A tabela bilíngue existia desde 10/09 mas só rodava na IMPORTAÇÃO do perfil
     * (Llm.kt, ao ler o currículo). Nunca tinha sido ligada aqui, no casamento com o campo,
     * que é onde ela decide se preenche ou não. Medido no aparelho dele em 12/09.
     */
    private fun casaBilingue(rotulo: String, chave: String): Boolean =
        Extractor.bilingue(chave).any { Matcher.casa(rotulo, it) }

    /**
     * Uma OPÇÃO de escolha (radio, checkbox) é o caso invertido: o rótulo da opção já é o
     * VALOR candidato, não a pergunta. "Especialista UX/UI" só deve ser marcado se isso
     * estiver declarado no perfil, nunca porque parece provável.
     *
     * Deliberadamente severo: exige que um valor do perfil e o rótulo da opção se contenham
     * depois de normalizados, com no mínimo 4 caracteres úteis. ⛔ Marcar de menos é um
     * campo em branco que a pessoa resolve em 1 toque; marcar de mais é uma afirmação falsa
     * enviada no nome dela, que é exatamente o que os concorrentes fazem (246) e o que este
     * produto promete não fazer. Os dois erros ⛔ NÃO têm o mesmo custo.
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
     * O caso que ele bateu de frente em 12/09: *"Você reside num país europeu?"* com opções
     * Sim/Não. Aqui a PERGUNTA é que casa com o perfil, e o rótulo da opção é só a resposta
     * candidata — invertido em relação a opcaoBateComPerfil, onde a opção já é o valor.
     *
     * Sem isto, o app comparava "Yes" com o perfil e nunca casava: nenhum perfil contém
     * "Yes". ⛔ Continua não inventando: só responde quando o perfil tem uma linha para
     * ESTA pergunta. O que não estiver declarado segue em branco.
     */
    fun respostaParaEscolha(pergunta: String, rotuloOpcao: String): Found? {
        val achado = valorPara(pergunta) ?: return null
        val valor = normalizar(achado.valor)
        val opcao = normalizar(rotuloOpcao)
        if (opcao.isEmpty()) return null
        val bate = when {
            // sim/não é a resposta mais comum e atravessa idioma: o perfil dele é em
            // português e o formulário da vaga, em inglês.
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
