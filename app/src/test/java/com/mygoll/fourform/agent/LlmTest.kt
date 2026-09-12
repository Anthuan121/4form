package com.mygoll.fourform.agent

import com.mygoll.fourform.scan.Json
import com.mygoll.fourform.scan.Learned
import com.mygoll.fourform.scan.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * A resposta de pergunta aberta (brief 245). O que se prova aqui, na ordem da rubrica:
 * a régua anti-invenção (sem âncora = campo aberto), a degradação de TODO modo de falha
 * para "campo continua aberto, nunca lança", o que NUNCA sobe no corpo (senha, valor
 * digitado), e o 3º ato: correção da pessoa vence a IA na passada seguinte.
 */
class LlmTest {

    private val linhas = listOf(
        "nome: Ana Prova",
        "email: ana.prova@exemplo.com",
        "linkedin: linkedin.com/in/anaprova",
    )

    /** Envelope OpenAI real (LiteLLM): choices[0].message.content. */
    private fun envelope(conteudo: String): String =
        "{\"id\":\"x\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0," +
            "\"message\":{\"role\":\"assistant\",\"content\":${Json.str(conteudo)}}," +
            "\"finish_reason\":\"stop\"}]}"

    private fun registroSemDado(hint: String = "Pretensão salarial"): Session.Registro {
        val campo = Field(chave = "c-$hint", hint = hint)
        val s = Session(Profile("", emptyList()))
        s.registrar(campo, s.decidir(campo))
        return s.registros().single()
    }

    // ---- parse e contrato ----

    @Test
    fun `resposta limpa com ancora vira sugestao`() {
        val v = Llm.avaliar(
            Result.success(
                envelope(
                    "{\"pode_responder\": true, \"resposta\": \"linkedin.com/in/anaprova\", " +
                        "\"ancora\": \"linkedin: linkedin.com/in/anaprova\", \"confianca\": \"alta\"}"
                )
            ),
            linhas,
        )
        val sug = (v as Llm.Veredito.Responder).sugestao
        assertEquals("linkedin.com/in/anaprova", sug.resposta)
        assertEquals("linkedin: linkedin.com/in/anaprova", sug.ancora)
        assertEquals("alta", sug.confianca)
    }

    @Test
    fun `cerca de markdown e texto em volta nao atrapalham`() {
        // medido no teste real da ponte em 10/09: a resposta veio cercada de ```json
        val v = Llm.avaliar(
            Result.success(
                envelope(
                    "Claro! Aqui está:\n```json\n{\"pode_responder\": true, " +
                        "\"resposta\": \"Ana Prova\", \"ancora\": \"nome: Ana Prova\", " +
                        "\"confianca\": \"alta\"}\n```\nEspero ter ajudado."
                )
            ),
            linhas,
        )
        assertEquals("Ana Prova", (v as Llm.Veredito.Responder).sugestao.resposta)
    }

    @Test
    fun `json truncado degrada sem lancar`() {
        // corte no meio da resposta (max_tokens estourou): meia resposta nunca entra num campo
        val cortado = envelope("{\"pode_responder\": true, \"resposta\": \"Ana Pro").dropLast(3)
        val v = Llm.avaliar(Result.success(cortado), linhas)
        assertTrue(v is Llm.Veredito.NaoResponder)
        assertFalse((v as Llm.Veredito.NaoResponder).doModelo)
    }

    @Test
    fun `texto sem json nenhum degrada sem lancar`() {
        val v = Llm.avaliar(Result.success(envelope("Desculpe, não posso ajudar com isso.")), linhas)
        assertTrue(v is Llm.Veredito.NaoResponder)
    }

    @Test
    fun `pode_responder false mantem o campo aberto e propaga o motivo`() {
        // resposta REAL do endpoint em 10/09 para "Salary expectation"
        val v = Llm.avaliar(
            Result.success(
                envelope(
                    "{\"pode_responder\": false, \"resposta\": \"Profile não informa expectativa salarial.\", " +
                        "\"ancora\": \"\", \"confianca\": \"alta\"}"
                )
            ),
            linhas,
        )
        assertTrue((v as Llm.Veredito.NaoResponder).doModelo)
        val reg = registroSemDado()
        val sugestoes = mutableMapOf<String, Llm.Sugestao>()
        assertEquals("modelo_nao_respondeu", Llm.aplicar(reg, v, sugestoes))
        assertEquals("aberto", reg.acao)
        assertEquals("AI: Profile não informa expectativa salarial.", reg.motivo)
        assertTrue(sugestoes.isEmpty())
    }

    @Test
    fun `resposta sem ancora e rebaixada para campo aberto`() {
        val v = Llm.avaliar(
            Result.success(
                envelope("{\"pode_responder\": true, \"resposta\": \"Ana Prova\", \"ancora\": \"\", \"confianca\": \"alta\"}")
            ),
            linhas,
        )
        assertTrue(v is Llm.Veredito.NaoResponder)
        // e o campo fica EXATAMENTE como estava: falha de contrato não vira motivo novo
        val reg = registroSemDado()
        val motivoOriginal = reg.motivo
        assertEquals("falhou", Llm.aplicar(reg, v, mutableMapOf()))
        assertEquals("aberto", reg.acao)
        assertEquals(motivoOriginal, reg.motivo)
    }

    @Test
    fun `ancora que nao existe no perfil e invencao`() {
        val v = Llm.avaliar(
            Result.success(
                envelope(
                    "{\"pode_responder\": true, \"resposta\": \"123.456.789-00\", " +
                        "\"ancora\": \"cpf: 123.456.789-00\", \"confianca\": \"alta\"}"
                )
            ),
            linhas,
        )
        assertTrue(v is Llm.Veredito.NaoResponder)
        assertFalse((v as Llm.Veredito.NaoResponder).doModelo)
    }

    @Test
    fun `confianca fora do combinado vira baixa`() {
        val v = Llm.avaliar(
            Result.success(
                envelope(
                    "{\"pode_responder\": true, \"resposta\": \"Ana Prova\", " +
                        "\"ancora\": \"nome: Ana Prova\", \"confianca\": \"altíssima\"}"
                )
            ),
            linhas,
        )
        assertEquals("baixa", (v as Llm.Veredito.Responder).sugestao.confianca)
    }

    // ---- modos de falha (critério 3 da rubrica: falhar bem) ----

    @Test
    fun `falha de rede degrada para campo aberto sem lancar`() {
        for (erro in listOf<Throwable>(IOException("timeout"), IOException("HTTP 500"), RuntimeException())) {
            val v = Llm.avaliar(Result.failure(erro), linhas)
            assertTrue(v is Llm.Veredito.NaoResponder)
            assertFalse((v as Llm.Veredito.NaoResponder).doModelo)
            val reg = registroSemDado()
            val motivoOriginal = reg.motivo
            assertEquals("falhou", Llm.aplicar(reg, v, mutableMapOf()))
            assertEquals("aberto", reg.acao)
            assertEquals(motivoOriginal, reg.motivo)
        }
    }

    @Test
    fun `corpo vazio e envelope sem conteudo degradam igual`() {
        assertTrue(Llm.avaliar(Result.success(""), linhas) is Llm.Veredito.NaoResponder)
        assertTrue(Llm.avaliar(Result.success("{\"erro\": \"oi\"}"), linhas) is Llm.Veredito.NaoResponder)
        assertTrue(Llm.avaliar(Result.success(envelope("")), linhas) is Llm.Veredito.NaoResponder)
    }

    // ---- o que sobe (e o que NUNCA sobe) ----

    @Test
    fun `senha e valor digitado nunca entram no corpo enviado`() {
        val s = Session(Profile("cidade: Dublin", emptyList()))
        val senha = Field(chave = "c1", hint = "Senha", senha = true)
        val digitado = Field(chave = "c2", hint = "CPF", textoAtual = "valor-digitado-987")
        val semDado = Field(chave = "c3", hint = "Pretensão salarial")
        for (c in listOf(senha, digitado, semDado)) s.registrar(c, s.decidir(c))

        val candidatos = Llm.candidatos(s.registros())
        assertEquals(listOf("c3"), candidatos.map { it.campo.chave })

        val corpo = Llm.corpo(candidatos.single().rotulo!!, Llm.linhasDePerfil("cidade: Dublin", emptyList()))
        assertFalse(corpo.contains("Senha"))
        assertFalse(corpo.contains("CPF"))
        assertFalse(corpo.contains("valor-digitado-987"))
        assertTrue(corpo.contains("Pretensão salarial"))
        assertTrue(corpo.contains("cidade: Dublin"))
        assertTrue(corpo.contains(Llm.MODELO))
    }

    @Test
    fun `candidato so quando aberto com rotulo bom e sem dado no perfil`() {
        // viewId cru: tem rótulo de exibição mas ninguém sabe o que o campo pergunta
        val s = Session(Profile("", emptyList()))
        val cru = Field(chave = "c4", viewId = "app:id/question_66138698")
        s.registrar(cru, s.decidir(cru))
        // sem rótulo nenhum
        val anonimo = Field(chave = "c5")
        s.registrar(anonimo, s.decidir(anonimo))
        assertTrue(Llm.candidatos(s.registros()).isEmpty())

        // campo que recusou a escrita: responder não adianta, escrever vai falhar de novo
        val s2 = Session(Profile("cidade: Dublin", emptyList()))
        val recusou = Field(chave = "c6", hint = "Cidade")
        s2.registrar(recusou, s2.decidir(recusou), escreveu = false)
        assertTrue(Llm.candidatos(s2.registros()).isEmpty())
    }

    @Test
    fun `sugestao aceita pelo modelo nao escreve sozinha`() {
        // ⛔ o coração do critério 4: aplicar() guarda a sugestão e NÃO muda o campo
        val reg = registroSemDado()
        val sugestoes = mutableMapOf<String, Llm.Sugestao>()
        val v = Llm.Veredito.Responder(Llm.Sugestao("Ana Prova", "nome: Ana Prova", "alta"))
        assertEquals("sugeriu", Llm.aplicar(reg, v, sugestoes))
        assertEquals("aberto", reg.acao)
        assertNull(reg.valorEscrito)
        assertEquals("Ana Prova", sugestoes[reg.campo.chave]?.resposta)
    }

    // ---- o 3º ato: a correção da pessoa vence a IA ----

    @Test
    fun `correcao da pessoa vence a IA na proxima passada`() {
        val campo = Field(chave = "k1", hint = "Cidade")
        val s1 = Session(Profile("", emptyList()), agoraMs = { 1000L })
        val d1 = s1.decidir(campo)
        s1.registrar(campo, d1)
        assertTrue(Llm.candidato(s1.registros().single()))
        // a IA sugeriu "Dublim"; a pessoa tocou Editar e escreveu o certo no painel
        assertTrue(s1.escreverAgora("k1", "Dublin, Irlanda"))
        val recibo = s1.fechar()!!
        // próxima passada, mesmo rótulo: o aprendido responde ANTES, e a IA nem é consultada
        val s2 = Session(Profile("", recibo.aprendidos))
        val d2 = s2.decidir(campo)
        assertEquals("Dublin, Irlanda", (d2 as Session.Decisao.Preencher).valor)
        assertEquals("aprendido", d2.fonte)
        s2.registrar(campo, d2)
        assertTrue(Llm.candidatos(s2.registros()).isEmpty())
    }

    @Test
    fun `aprendido esconde a linha base equivalente no prompt`() {
        // se a pessoa corrigiu "cidade", a IA nunca mais vê o valor velho para ancorar nele
        val linhas = Llm.linhasDePerfil(
            "cidade: Porto\nemail: ana@exemplo.com",
            listOf(Learned("Cidade", "Dublin", 2000L, "corrigiu")),
        )
        assertEquals(listOf("Cidade: Dublin", "email: ana@exemplo.com"), linhas)
    }

    @Test
    fun `escreverAgora com fonte de IA marca a fonte no recibo`() {
        val campo = Field(chave = "k2", hint = "Nome")
        val s = Session(Profile("", emptyList()))
        s.registrar(campo, s.decidir(campo))
        assertTrue(s.escreverAgora("k2", "Ana Prova", fonte = "IA · aprovado por você"))
        assertEquals("IA · aprovado por você", s.registros().single().fonte)
        // e continua aprendendo: na próxima o perfil responde sem IA
        assertEquals(1, s.fechar()!!.aprendidos.size)
    }

    // ---- diagnóstico ----

    @Test
    fun `diagnostico leva desfecho da IA sem resposta nem ancora`() {
        val reg = registroSemDado()
        val json = Diagnostics.json(
            "0.8-ia", "com.exemplo", "2026-09-10 08:00:00", 10, 0, "fim",
            listOf(reg), 0, emptyList(),
            llm = mapOf(reg.campo.chave to Llm.LlmDiagnostico("sugeriu", "alta", 1234L)),
        )
        assertTrue(json.contains("\"llm\""))
        assertTrue(json.contains("\"sugeriu\""))
        assertTrue(json.contains("\"alta\""))
        assertTrue(json.contains("1234"))
        // a régua do 242 continua: nem a resposta nem a âncora têm CAMPO no arquivo que sai
        assertFalse(json.contains("\"resposta\""))
        assertFalse(json.contains("\"ancora\""))
    }
}
