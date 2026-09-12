package com.mygoll.funform.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A ponte entre idiomas (10/09). O caso REAL medido no aparelho dele, no Greenhouse:
 *
 *   [aberto] "How many years of professional UX/UI or product design experience"
 *            → "não tenho esse dado"
 *
 * O perfil TINHA o dado — como "anos de experiencia: 12". Matcher.kt casa palavra a
 * palavra e nenhuma palavra é comum entre as duas línguas. Dado que existe virava buraco,
 * e a IA era chamada para cobrir uma falha que não precisava existir.
 */
class BilingueTest {

    @Test
    fun `chave em portugues casa com rotulo em ingles pela tabela`() {
        val gemeas = Extractor.bilingue("anos_experiencia")
        assertTrue("precisa emitir a gêmea em inglês", gemeas.any { it.contains("years") })
        assertTrue("e manter a portuguesa", gemeas.any { it.contains("anos") })
        // o que o Greenhouse realmente perguntou naquele teste
        assertTrue(
            "o rótulo real do formulário tem que casar",
            gemeas.any { Matcher.casa("How many years of experience", it) },
        )
    }

    @Test
    fun `o caminho inverso tambem vale - chave em ingles casa com campo em portugues`() {
        val gemeas = Extractor.bilingue("work authorisation")
        assertTrue(gemeas.any { Matcher.casa("Autorização de trabalho", it) })
    }

    @Test
    fun `pais casa com country - o outro campo que falhou no teste dele`() {
        val gemeas = Extractor.bilingue("pais")
        assertTrue(gemeas.any { Matcher.casa("Country", it) })
    }

    @Test
    fun `chave fora da tabela sai intacta - NUNCA traducao adivinhada`() {
        // inventar tradução é inventar dado: fora da tabela, a chave passa como veio
        assertEquals(listOf("hobbies favoritos"), Extractor.bilingue("hobbies favoritos"))
    }

    @Test
    fun `par proposto pela IA vira as duas gemeas no perfil`() {
        val linhas = listOf(
            LinhaNaoEntendida(8, "A designer for 12 years, the last 7+ focused on product and UX"),
        )
        val envelope = Result.success(
            "{\"choices\":[{\"message\":{\"content\":" +
                Json.str("{\"pares\":[{\"chave\":\"anos_experiencia\",\"valor\":\"12 years\",\"linha\":8}]}") +
                "}}]}"
        )
        val propostos = Llm.avaliarPerfil(envelope, linhas)
        assertTrue("uma proposta em pt vira duas chaves", propostos.size >= 2)
        assertTrue(propostos.any { it.chave.contains("years") })
        assertTrue(propostos.any { it.chave.contains("anos") })
        assertTrue("o valor é o mesmo nas duas", propostos.all { it.valor == "12 years" })
        assertTrue("a origem sobrevive", propostos.all { it.linha == 8 })
    }

    /**
     * 12/09, Ashby (Ceartas). Ele: *"quantos anos de experiência você tem na área, ele não
     * preencheu. Porra, ele TEM essa informação"*. E tinha mesmo.
     *
     * 🔴 A tabela bilíngue existia desde 10/09 mas só rodava na IMPORTAÇÃO do currículo.
     * No casamento com o campo, que é onde ela decide se preenche, Profile.valorPara usava
     * o Matcher cru. Dado presente virava buraco, e o campo ainda subia pra IA de graça.
     * Este teste prende a tabela no caminho do preenchimento.
     */
    @Test
    fun `o perfil em portugues responde a pergunta em ingles do formulario`() {
        val perfil = Profile("anos de experiencia: 12\nnome: Anthuan", emptyList())
        val achado = perfil.valorPara(
            "How many years of professional UX/UI or product design experience do you have?"
        )
        assertEquals("12", achado?.valor)
        assertEquals("perfil", achado?.fonte)
    }

    /** ⛔ E continua sem traduzir por conta própria: fora da tabela, não inventa gêmea. */
    @Test
    fun `chave fora da tabela nao ganha traducao adivinhada`() {
        val perfil = Profile("hobbies favoritos: violao", emptyList())
        assertEquals(null, perfil.valorPara("What are your favourite hobbies?")?.valor)
    }
}
