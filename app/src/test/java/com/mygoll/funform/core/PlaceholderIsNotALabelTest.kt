package com.mygoll.funform.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O caso real medido no aparelho dele em 12/09: formulário do Ashby (jobs.ashbyhq.com,
 * vaga Product Designer @ Ceartas). Os quatro campos da tela tinham o MESMO hint,
 * "Type here...", e a pergunta de verdade ficava num texto acima do campo.
 *
 * Como hint é o nível 2 da escada de rótulo e vizinho é o 6, o app batizava os quatro
 * campos de "Type here", não casava nada com o perfil e ainda mandava "Type here" para a
 * IA. O sintoma que ele descreveu foi "ele não preencheu nada" — e não era cegueira de
 * varredura, era o campo estar com o nome errado.
 *
 * Este teste existe porque o erro é invisível: o app funciona, responde, gasta rede, e
 * entrega nada. Sem check, volta na primeira mexida na escada.
 */
class PlaceholderNaoEhRotuloTest {

    private val PERGUNTA =
        "How many years of professional UX/UI or product design experience do you have?"

    /** O campo do Ashby: hint genérico, pergunta de verdade 40px acima. */
    private fun campoDoAshby(hintRepetido: Boolean = true) = Field(
        chave = "anos",
        hint = "Type here...",
        caixa = Box(60, 880, 870, 990),
        rotuloVizinho = RotuloVizinho(PERGUNTA, 40),
        hintRepetidoNaTela = hintRepetido,
    )

    @Test
    fun `a pergunta acima vence o placeholder generico`() {
        val r = Labeler.rotulo(campoDoAshby())
        assertEquals(PERGUNTA, r?.first)
        assertTrue("deveria ter resolvido pelo vizinho", r!!.second.startsWith("vizinho"))
    }

    @Test
    fun `placeholder generico e recusado mesmo quando aparece uma vez so`() {
        val r = Labeler.rotulo(campoDoAshby(hintRepetido = false))
        assertEquals(PERGUNTA, r?.first)
    }

    /**
     * A segunda defesa, e é a que atravessa idioma: dois campos com o mesmo hint não são
     * nomeados por ele, seja qual for a língua. Sem isto, a lista de padrões teria que
     * prever todo placeholder de todo formulário do mundo.
     */
    @Test
    fun `hint repetido na tela nao nomeia campo nenhum, em qualquer idioma`() {
        val campo = Field(
            chave = "x",
            hint = "Escreva sua resposta",
            caixa = Box(60, 880, 870, 990),
            rotuloVizinho = RotuloVizinho("Qual sua pretensão salarial?", 40),
            hintRepetidoNaTela = true,
        )
        assertEquals("Qual sua pretensão salarial?", Labeler.rotulo(campo)?.first)
    }

    /** ⛔ E o hint LEGÍTIMO continua ganhando: a correção não pode cegar o caso comum. */
    @Test
    fun `hint que nomeia de verdade continua valendo`() {
        val campo = Field(
            chave = "email",
            hint = "E-mail",
            caixa = Box(60, 100, 870, 200),
            rotuloVizinho = RotuloVizinho("Contato", 30),
        )
        val r = Labeler.rotulo(campo)
        assertEquals("E-mail", r?.first)
        assertEquals("hint", r?.second)
    }

    /**
     * O efeito que importa em dinheiro e em tempo: com o rótulo certo, o campo vira
     * candidato a IA com a PERGUNTA. Com o rótulo errado, ele gastava 1,7s de rede para
     * perguntar ao modelo o que é "Type here".
     */
    @Test
    fun `o campo vai para a IA com a pergunta, nao com o placeholder`() {
        val s = Session(Profile("cargo: Product Designer", emptyList()))
        val campo = campoDoAshby()
        s.registrar(campo, s.decidir(campo))
        val candidato = Llm.candidatos(s.registros()).single()
        assertEquals(PERGUNTA, candidato.rotulo)
    }
}
