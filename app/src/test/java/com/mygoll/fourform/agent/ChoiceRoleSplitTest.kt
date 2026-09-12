package com.mygoll.fourform.agent

import com.mygoll.fourform.scan.Box
import com.mygoll.fourform.scan.Choice
import com.mygoll.fourform.scan.Scanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Brief 257: the bug measured on device 09/12 (420 choice fields seen, 0 clicks) was the
 * app reading the QUESTION as if it were the OPTION. Root cause was Scanner filling
 * Choice.rotulo from Labeler.vizinhoMaisProximo, which accepts text above OR to the left
 * without telling the two roles apart, so the question above (closer than any option)
 * always won. These tests build the real layout he measured (a question above a group of
 * three radios, one option label per row, to the right of each input) and prove the fix:
 * TESTE JVM NOVO 1 (Camada 1).
 */
class DivisaoDePapelDeEscolhaTest {

    /**
     * "What is your level of English?" above three radios, each with its own option label
     * to the right, one per row, same shape the dumps showed.
     */
    private val pergunta = "What is your level of English?"
    private val radioBasico = Box(esq = 40, topo = 200, dir = 60, baixo = 220)
    private val radioIntermediario = Box(esq = 40, topo = 240, dir = 60, baixo = 260)
    private val radioAvancado = Box(esq = 40, topo = 280, dir = 60, baixo = 300)

    private val textos = listOf(
        pergunta to Box(20, 140, 500, 180),
        "Basic" to Box(70, 200, 200, 220),
        "Intermediate" to Box(70, 240, 260, 260),
        "Advanced" to Box(70, 280, 220, 300),
    )

    private val brutas = listOf(
        Choice(tipo = "radio", caixa = radioBasico, clicavel = true),
        Choice(tipo = "radio", caixa = radioIntermediario, clicavel = true),
        Choice(tipo = "radio", caixa = radioAvancado, clicavel = true),
    )

    @Test
    fun `rotulo vira a OPCAO a direita, nao a pergunta acima`() {
        val resolvidas = Scanner.resolverEscolhas(brutas, textos)
        assertEquals(listOf("Basic", "Intermediate", "Advanced"), resolvidas.map { it.rotulo })
    }

    @Test
    fun `pergunta vira o ENUNCIADO acima do grupo, para as tres opcoes`() {
        val resolvidas = Scanner.resolverEscolhas(brutas, textos)
        resolvidas.forEach { assertEquals(pergunta, it.pergunta) }
    }

    @Test
    fun `radio sem texto a direita fica sem rotulo, sem cair de volta na pergunta`() {
        // the exact bug: no option label to the right, and the old fallback (vizinho
        // acima/esquerda) would have grabbed the question. The fix must leave it null.
        val orfao = Choice(tipo = "radio", caixa = Box(40, 400, 60, 420), clicavel = true)
        val resolvidas = Scanner.resolverEscolhas(listOf(orfao), textos)
        assertNull(resolvidas.single().rotulo)
    }

    @Test
    fun `decidirEscolha marca a opcao certa usando a pergunta separada da opcao`() {
        val resolvidas = Scanner.resolverEscolhas(brutas, textos)
        val s = Session(Profile("level of english: Intermediate", emptyList()))
        val decisoes = resolvidas.map { it.rotulo to s.decidirEscolha(it) }
        assertTrue(decisoes.single { it.first == "Intermediate" }.second is Session.Decisao.Preencher)
        assertTrue(decisoes.single { it.first == "Basic" }.second is Session.Decisao.DeixarAberto)
        assertTrue(decisoes.single { it.first == "Advanced" }.second is Session.Decisao.DeixarAberto)
    }
}
