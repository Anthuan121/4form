package com.mygoll.fourform.agent

import com.mygoll.fourform.scan.Field
import com.mygoll.fourform.scan.Choice
import com.mygoll.fourform.scan.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O censo de campos de escolha existe para responder UMA pergunta: "o app não preencheu,
 * ou não viu?". Quem responde isso é o cegueiraPct do diagnóstico, então é ele que tem
 * check: se a conta quebrar, a medição mente e a decisão de produto sai errada.
 */
class CensoEscolhaTest {

    private fun json(texto: Int, escolha: Int): String = Diagnostics.json(
        build = "teste",
        pacote = "com.android.chrome",
        quando = "2026-09-12 07:00:00",
        totalNos = 700,
        voltasDeRolagem = 1,
        paradaPor = "fim",
        registros = List(texto) {
            Session.Registro(
                campo = Field(chave = "t$it"),
                rotulo = "campo $it",
                origemRotulo = "viewId",
                acao = "aberto",
                motivo = "não tenho esse dado",
            )
        },
        aprendidosNaSessao = 0,
        escolhas = List(escolha) { Choice(tipo = "checkbox", rotulo = "opção $it") },
    )

    @Test
    fun `metade do formulario invisivel da 50 por cento`() {
        assertTrue(json(texto = 5, escolha = 5).contains("\"cegueiraPct\": 50"))
    }

    @Test
    fun `formulario so de texto da zero, e nao divide por zero quando vazio`() {
        assertTrue(json(texto = 10, escolha = 0).contains("\"cegueiraPct\": 0"))
        assertTrue(json(texto = 0, escolha = 0).contains("\"cegueiraPct\": 0"))
    }

    /**
     * O bug medido no aparelho em 12/09: formulário de vaga real, rolar trouxe 7 radios e
     * zero caixas de texto, e o laço leu isso como "cheguei ao fim" e parou na 1ª volta.
     */
    @Test
    fun `rolagem que revela SO campos de escolha nao encerra o laco`() {
        val m = Engine()
        m.receberVarredura("com.android.chrome", listOf(Field(chave = "nome")), assinatura = "a")
        m.campoTratado("nome")
        // a rolagem trouxe 7 radios e nenhuma caixa de texto nova
        val radios = (1..7).map { Choice(tipo = "radio", rotulo = "opção $it") }
        m.receberVarredura("com.android.chrome", emptyList(), radios, assinatura = "b")
        // os 7 entram na FILA e são tratados um a um, na ordem visual, como campo de texto
        for (r in radios) {
            assertTrue(
                "escolha deveria virar passo do laço",
                m.proximoPasso() is Engine.Passo.Escolher,
            )
            m.campoTratado(r.chave)
        }
        assertTrue(
            "tratadas as escolhas, deveria continuar rolando, não encerrar",
            m.proximoPasso() is Engine.Passo.Rolar,
        )
    }

    /**
     * A régua dele de 12/09: *"se ele não vai marcar, segue pro próximo campo, não precisa
     * me travar ali"*. Choice não tratada não pode empatar a fila nem virar pergunta.
     */
    @Test
    fun `escolha e campo de texto se intercalam na ordem visual da tela`() {
        val m = Engine()
        m.receberVarredura(
            "com.android.chrome",
            listOf(
                Field(chave = "nome", caixa = Box(0, 10, 100, 40)),
                Field(chave = "email", caixa = Box(0, 300, 100, 330)),
            ),
            listOf(Choice(tipo = "checkbox", rotulo = "aceito", caixa = Box(0, 100, 50, 130))),
        )
        val ordem = mutableListOf<String>()
        repeat(3) {
            when (val p = m.proximoPasso()) {
                is Engine.Passo.Agir -> {
                    ordem.add(p.campo.chave); m.campoTratado(p.campo.chave)
                }
                is Engine.Passo.Escolher -> {
                    ordem.add("escolha"); m.campoTratado(p.escolha.chave)
                }
                else -> ordem.add("?")
            }
        }
        assertEquals(listOf("nome", "escolha", "email"), ordem)
    }

    @Test
    fun `rolagem que nao move a tela encerra o laco`() {
        val m = Engine()
        m.receberVarredura("com.android.chrome", listOf(Field(chave = "nome")), assinatura = "x")
        m.campoTratado("nome")
        m.receberVarredura("com.android.chrome", emptyList(), assinatura = "x")
        assertTrue(m.proximoPasso() is Engine.Passo.Fim)
    }

    @Test
    fun `tela so de escolhas nao e tratada como tela sem campo`() {
        val m = Engine()
        m.receberVarredura(
            "com.android.chrome",
            emptyList(),
            (1..7).map { Choice(tipo = "radio", rotulo = "opção $it") },
        )
        val passo = m.proximoPasso()
        assertTrue(passo !is Engine.Passo.Fim || !(passo).motivo.contains("não achei"))
    }

    /**
     * A trava que vale dinheiro: marcar de menos é um campo em branco que ele resolve num
     * toque; marcar de mais é uma afirmação FALSA enviada no nome dele numa candidatura.
     * Os dois erros ⛔ não têm o mesmo custo, então o teste guarda os dois lados.
     */
    @Test
    fun `so marca a opcao que o perfil declara, e nunca desmarca`() {
        val s = Session(Profile("cargo: Especialista UX/UI", emptyList()))
        fun d(e: Choice) = s.decidirEscolha(e)

        assertTrue(
            "o perfil declara isto: deve marcar",
            d(Choice(tipo = "radio", rotulo = "Especialista UX/UI", clicavel = true))
                is Session.Decisao.Preencher,
        )
        assertTrue(
            "provável ⛔ não é declarado: deixa em branco",
            d(Choice(tipo = "radio", rotulo = "Candidatura espontânea", clicavel = true))
                is Session.Decisao.DeixarAberto,
        )
        assertTrue(
            "já marcada: clicar aqui desmarcaria a escolha da pessoa",
            d(
                Choice(
                    tipo = "radio", rotulo = "Especialista UX/UI",
                    clicavel = true, marcada = true,
                )
            ) is Session.Decisao.DeixarAberto,
        )
        assertTrue(
            "sem rótulo não dá pra saber o que a opção afirma",
            d(Choice(tipo = "checkbox", rotulo = null, clicavel = true))
                is Session.Decisao.DeixarAberto,
        )
    }

    /**
     * O caso real dele, 12/09, preenchendo uma vaga: *"você reside em país europeu? Porra,
     * como não tem a informação? A Irlanda é um país de onde, da África?"*. O app não estava
     * sem o dado — estava comparando "Yes" com o perfil, porque nunca via a PERGUNTA.
     */
    @Test
    fun `a pergunta do grupo e que casa com o perfil, nao o rotulo Yes`() {
        // ⚠️ perfil e pergunta no MESMO idioma de propósito: o casamento determinístico é
        // palavra a palavra, e cross-idioma fora da tabela bilíngue é categoria 2 (IA),
        // não determinismo. Fingir o contrário aqui esconderia a régua real do produto.
        val s = Session(Profile("reside em país europeu: sim\npaís: Irlanda", emptyList()))
        val sim = Choice(
            tipo = "radio", rotulo = "Sim", clicavel = true,
            pergunta = "Você reside em um país europeu?",
        )
        val nao = sim.copy(rotulo = "Não")

        assertTrue(
            "perfil responde sim à pergunta: marca o Yes",
            s.decidirEscolha(sim) is Session.Decisao.Preencher,
        )
        assertTrue(
            "⛔ e NUNCA o Não na mesma pergunta",
            s.decidirEscolha(nao) is Session.Decisao.DeixarAberto,
        )
        assertTrue(
            "pergunta que o perfil não declara segue em branco, sem inventar",
            s.decidirEscolha(sim.copy(pergunta = "Você precisa de patrocínio de visto?"))
                is Session.Decisao.DeixarAberto,
        )
    }

    @Test
    fun `o estado marcado NAO sai no diagnostico, so tipo e rotulo`() {
        val saida = json(texto = 1, escolha = 1)
        assertTrue(saida.contains("\"tipo\": \"checkbox\""))
        assertTrue(saida.contains("\"camposDeEscolha\": 1"))
        // a régua do 242: nada de conteúdo pessoal sai do aparelho
        assertTrue(!saida.contains("marcado"))
    }
}
