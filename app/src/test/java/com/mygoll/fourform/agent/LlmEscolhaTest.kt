package com.mygoll.fourform.agent

import com.mygoll.fourform.scan.Choice
import com.mygoll.fourform.scan.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Brief 257, Camada 2: the model only ever sees the CLOSED list of options a group
 * actually has on screen, and the mandatory guard is that an answer outside that list is
 * discarded, exactly like an unanchored answer already is on the text-field path (Llm.avaliar).
 * TESTE JVM NOVO 2.
 */
class LlmEscolhaTest {

    private val opcoes = listOf("Sim", "Não")
    private val perfil = listOf("reside em pais europeu: sim")

    private fun httpContent(json: String): Result<String> =
        Result.success("{\"choices\": [{\"message\": {\"content\": ${Json.str(json)}}}]}")

    @Test
    fun `opcao devolvida fora da lista enviada e descartada`() {
        val resposta = httpContent(
            "{\"pode_responder\": true, \"resposta\": \"Talvez\", " +
                "\"ancora\": \"reside em pais europeu: sim\", \"confianca\": \"alta\"}",
        )
        val veredito = Llm.avaliarEscolha(resposta, opcoes, perfil)
        // A trava que importa é o DESCARTE: opção que não estava na tela nunca é marcada.
        // ⛔ Não afirmo o texto do motivo: ele é mensagem de usuário e muda com a redação.
        assertTrue(veredito is Llm.Veredito.NaoResponder)
    }

    @Test
    fun `opcao que bate literalmente com a lista e aceita`() {
        val resposta = httpContent(
            "{\"pode_responder\": true, \"resposta\": \"Sim\", " +
                "\"ancora\": \"reside em pais europeu: sim\", \"confianca\": \"alta\"}",
        )
        val veredito = Llm.avaliarEscolha(resposta, opcoes, perfil)
        assertTrue(veredito is Llm.Veredito.Responder)
        assertEquals("Sim", (veredito as Llm.Veredito.Responder).sugestao.resposta)
    }

    @Test
    fun `ancora que nao existe no perfil tambem e descartada, mesmo com opcao valida`() {
        val resposta = httpContent(
            "{\"pode_responder\": true, \"resposta\": \"Sim\", " +
                "\"ancora\": \"gosta de pizza\", \"confianca\": \"alta\"}",
        )
        val veredito = Llm.avaliarEscolha(resposta, opcoes, perfil)
        assertTrue(veredito is Llm.Veredito.NaoResponder)
    }

    @Test
    fun `agrupar por pergunta usa o enunciado geometrico, nunca um id inventado`() {
        val a = Choice(
            tipo = "radio", rotulo = "Sim", clicavel = true, pergunta = "Reside em país europeu?",
        )
        val b = a.copy(rotulo = "Não")
        val marcado = a.copy(marcada = true) // já marcada: não entra no grupo a decidir
        val semPergunta = Choice(tipo = "radio", rotulo = "Outra", clicavel = true)
        val grupos = Llm.agruparPorPergunta(listOf(a, b, marcado, semPergunta))
        assertEquals(setOf("Reside em país europeu?"), grupos.keys)
        assertEquals(listOf("Sim", "Não"), grupos.getValue("Reside em país europeu?").map { it.rotulo })
    }
}
