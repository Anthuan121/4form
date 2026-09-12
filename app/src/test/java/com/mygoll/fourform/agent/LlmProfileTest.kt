package com.mygoll.fourform.agent

import com.mygoll.fourform.scan.LinhaNaoEntendida
import com.mygoll.fourform.scan.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A leitura do currículo pela IA (10/09). Found dele no aparelho: 55 linhas em
 * "não entendi" contendo cargo, autorização de trabalho e anos de experiência.
 *
 * A régua aqui é a MESMA da âncora do 245: o valor tem que existir na linha citada.
 * Sem isso, "a IA leu o currículo" vira "a IA escreveu um currículo".
 */
class LlmPerfilTest {

    private val linhas = listOf(
        LinhaNaoEntendida(2, "Product Designer · Banking · Insurance · Healthcare · Fintech"),
        LinhaNaoEntendida(4, "EU citizen (Italian) — full work authorisation"),
        LinhaNaoEntendida(8, "A designer for 12 years, the last 7+ focused on product and UX"),
    )

    private fun envelope(json: String) =
        Result.success("{\"choices\":[{\"message\":{\"content\":${Json.str(json)}}}]}")

    @Test
    fun `extrai par quando o valor esta mesmo na linha citada`() {
        val r = Llm.avaliarPerfil(
            envelope("{\"pares\":[{\"chave\":\"cargo\",\"valor\":\"Product Designer\",\"linha\":2}]}"),
            linhas,
        )
        // "cargo" está na tabela bilíngue: uma proposta vira as duas gêmeas (10/09)
        assertEquals(2, r.size)
        assertTrue(r.any { it.chave == "cargo" })
        assertTrue(r.any { it.chave == "job title" })
        assertTrue(r.all { it.valor == "Product Designer" && it.linha == 2 })
        // a origem viaja junto: é o que deixa ele conferir na tela sem abrir o arquivo
        assertTrue(r[0].origem.contains("Banking"))
    }

    @Test
    fun `valor que NAO esta na linha citada e descartado como invencao`() {
        // o modelo "deduziu" um cargo que não está escrito em lugar nenhum
        val r = Llm.avaliarPerfil(
            envelope("{\"pares\":[{\"chave\":\"cargo\",\"valor\":\"Head of Design\",\"linha\":2}]}"),
            linhas,
        )
        assertTrue("invenção não pode passar", r.isEmpty())
    }

    @Test
    fun `linha que nao foi enviada e descartada`() {
        val r = Llm.avaliarPerfil(
            envelope("{\"pares\":[{\"chave\":\"cargo\",\"valor\":\"Product Designer\",\"linha\":99}]}"),
            linhas,
        )
        assertTrue(r.isEmpty())
    }

    @Test
    fun `par sem numero de linha e descartado`() {
        val r = Llm.avaliarPerfil(
            envelope("{\"pares\":[{\"chave\":\"cargo\",\"valor\":\"Product Designer\"}]}"),
            linhas,
        )
        assertTrue(r.isEmpty())
    }

    @Test
    fun `duas linhas com a mesma chave viram DOIS pares - a pessoa decide`() {
        // a régua dele de 10/09: conflito NÃO vira pergunta que trava; vira duas opções na tela
        val duas = linhas + LinhaNaoEntendida(84, "Product Designer who builds with AI · Founding Designer")
        val r = Llm.avaliarPerfil(
            envelope(
                "{\"pares\":[" +
                    "{\"chave\":\"cargo\",\"valor\":\"Product Designer\",\"linha\":2}," +
                    "{\"chave\":\"cargo\",\"valor\":\"Founding Designer\",\"linha\":84}]}"
            ),
            duas,
        )
        // 2 valores diferentes × 2 gêmeas de "cargo" = 4; o que importa é que NENHUM
        // valor foi descartado: a régua dele é que conflito não some e não trava.
        assertEquals(4, r.size)
        assertEquals(setOf(2, 84), r.map { it.linha }.toSet())
        assertEquals(setOf("Product Designer", "Founding Designer"), r.map { it.valor }.toSet())
    }

    @Test
    fun `par identico repetido entra uma vez so`() {
        val r = Llm.avaliarPerfil(
            envelope(
                "{\"pares\":[" +
                    "{\"chave\":\"cargo\",\"valor\":\"Product Designer\",\"linha\":2}," +
                    "{\"chave\":\"Cargo\",\"valor\":\"Product Designer\",\"linha\":2}]}"
            ),
            linhas,
        )
        // "cargo" e "Cargo" são o mesmo par: cada gêmea entra uma vez só
        assertEquals(2, r.size)
        assertEquals(setOf("cargo", "job title"), r.map { it.chave }.toSet())
    }

    @Test
    fun `cerca markdown e texto em volta nao atrapalham`() {
        // MEDIDO em 10/09: o modelo devolveu o JSON dentro de cerca ```json
        val r = Llm.avaliarPerfil(
            envelope("Claro! Aqui está:\n```json\n{\"pares\":[{\"chave\":\"cargo\",\"valor\":\"Product Designer\",\"linha\":2}]}\n```"),
            linhas,
        )
        assertEquals("as duas gêmeas de cargo, sem repetir", 2, r.size)
    }

    @Test
    fun `json truncado aproveita os pares inteiros e ignora o cortado`() {
        val r = Llm.avaliarPerfil(
            envelope(
                "{\"pares\":[{\"chave\":\"cargo\",\"valor\":\"Product Designer\",\"linha\":2}," +
                    "{\"chave\":\"nacionalid"
            ),
            linhas,
        )
        assertEquals("só o par inteiro, nas duas gêmeas", 2, r.size)
    }

    @Test
    fun `falha de rede devolve lista vazia e NUNCA lanca`() {
        val r = Llm.avaliarPerfil(Result.failure(java.io.IOException("sem rede")), linhas)
        assertTrue(r.isEmpty())
    }

    @Test
    fun `resposta ilegivel devolve lista vazia`() {
        assertTrue(Llm.avaliarPerfil(Result.success("<html>502 Bad Gateway</html>"), linhas).isEmpty())
        assertTrue(Llm.avaliarPerfil(Result.success(""), linhas).isEmpty())
    }

    @Test
    fun `o corpo manda as linhas numeradas e proibe traduzir`() {
        val corpo = Llm.corpoPerfil(linhas)
        assertTrue(corpo.contains("L2:"))
        assertTrue(corpo.contains("L8:"))
        assertTrue(corpo.contains("nina-default"))
        assertTrue("copiar, não traduzir", corpo.contains("não traduza"))
    }

    @Test
    fun `curriculo gigante e cortado no teto - requisicao nao explode`() {
        val muitas = (1..500).map { LinhaNaoEntendida(it, "linha $it de enchimento") }
        val corpo = Llm.corpoPerfil(muitas)
        assertTrue(corpo.contains("L${Llm.MAX_LINHAS_PERFIL}:"))
        assertTrue("passou do teto", !corpo.contains("L${Llm.MAX_LINHAS_PERFIL + 1}:"))
    }

    @Test
    fun `a regua de idioma e a lista de imunes estao no prompt do preenchimento`() {
        val corpo = Llm.corpo("Do you require sponsorship?", listOf("precisa de patrocinio de visto: nao"))
        assertTrue("responde no idioma do rótulo", corpo.contains("idioma do R\\u00d3TULO") || corpo.contains("IDIOMA"))
        assertTrue("nome próprio é imune", corpo.contains("NUNCA traduza nome de pessoa"))
    }
}
