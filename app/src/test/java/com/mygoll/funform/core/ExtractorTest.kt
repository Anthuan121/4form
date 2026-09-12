package com.mygoll.funform.core

import com.mygoll.funform.Store
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O contrato do brief 243: extrair sem inventar, mesclar sem apagar, apagar de verdade.
 * Pessoa 100% inventada (a Maria do roteiro); nenhum dado real entra aqui.
 */
class ExtratorTest {

    private fun chaves(e: Extracao) = e.pares.map { it.chave }.toSet()
    private fun valor(e: Extracao, chave: String) = e.pares.first { it.chave == chave }.valor

    // ---- degrau 1: .md estruturado, o formato que uma IA gera ----

    @Test
    fun mdEstruturadoViraPerfilCorreto() {
        val e = Extractor.extrair(
            """
            # Maria da Graça Boaventura

            email: maria.boaventura@exemplo.com
            telefone: +353 83 000 0000
            cidade: Dublin
            linkedin: linkedin.com/in/maria-exemplo
            cargo pretendido: Product Designer
            """.trimIndent()
        )
        assertEquals("Maria da Graça Boaventura", valor(e, "nome"))
        assertEquals("Maria da Graça Boaventura", valor(e, "name"))
        assertEquals("Maria", valor(e, "first name"))
        assertEquals("Boaventura", valor(e, "last name"))
        assertEquals("maria.boaventura@exemplo.com", valor(e, "email"))
        assertEquals("+353 83 000 0000", valor(e, "phone"))
        assertEquals("+353 83 000 0000", valor(e, "telefone"))
        assertEquals("Dublin", valor(e, "city"))
        assertEquals("Dublin", valor(e, "cidade"))
        assertEquals("linkedin.com/in/maria-exemplo", valor(e, "linkedin"))
        // chave livre fica como a pessoa escreveu, sem tradução adivinhada
        assertEquals("Product Designer", valor(e, "cargo pretendido"))
        assertTrue(e.naoEntendi.isEmpty())
    }

    @Test
    fun todoParExtraidoCarregaALinhaDeOrigem() {
        val e = Extractor.extrair("email: maria@exemplo.com")
        val par = e.pares.first { it.chave == "email" }
        assertEquals(1, par.linha)
        assertTrue(par.linhaTexto.contains("maria@exemplo.com"))
    }

    // ---- .txt corrido: só o que tem forma sai, e MAIS NADA ----

    @Test
    fun txtCorridoExtraiEmailETelefoneEMaisNada() {
        val e = Extractor.extrair(
            """
            Sou uma profissional com dez anos de estrada em atendimento.
            Me escreva em maria.boaventura@exemplo.com ou ligue +353 83 000 0000.
            Moro perto do centro e gosto de trabalho híbrido.
            """.trimIndent()
        )
        assertEquals(setOf("email", "phone", "telefone"), chaves(e))
        assertEquals("maria.boaventura@exemplo.com", valor(e, "email"))
        assertEquals("+353 83 000 0000", valor(e, "phone"))
        assertEquals(2, e.naoEntendi.size) // as duas frases de prosa, intactas, pra pessoa decidir
    }

    @Test
    fun cidadeSoltaNaoViraCampo() {
        val e = Extractor.extrair("Contato geral\n\nDublin, Ireland")
        assertFalse("cidade por heurística é onde a invenção começa", "cidade" in chaves(e))
        assertFalse("city" in chaves(e))
        assertTrue(e.naoEntendi.any { it.texto.contains("Dublin") })
    }

    // ---- nome: posição estrita, nunca do meio ----

    @Test
    fun nomeNoMeioDoTextoNaoSai() {
        val e = Extractor.extrair(
            """
            Profile profissional resumido aqui.

            Maria da Graça Boaventura
            maria@exemplo.com
            """.trimIndent()
        )
        assertFalse("nome" in chaves(e))
        assertTrue(e.naoEntendi.any { it.texto.contains("Boaventura") })
    }

    @Test
    fun nomeSaiDaPrimeiraLinha() {
        val e = Extractor.extrair("Maria da Graça Boaventura\nmaria@exemplo.com")
        assertEquals("Maria da Graça Boaventura", valor(e, "nome"))
    }

    @Test
    fun nomeSaiDoTituloUmMesmoQueNaoSejaAPrimeiraLinha() {
        val e = Extractor.extrair("Currículo\n\n# Maria da Graça Boaventura")
        assertEquals("Maria da Graça Boaventura", valor(e, "nome"))
    }

    @Test
    fun nomeEmMaiusculasNaPrimeiraLinhaSai() {
        val e = Extractor.extrair("MARIA BOAVENTURA\nDublin")
        assertEquals("MARIA BOAVENTURA", valor(e, "nome"))
        assertEquals("MARIA", valor(e, "first name"))
    }

    @Test
    fun tituloDeDocumentoNaoViraNome() {
        assertFalse("nome" in chaves(Extractor.extrair("# CURRICULUM VITAE\nmaria@exemplo.com")))
        assertFalse("nome" in chaves(Extractor.extrair("Curriculum Vitae\nmaria@exemplo.com")))
    }

    // ---- número que parece telefone mas não é ----

    @Test
    fun periodoEDataNaoSaoTelefone() {
        val e = Extractor.extrair(
            """
            Analista entre 2015 - 2024 na Empresa Exemplo.
            Nascida em 10/09/1990, disponível a partir de 01/02/2027.
            """.trimIndent()
        )
        assertFalse("phone" in chaves(e))
        assertFalse("telefone" in chaves(e))
        assertEquals(2, e.naoEntendi.size)
    }

    // ---- o que não é dado não vira par ----

    @Test
    fun blockquoteNaoViraPar() {
        val e = Extractor.extrair("Notas do arquivo\n> Ângulo: lidera com pesquisa")
        assertTrue(e.pares.none { Matcher.normalizar(it.chave) == "angulo" })
        assertTrue(e.naoEntendi.any { it.texto.contains("Ângulo") })
    }

    @Test
    fun urlSoltaNaoViraPar() {
        val e = Extractor.extrair("Links da minha vida online.\nhttps://exemplo.com/portfolio")
        assertTrue(e.pares.none { it.chave == "https" })
        assertTrue(e.naoEntendi.any { it.texto.contains("exemplo.com") })
    }

    @Test
    fun emailRepetidoComValorDiferenteCaiEmNaoEntendi() {
        val e = Extractor.extrair("maria@exemplo.com\noutra@exemplo.com")
        assertEquals("maria@exemplo.com", valor(e, "email")) // o primeiro vence
        assertTrue(e.naoEntendi.any { it.texto.contains("outra@") }) // o conflito fica visível
    }

    // ---- brief 244: prosa de currículo com dois-pontos não vira par ----

    @Test
    fun bulletDeResponsabilidadeNaoViraPar() {
        val e = Extractor.extrair(
            "Experiência\n· Job-search platform: user research, interactive prototypes (Adobe XD, Figma),"
        )
        assertTrue(e.pares.isEmpty())
        assertTrue(e.naoEntendi.any { it.texto.contains("Job-search") })
    }

    @Test
    fun cabecalhoDeSecaoComListaNaoViraPar() {
        val e = Extractor.extrair(
            "Trajetória\nEarlier: UX Researcher, Tecza (2019, construction) · Lead Designer, 2L"
        )
        assertTrue(e.pares.isEmpty())
    }

    @Test
    fun fraseDeProsaComDoisPontosNaoViraPar() {
        // sem vírgula nenhuma: aqui quem corta é o teto de palavras do valor
        val e = Extractor.extrair(
            "Resumo\nRecently I stopped handing off: I now design and build products end-to-end"
        )
        assertTrue(e.pares.isEmpty())
        assertTrue(e.naoEntendi.any { it.texto.contains("handing off") })
    }

    @Test
    fun parLivreComValorDeDadoContinuaSaindo() {
        val e = Extractor.extrair("Dados\ncargo pretendido: Product Designer")
        assertEquals("Product Designer", valor(e, "cargo pretendido"))
    }

    @Test
    fun chaveCanonicaFicaIsentaDaReguaDoValor() {
        // a pessoa nomeou o campo de propósito; vírgula no valor não mata dado explícito
        val e = Extractor.extrair("cidade: Dublin, Ireland")
        assertEquals("Dublin, Ireland", valor(e, "cidade"))
    }

    // ---- brief 244: palavra funcional de documento não é nome ----

    @Test
    fun portfolioDaMariaNaoViraNomeMasNomeComParticulaSai() {
        assertFalse(Extractor.pareceNome("Portfolio da Maria"))
        assertTrue(Extractor.pareceNome("Maria da Graça Boaventura"))
        val e = Extractor.extrair("Portfolio da Maria\nmaria@exemplo.com")
        assertFalse("nome" in chaves(e))
        assertTrue(e.naoEntendi.any { it.texto.contains("Portfolio") })
    }

    // ---- mesclar: entra por cima, chave a chave, sem apagar o resto ----

    @Test
    fun mesclarSubstituiChaveIgualEPreservaOResto() {
        val base = "nome: Maria Antiga\nsó uma anotação solta\nemail: velho@exemplo.com"
        val saida = Merger.mesclar(base, listOf("nome" to "Maria da Graça Boaventura", "cidade" to "Dublin"))
        assertEquals(
            "nome: Maria da Graça Boaventura\nsó uma anotação solta\nemail: velho@exemplo.com\ncidade: Dublin",
            saida
        )
    }

    @Test
    fun mesclarNaoAtropelaChaveComposta() {
        // igualdade EXATA de chave normalizada: "nome" não substitui "nome completo"
        val saida = Merger.mesclar("nome completo: Maria Antiga", listOf("nome" to "Maria Nova"))
        assertTrue(saida.contains("nome completo: Maria Antiga"))
        assertTrue(saida.contains("nome: Maria Nova"))
    }

    @Test
    fun aprendidoContinuaVencendoDepoisDaMescla() {
        val mesclado = Merger.mesclar("email: velho@exemplo.com", listOf("email" to "novo@exemplo.com"))
        val perfil = Profile(mesclado, listOf(Learned("email", "corrigido@exemplo.com", 123L, "corrigiu")))
        val achado = perfil.valorPara("Email")!!
        assertEquals("corrigido@exemplo.com", achado.valor)
        assertEquals("aprendido", achado.fonte)
    }

    // ---- sem confirmação nada é salvo · apagar apaga de verdade ----

    @Test
    fun extrairNaoEscreveNada() {
        // Extractor recebe String e devolve dados: não tem NENHUM acesso a armazenamento
        // (garantia de tipo). Este teste prende o contrato do fluxo: extração rodou,
        // ninguém confirmou, o disco está como estava.
        val dir = java.nio.file.Files.createTempDirectory("preenche-teste").toFile()
        try {
            File(dir, "perfil.txt").writeText("nome: Antiga")
            Extractor.extrair("# Maria da Graça Boaventura\nemail: maria@exemplo.com")
            assertEquals(listOf("perfil.txt"), dir.list()!!.toList())
            assertEquals("nome: Antiga", File(dir, "perfil.txt").readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun apagarPerfilDeixaOArmazenamentoVazio() {
        val dir = java.nio.file.Files.createTempDirectory("preenche-teste").toFile()
        try {
            File(dir, "perfil.txt").writeText("nome: Maria")
            File(dir, "aprendidos.tsv").writeText("email\tx@y.com\t1\tcorrigiu")
            File(dir, "ultimo-recibo.tsv").writeText("P\tnome\tMaria\tperfil")
            Store.apagarPerfil(dir)
            assertFalse(File(dir, "perfil.txt").exists())
            assertFalse(File(dir, "aprendidos.tsv").exists())
            assertFalse(File(dir, "ultimo-recibo.tsv").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
