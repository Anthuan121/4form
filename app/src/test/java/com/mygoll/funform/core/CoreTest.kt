package com.mygoll.funform.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RotuladorTest {

    @Test
    fun `escolhe o vizinho mais proximo acima ou a esquerda por distancia em pixels`() {
        val campo = Box(esq = 100, topo = 500, dir = 600, baixo = 560)
        val textos = listOf(
            "Nome completo" to Box(100, 460, 300, 490), // 10px acima
            "Formulário de vaga" to Box(100, 300, 500, 340), // 160px acima
            "E-mail:" to Box(10, 505, 90, 550), // 10px à esquerda
        )
        val vizinho = Labeler.vizinhoMaisProximo(campo, textos)!!
        assertEquals("Nome completo", vizinho.texto)
        assertEquals(10, vizinho.distanciaPx)
    }

    @Test
    fun `vizinho fora do raio de 240px existe para o diagnostico mas NAO vira rotulo`() {
        // desde o 242 a distância é registrada mesmo longe (o diagnóstico diz "havia
        // texto a 860px"); o corte do raio acontece na hora de rotular
        val campo = Box(100, 1000, 600, 1060)
        val textos = listOf("Título da página" to Box(100, 100, 500, 140)) // 860px acima
        val v = Labeler.vizinhoMaisProximo(campo, textos)!!
        assertEquals(860, v.distanciaPx)
        assertNull(Labeler.rotulo(Field(chave = "c", caixa = campo, rotuloVizinho = v)))
    }

    @Test
    fun `ignora texto abaixo ou a direita`() {
        val campo = Box(100, 500, 600, 560)
        val textos = listOf(
            "ajuda embaixo" to Box(100, 570, 400, 600),
            "à direita" to Box(620, 500, 800, 560),
        )
        assertNull(Labeler.vizinhoMaisProximo(campo, textos))
    }

    @Test
    fun `ordem do rotulo espelha os niveis - hint vence descricao que vence viewId que vence vizinho`() {
        val base = Field(
            chave = "c1",
            hint = "Seu e-mail",
            descricao = "campo de email",
            viewId = "com.x:id/email_address",
            rotuloVizinho = RotuloVizinho("E-mail", 12),
        )
        assertEquals("Seu e-mail" to "hint", Labeler.rotulo(base))
        assertEquals("campo de email" to "descricao", Labeler.rotulo(base.copy(hint = null)))
        assertEquals("email address" to "viewId", Labeler.rotulo(base.copy(hint = null, descricao = null)))
        assertEquals(
            "E-mail" to "vizinho:12px",
            Labeler.rotulo(base.copy(hint = null, descricao = null, viewId = null))
        )
        assertNull(Labeler.rotulo(Field(chave = "c2")))
    }

    @Test
    fun `labeledBy e o TOPO da escada - vence hint, descricao, viewId e vizinho`() {
        // critério 4: labeledBy é o rótulo que o AUTOR da página declarou (<label for>)
        val c = Field(
            chave = "c",
            labeledBy = "First name",
            hint = "type here",
            descricao = "input",
            viewId = "com.x:id/first_name",
            rotuloVizinho = RotuloVizinho("perto", 5),
        )
        assertEquals("First name" to "labeledBy", Labeler.rotulo(c))
    }

    @Test
    fun `havendo labeledBy o rotulo NAO sai do viewId`() {
        // o caso do print de 10/09, com a relação declarada presente
        val c = Field(chave = "c", labeledBy = "Why this role?", viewId = "question_66138698")
        assertEquals("Why this role?" to "labeledBy", Labeler.rotulo(c))
    }

    @Test
    fun `viewId cru desce para ultimo recurso e sai marcado - vizinho e irmao vencem ele`() {
        // "question 66138698" é id daquela vaga, não nome de campo
        val cru = Field(chave = "c", viewId = "question_66138698")
        assertEquals("question 66138698" to "viewId-cru", Labeler.rotulo(cru))
        assertEquals(
            "Why do you want to work here?" to "vizinho:30px",
            Labeler.rotulo(cru.copy(rotuloVizinho = RotuloVizinho("Why do you want to work here?", 30)))
        )
        assertEquals(
            "Pretensão salarial" to "irmao",
            Labeler.rotulo(cru.copy(rotuloIrmao = "Pretensão salarial"))
        )
        // viewId LEGÍVEL segue resolvendo no nível de sempre
        assertTrue(Labeler.viewIdCru("question 66138698"))
        assertTrue(Labeler.viewIdCru("input 42"))
        assertFalse(Labeler.viewIdCru("email address"))
        assertFalse(Labeler.viewIdCru("address line 1"))
    }

    @Test
    fun `nivel preferido e atalho, nunca trava - se nao resolve, a escada roda inteira`() {
        val c = Field(chave = "c", hint = "Nome", rotuloVizinho = RotuloVizinho("Vizinho", 10))
        // o atalho aprendido aponta pro vizinho: ele é tentado primeiro
        assertEquals("Vizinho" to "vizinho:10px", Labeler.rotulo(c, nivelPreferido = "vizinho"))
        // o atalho aponta pra um nível que ESTE campo não tem: cai na escada normal
        assertEquals("Nome" to "hint", Labeler.rotulo(c, nivelPreferido = "labeledBy"))
    }
}

class CasadorTest {

    @Test
    fun `casa rotulo com chave por palavra normalizada`() {
        assertTrue(Matcher.casa("Nome completo", "nome"))
        assertTrue(Matcher.casa("E-MAIL", "email"))
        assertTrue(Matcher.casa("Telefone", "telefone"))
        assertTrue(Matcher.casa("Cidade onde mora", "cidade"))
    }

    @Test
    fun `sobrenome NAO casa com nome - substring nao vale`() {
        assertFalse(Matcher.casa("Sobrenome", "nome"))
        assertFalse(Matcher.casa("nome", "Sobrenome"))
    }

    @Test
    fun `vazio nunca casa`() {
        assertFalse(Matcher.casa("", "nome"))
        assertFalse(Matcher.casa("Nome", ""))
        assertFalse(Matcher.casa("!!!", "???"))
    }
}

class PerfilTest {

    @Test
    fun `le linhas chave dois-pontos valor e ignora o resto`() {
        val p = Profile("nome: Maria da Graça\nlinha solta sem par\nemail: maria@exemplo.com\n: sem chave", emptyList())
        assertEquals("Maria da Graça", p.valorPara("Nome")?.valor)
        assertEquals("maria@exemplo.com", p.valorPara("E-mail")?.valor)
        assertNull(p.valorPara("telefone"))
    }

    @Test
    fun `url com dois-pontos no valor sobrevive ao parse`() {
        val p = Profile("linkedin: https://linkedin.com/in/maria", emptyList())
        assertEquals("https://linkedin.com/in/maria", p.valorPara("LinkedIn")?.valor)
    }

    @Test
    fun `aprendido vence o perfil base e o mais recente vence o mais antigo`() {
        val p = Profile(
            "cidade: Porto Alegre",
            listOf(
                Learned("cidade", "Dublin", quandoMs = 100, origem = "corrigiu"),
                Learned("cidade", "Barcelona", quandoMs = 200, origem = "corrigiu"),
            ),
        )
        val achado = p.valorPara("Cidade")!!
        assertEquals("Barcelona", achado.valor)
        assertEquals("aprendido", achado.fonte)
    }
}

class JanelaTest {

    @Test
    fun `teclado abrindo nao encerra a sessao`() {
        assertFalse(WindowRule.encerraSessao("com.google.android.inputmethod.latin", null, "com.mygoll.funform"))
        assertFalse(WindowRule.encerraSessao("com.samsung.android.honeyboard", null, "com.mygoll.funform"))
        assertFalse(WindowRule.encerraSessao("com.android.chrome", "android.inputmethodservice.SoftInputWindow", "com.mygoll.funform"))
    }

    @Test
    fun `o proprio app nao encerra a sessao`() {
        assertFalse(WindowRule.encerraSessao("com.mygoll.funform", null, "com.mygoll.funform"))
    }

    @Test
    fun `mudanca de pagina no mesmo app encerra - formulario multipagina salva por pagina`() {
        assertTrue(WindowRule.encerraSessao("com.android.chrome", "android.widget.FrameLayout", "com.mygoll.funform"))
    }
}

class TextoTest {

    @Test
    fun `tsv escape e desescape sao inversos`() {
        val original = "valor com\ttab, quebra\nde linha e barra \\ no meio"
        assertEquals(original, Tsv.des(Tsv.esc(original)))
        assertFalse(Tsv.esc(original).contains('\n'))
        assertFalse(Tsv.esc(original).contains('\t'))
    }

    @Test
    fun `json escapa aspas barra e controle`() {
        assertEquals("\"a \\\"b\\\" \\\\ c\\n\"", Json.str("a \"b\" \\ c\n"))
    }
}
