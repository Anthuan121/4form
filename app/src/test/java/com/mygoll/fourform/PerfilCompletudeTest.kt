package com.mygoll.fourform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cobre a função pura por trás dos pilares do brief 256: vazio não vira gráfico, contato
 * sozinho preenche só a coluna Contact, e um perfil completo zera "No answer". Pessoa
 * inventada (nenhum dado real do dono entra aqui, mesma régua do ExtratorTest).
 */
class PerfilCompletudeTest {

    @Test
    fun `perfil vazio nao produz pilares`() {
        assertNull(PerfilCompletude.calcular(""))
        assertNull(PerfilCompletude.calcular("   \n   "))
    }

    @Test
    fun `perfil so com contato preenche so a coluna Contact`() {
        val p = PerfilCompletude.calcular(
            """
            email: maria@exemplo.com
            telefone: +353 83 000 0000
            linkedin: linkedin.com/in/maria-exemplo
            portfolio: maria.design
            """.trimIndent()
        )!!
        val contato = p.itens.first { it.rotulo == "Contact" }
        assertEquals("100%", contato.valor)
        assertEquals(1f, contato.fracao, 0.001f)

        val experiencia = p.itens.first { it.rotulo == "Experience" }
        val formacao = p.itens.first { it.rotulo == "Education" }
        assertEquals("0%", experiencia.valor)
        assertEquals("0%", formacao.valor)

        // areas vazias: Pessoal, Experiência, Formação (Contato tem dado) => 3
        val semResposta = p.itens.first { it.rotulo == "No answer" }
        assertEquals("3", semResposta.valor)
        assertTrue(semResposta.tracejado)
        assertEquals(3, p.lacunas)
    }

    @Test
    fun `perfil completo nas 4 areas zera os known gaps`() {
        val p = PerfilCompletude.calcular(
            """
            email: maria@exemplo.com
            telefone: +353 83 000 0000
            linkedin: linkedin.com/in/maria-exemplo
            portfolio: maria.design
            nome: Maria da Graça Boaventura
            sobrenome: Boaventura
            cidade: Dublin
            pais: Irlanda
            nacionalidade: Brasileira
            cargo: Product Designer
            empresa: Vertex Labs
            anos de experiencia: 8
            area de atuacao: Design de produto
            formacao: Bacharel em Design
            idiomas: Português, Inglês
            """.trimIndent()
        )!!
        assertEquals("100%", p.itens.first { it.rotulo == "Contact" }.valor)
        assertEquals("100%", p.itens.first { it.rotulo == "Experience" }.valor)
        assertEquals("100%", p.itens.first { it.rotulo == "Education" }.valor)
        assertEquals("0", p.itens.first { it.rotulo == "No answer" }.valor)
        assertEquals(0, p.lacunas)
    }

    @Test
    fun `nunca inventa 100 por cento pra area sem nenhuma chave conhecida`() {
        val p = PerfilCompletude.calcular("time favorito: Flamengo")!!
        assertEquals("0%", p.itens.first { it.rotulo == "Contact" }.valor)
        assertEquals(4, p.lacunas) // as 4 áreas ficam vazias, nenhuma inventada
    }
}
