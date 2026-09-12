package com.mygoll.fourform.agent

import com.mygoll.fourform.scan.Learned
import com.mygoll.fourform.scan.RotuloVizinho
import com.mygoll.fourform.scan.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Profile de pessoa INVENTADA (regra do brief: nenhum dado real do usuário em fixture). */
private fun perfilTeste(aprendidos: List<Learned> = emptyList()) = Profile(
    """
    nome: Maria da Graça Boaventura
    email: maria.boaventura@exemplo.com
    telefone: +353 83 000 0000
    cidade: Dublin
    """.trimIndent(),
    aprendidos,
)

private var relogio = 1_000L
private fun novaSessao(perfil: Profile = perfilTeste()) = Session(perfil) { ++relogio }

class SessaoDecisaoTest {

    @Test
    fun `campo com rotulo conhecido e dado no perfil e preenchido`() {
        val s = novaSessao()
        val d = s.decidir(Field(chave = "c", hint = "Nome"))
        assertTrue(d is Session.Decisao.Preencher)
        assertEquals("Maria da Graça Boaventura", (d as Session.Decisao.Preencher).valor)
        assertEquals("perfil", d.fonte)
    }

    @Test
    fun `campo de senha NUNCA e preenchido - trava dura nao configuravel`() {
        val s = novaSessao()
        // mesmo com rótulo que casaria com o perfil, senha é pulo imediato
        val d = s.decidir(Field(chave = "c", hint = "nome", senha = true))
        assertTrue(d is Session.Decisao.DeixarAberto)
        assertTrue((d as Session.Decisao.DeixarAberto).motivo.contains("password"))
    }

    @Test
    fun `campo que ja tem texto nao e sobrescrito`() {
        val s = novaSessao()
        val d = s.decidir(Field(chave = "c", hint = "Nome", textoAtual = "escrito pelo usuário"))
        assertTrue(d is Session.Decisao.DeixarAberto)
        assertTrue((d as Session.Decisao.DeixarAberto).motivo.contains("already has text"))
    }

    @Test
    fun `campo sem dado no perfil fica aberto com o motivo dizendo o rotulo`() {
        val s = novaSessao()
        val d = s.decidir(Field(chave = "c", hint = "Anos de experiência com Figma"))
        assertTrue(d is Session.Decisao.DeixarAberto)
        assertTrue((d as Session.Decisao.DeixarAberto).motivo.contains("Anos de experiência com Figma"))
        assertTrue(d.motivo.contains("I don't have this in your profile"))
    }

    @Test
    fun `rotulo que e viewId cru JAMAIS casa com o perfil - criterio 5, o caso real do print`() {
        // perfil ARMADO com a chave que casaria por texto: nem assim pode casar, porque
        // "question 66138698" identifica a vaga, não a pergunta — casar seria inventar
        val perfilArmado = Profile("question 66138698: valor-pegadinha\nnome: Maria", emptyList())
        val s = Session(perfilArmado) { ++relogio }
        val d = s.decidir(Field(chave = "q", viewId = "question_66138698"))
        assertTrue(d is Session.Decisao.DeixarAberto)
        assertEquals("I don't know what this field is asking", (d as Session.Decisao.DeixarAberto).motivo)
    }
}

/**
 * Brief 260: campos que a pessoa reserva pra si, mesmo quando o perfil tem o valor.
 * TESTE JVM 1 é o que prova o brief inteiro — sem ele passando, a régua não existe.
 */
class SessaoCampoReservadoTest {

    /** Perfil ARMADO com o próprio dado reservado, pra provar que ter o valor não basta. */
    private fun perfilComSalario() = Profile(
        """
        nome: Maria da Graça Boaventura
        salary expectations: 55k
        pretensao salarial: R$ 12.000
        gender: female
        genero: feminino
        i declare that the information is true: sim
        """.trimIndent(),
        emptyList(),
    )

    // TESTE JVM 1: campo reservado NÃO é preenchido mesmo com o valor presente no perfil.
    @Test
    fun `TESTE JVM 1 - campo reservado nao e preenchido mesmo com o valor no perfil`() {
        val s = Session(perfilComSalario()) { ++relogio }
        val d = s.decidir(Field(chave = "salario", hint = "Salary expectations"))
        assertTrue(d is Session.Decisao.DeixarAberto)
        assertTrue((d as Session.Decisao.DeixarAberto).reservado)
        // não vaza o valor do perfil no motivo nem em lugar nenhum
        assertFalse(d.motivo.contains("55k"))
    }

    // TESTE JVM 2: as três famílias reconhecidas em inglês e português.
    @Test
    fun `TESTE JVM 2 - as tres familias casam em ingles e portugues`() {
        val s = Session(perfilComSalario()) { ++relogio }

        val negociacaoEn = s.decidir(Field(chave = "a", hint = "Salary expectations"))
        val negociacaoPt = s.decidir(Field(chave = "b", hint = "Pretensão salarial"))
        val identidadeEn = s.decidir(Field(chave = "c", hint = "Gender"))
        val identidadePt = s.decidir(Field(chave = "d", hint = "Gênero"))
        val juridicoEn = s.decidir(Field(chave = "e", hint = "I declare that the information is true"))

        for (d in listOf(negociacaoEn, negociacaoPt, identidadeEn, identidadePt, juridicoEn)) {
            assertTrue(d is Session.Decisao.DeixarAberto)
            assertTrue((d as Session.Decisao.DeixarAberto).reservado)
        }
        assertEquals(
            CampoReservado.Familia.NEGOCIACAO,
            CampoReservado.deste("Salary expectations")?.familia,
        )
        assertEquals(
            CampoReservado.Familia.NEGOCIACAO,
            CampoReservado.deste("Pretensão salarial")?.familia,
        )
        assertEquals(CampoReservado.Familia.IDENTIDADE, CampoReservado.deste("Gender")?.familia)
        assertEquals(CampoReservado.Familia.IDENTIDADE, CampoReservado.deste("Gênero")?.familia)
        assertEquals(
            CampoReservado.Familia.JURIDICO,
            CampoReservado.deste("I declare that the information is true")?.familia,
        )
    }

    // TESTE JVM 3: campo comum continua sendo preenchido normalmente (a régua não vira
    // rede que pega tudo).
    @Test
    fun `TESTE JVM 3 - campo comum continua sendo preenchido normalmente`() {
        val s = Session(perfilComSalario()) { ++relogio }
        val d = s.decidir(Field(chave = "nome", hint = "Nome"))
        assertTrue(d is Session.Decisao.Preencher)
        assertEquals("Maria da Graça Boaventura", (d as Session.Decisao.Preencher).valor)
        assertNull(CampoReservado.deste("Nome"))
        assertNull(CampoReservado.deste("Email"))
        assertNull(CampoReservado.deste("Anos de experiência com Figma"))
    }

    @Test
    fun `motivo do campo reservado fala em primeira pessoa e nao parece falha de dado`() {
        val motivo = CampoReservado.deste("Salary expectations")!!
        assertFalse(motivo.frase.contains("I don't have"))
        assertTrue(motivo.frase.contains("your call") || motivo.frase.contains("mine"))
    }

    @Test
    fun `registro guarda reservado separado do motivo de sem dado`() {
        val s = Session(perfilComSalario()) { ++relogio }
        val reservado = Field(chave = "salario", hint = "Salary expectations")
        val semDado = Field(chave = "figma", hint = "Anos de experiência com Figma")
        s.registrar(reservado, s.decidir(reservado))
        s.registrar(semDado, s.decidir(semDado))
        val regs = s.registros().associateBy { it.campo.chave }
        assertTrue(regs.getValue("salario").reservado)
        assertFalse(regs.getValue("figma").reservado)
    }
}

class SessaoPainelTest {

    @Test
    fun `desfazer do painel - o preenchido volta a aberto e sai dos preenchidos do recibo`() {
        val s = novaSessao()
        val campo = Field(chave = "nome", hint = "Nome")
        s.registrar(campo, s.decidir(campo)) // preencheu "Maria..."
        assertTrue(s.desfazer("nome"))
        val reg = s.registros().single()
        assertEquals("aberto", reg.acao)
        assertNull(reg.valorEscrito)
        assertEquals("you undid it", reg.motivo)
        assertNull(s.fechar()) // nada preenchido, nada aprendido: sem recibo
    }

    @Test
    fun `escrever pelo painel - vira preenchido, aprende na hora, e o eco nao duplica`() {
        val s = novaSessao()
        val campo = Field(chave = "figma", hint = "Anos de experiência com Figma")
        s.registrar(campo, s.decidir(campo)) // aberto: perfil não tem
        assertTrue(s.escreverAgora("figma", "5 anos"))
        val reg = s.registros().single()
        assertEquals("preencheu", reg.acao)
        assertEquals("you, in the panel", reg.fonte)
        assertFalse(s.textoMudou("figma", "5 anos")) // o eco do ACTION_SET_TEXT
        val recibo = s.fechar()!!
        assertEquals(1, recibo.aprendidos.size)
        assertEquals("5 anos", recibo.aprendidos[0].valor)
        assertEquals("preencheu", recibo.aprendidos[0].origem)
    }

    @Test
    fun `escrever pelo painel em campo de viewId cru - escreve mas NAO aprende`() {
        val s = novaSessao()
        val campo = Field(chave = "q", viewId = "question_66138698")
        s.registrar(campo, s.decidir(campo))
        assertTrue(s.escreverAgora("q", "resposta da pessoa"))
        // aprender "question 66138698 → resposta" seria guardar lixo que expira na
        // próxima vaga; o valor entra no campo e só
        assertTrue(s.fechar()!!.aprendidos.isEmpty())
    }

    @Test
    fun `escrever pelo painel nunca toca campo de senha`() {
        val s = novaSessao()
        val campo = Field(chave = "pwd", hint = "Password", senha = true)
        s.registrar(campo, s.decidir(campo))
        assertFalse(s.escreverAgora("pwd", "segredo"))
    }
}

class SessaoAprendizadoTest {

    @Test
    fun `usuario preenche campo que ficou aberto - app aprende com origem preencheu`() {
        val s = novaSessao()
        val campo = Field(chave = "figma", hint = "Anos de experiência com Figma")
        s.registrar(campo, s.decidir(campo))
        assertTrue(s.textoMudou("figma", "5 anos"))
        val recibo = s.fechar()!!
        assertEquals(1, recibo.aprendidos.size)
        assertEquals("Anos de experiência com Figma", recibo.aprendidos[0].rotulo)
        assertEquals("5 anos", recibo.aprendidos[0].valor)
        assertEquals("preencheu", recibo.aprendidos[0].origem)
    }

    @Test
    fun `usuario corrige campo preenchido - app aprende com origem corrigiu`() {
        val s = novaSessao()
        val campo = Field(chave = "cidade", hint = "Cidade")
        s.registrar(campo, s.decidir(campo)) // preencheu "Dublin"
        assertTrue(s.textoMudou("cidade", "Barcelona"))
        val recibo = s.fechar()!!
        assertEquals("corrigiu", recibo.aprendidos[0].origem)
        assertEquals("Barcelona", recibo.aprendidos[0].valor)
    }

    @Test
    fun `o eco da nossa propria escrita NAO vira aprendizado`() {
        val s = novaSessao()
        val campo = Field(chave = "cidade", hint = "Cidade")
        s.registrar(campo, s.decidir(campo))
        assertFalse(s.textoMudou("cidade", "Dublin")) // é o que nós escrevemos
        assertNull(s.fechar()?.aprendidos?.firstOrNull()) // recibo existe (preencheu), sem aprendizado
    }

    @Test
    fun `digitacao letra a letra - o ultimo valor vence, um aprendizado so`() {
        val s = novaSessao()
        val campo = Field(chave = "figma", hint = "Ferramenta favorita")
        s.registrar(campo, s.decidir(campo))
        s.textoMudou("figma", "F")
        s.textoMudou("figma", "Fig")
        s.textoMudou("figma", "Figma")
        val recibo = s.fechar()!!
        assertEquals(1, recibo.aprendidos.size)
        assertEquals("Figma", recibo.aprendidos[0].valor)
    }

    @Test
    fun `campo de senha nunca gera aprendizado mesmo com texto mudando`() {
        val s = novaSessao()
        val campo = Field(chave = "pwd", hint = "Password", senha = true)
        s.registrar(campo, s.decidir(campo))
        assertFalse(s.textoMudou("pwd", "segredo123"))
        assertNull(s.fechar()) // nada preenchido, nada aprendido
    }

    @Test
    fun `campo nao rastreado na varredura nao gera aprendizado`() {
        val s = novaSessao()
        assertFalse(s.textoMudou("campo-fantasma", "qualquer coisa"))
    }

    @Test
    fun `valor aprendido vence na rodada seguinte`() {
        // rodada 1: usuário corrige a cidade
        val s1 = novaSessao()
        val campo = Field(chave = "cidade", hint = "Cidade")
        s1.registrar(campo, s1.decidir(campo))
        s1.textoMudou("cidade", "Barcelona")
        val aprendidos = s1.fechar()!!.aprendidos

        // rodada 2: mesmo campo, perfil agora carrega o aprendido
        val s2 = novaSessao(perfilTeste(aprendidos))
        val d = s2.decidir(campo)
        assertTrue(d is Session.Decisao.Preencher)
        assertEquals("Barcelona", (d as Session.Decisao.Preencher).valor)
        assertEquals("aprendido", d.fonte)
    }
}

class SessaoFimTest {

    @Test
    fun `a observacao PARA quando a sessao fecha`() {
        val s = novaSessao()
        val campo = Field(chave = "figma", hint = "Ferramenta")
        s.registrar(campo, s.decidir(campo))
        s.fechar()
        assertFalse(s.ativa)
        assertFalse(s.textoMudou("figma", "digitado depois do fim"))
        assertNull(s.fechar()) // fechar duas vezes não ressuscita nada
    }

    @Test
    fun `sessao sem preenchimento e sem aprendizado NAO gera recibo`() {
        val s = novaSessao()
        val campo = Field(chave = "x", hint = "Field que ninguém conhece")
        s.registrar(campo, s.decidir(campo)) // fica aberto
        assertNull(s.fechar()) // recibo que pisca à toa é ruído
    }

    @Test
    fun `sessao que preencheu gera recibo mesmo sem aprendizado`() {
        val s = novaSessao()
        val campo = Field(chave = "nome", hint = "Nome")
        s.registrar(campo, s.decidir(campo))
        val recibo = s.fechar()
        assertNotNull(recibo)
        assertEquals(1, recibo!!.preenchidos.size)
    }

    @Test
    fun `escrita recusada pelo campo conta como aberto, nao como preenchido`() {
        val s = novaSessao()
        val campo = Field(chave = "nome", hint = "Nome")
        s.registrar(campo, s.decidir(campo), escreveu = false)
        assertNull(s.fechar()) // nada de fato escrito, nada aprendido: sem recibo
    }
}

/**
 * O diagnóstico SAI do aparelho pelo botão de compartilhar (critérios 7 e 8): rótulo,
 * nível e decisão sim; valor escrito e qualquer coisa de senha, nunca.
 */
class DiagnosticoTest {

    private fun json(s: Session, aprendidos: Int = 0) = Diagnostics.json(
        build = "0.3-motor",
        pacote = "com.android.chrome",
        quando = "2026-09-10 03:00:00",
        totalNos = 412,
        voltasDeRolagem = 2,
        paradaPor = "rolei e não apareceu campo novo: cheguei ao fim do formulário",
        registros = s.registros(),
        aprendidosNaSessao = aprendidos,
        laco = listOf("varredura 0: rolável android.widget.ScrollView (sem id) 1080x1800 · 3 campos · espera 500ms"),
    )

    /**
     * Instrumentação do laço (fechador 10/09): sem ela, "rolei e não apareceu campo novo"
     * não distingue fim do formulário de contêiner rolável errado. E ela não pode levar
     * dado pessoal para fora do aparelho: só forma, tamanho e tempo.
     */
    @Test
    fun `o diagnostico leva a decisao de rolagem de cada varredura, e sem valor de campo`() {
        val s = novaSessao()
        val nome = Field(chave = "nome", hint = "Nome")
        s.registrar(nome, s.decidir(nome))
        val j = json(s)
        assertTrue(j.contains("\"laco\": ["))
        assertTrue(j.contains("rolável android.widget.ScrollView"))
        assertTrue(j.contains("espera 500ms"))
        assertFalse(j.contains("Maria"))
    }

    @Test
    fun `diz por campo o rotulo, QUAL NIVEL resolveu, o viewId cru, a decisao e o motivo`() {
        val s = novaSessao()
        val nome = Field(chave = "nome", hint = "Nome")
        val cru = Field(chave = "q", viewId = "question_66138698")
        s.registrar(nome, s.decidir(nome))
        s.registrar(cru, s.decidir(cru))
        val j = json(s)
        assertTrue(j.contains("\"nivel\": \"hint\""))
        assertTrue(j.contains("\"nivel\": \"viewId-cru\""))
        assertTrue(j.contains("question_66138698")) // o viewId cru é dado sobre o board, entra
        assertTrue(j.contains("\"viewIdCru\": true"))
        assertTrue(j.contains("I don't know what this field is asking"))
        assertTrue(j.contains("\"voltasDeRolagem\": 2"))
        assertTrue(j.contains("\"paradaPor\""))
    }

    @Test
    fun `VALOR escrito jamais aparece - nem o preenchido, nem o que o usuario digitou`() {
        val s = novaSessao()
        val nome = Field(chave = "nome", hint = "Nome")
        val figma = Field(chave = "figma", hint = "Anos de Figma")
        s.registrar(nome, s.decidir(nome)) // escreve "Maria da Graça Boaventura"
        s.registrar(figma, s.decidir(figma))
        s.textoMudou("figma", "cinco anos e meio") // digitado pelo usuário
        val recibo = s.fechar()!!
        val j = json(s, aprendidos = recibo.aprendidos.size)
        assertFalse(j.contains("Maria"))
        assertFalse(j.contains("cinco anos e meio"))
        assertTrue(j.contains("\"acao\": \"preencheu\""))
        assertTrue(j.contains("\"fonte\": \"perfil\""))
        assertTrue(j.contains("\"aprendidosNaSessao\": 1")) // a contagem sim, o conteúdo não
    }

    @Test
    fun `nada de campo de senha aparece alem da marca de que era senha`() {
        val s = novaSessao()
        val pwd = Field(chave = "pwd", hint = "Password", senha = true)
        s.registrar(pwd, s.decidir(pwd))
        s.textoMudou("pwd", "segredo-super-secreto")
        val j = json(s)
        assertFalse(j.contains("segredo-super-secreto"))
        assertTrue(j.contains("\"senha\": true"))
    }

    @Test
    fun `o exemplar completo do cenario do print de 10-09 sai valido e sem nada da pessoa`() {
        // pessoa INVENTADA; espelha o board real das 00:08 de 10/09: perguntas custom com
        // viewId cru + os campos de nome que só a rolagem revela. O arquivo vai para
        // build/ (fora do repo) só para o relatório do brief citar um exemplar real.
        val perfil = Profile(
            "first name: Maria\nlast name: Boaventura\nemail: maria.boaventura@exemplo.com\nphone: +353 83 000 0000",
            emptyList(),
        )
        val s = Session(perfil) { ++relogio }
        val campos = listOf(
            Field(chave = "first_name", labeledBy = "First name", labeledByPresente = true, viewId = "first_name", dentroDeWebView = true),
            Field(chave = "email", labeledBy = "Email", labeledByPresente = true, viewId = "email", dentroDeWebView = true),
            Field(chave = "q1", viewId = "question_66138698", dentroDeWebView = true, rotuloVizinho = RotuloVizinho("Why do you want to work at Exemplo Corp?", 96)),
            Field(chave = "q2", viewId = "question_66138699", dentroDeWebView = true, rotuloVizinho = RotuloVizinho("Cover letter", 402)),
            Field(chave = "pwd", hint = "Password", senha = true),
        )
        campos.forEach { s.registrar(it, s.decidir(it)) }
        val j = Diagnostics.json(
            "0.3-motor", "com.android.chrome", "2026-09-10 00:08:00",
            totalNos = 634, voltasDeRolagem = 3,
            paradaPor = "rolei e não apareceu campo novo: cheguei ao fim do formulário",
            registros = s.registros(), aprendidosNaSessao = 0,
        )
        assertFalse(j.contains("Maria"))
        assertFalse(j.contains("maria.boaventura"))
        assertTrue(j.contains("\"nivel\": \"labeledBy\""))
        java.io.File("build/diagnostico-exemplo.json").writeText(j)
    }

    @Test
    fun `quando nenhum nivel resolveu, os sinais dizem O QUE HAVIA no no - criterio 7`() {
        val s = novaSessao()
        val cego = Field(
            chave = "x",
            labeledByPresente = true, // a relação existia, o alvo não tinha texto
            rotuloVizinho = RotuloVizinho("texto longe", 500), // fora do raio de 240px
        )
        s.registrar(cego, s.decidir(cego))
        val j = json(s)
        assertTrue(j.contains("\"nivel\": \"nenhum\""))
        assertTrue(j.contains("\"labeledBy\": \"presente mas sem texto\""))
        assertTrue(j.contains("\"vizinhoPx\": 500"))
        assertTrue(j.contains("\"hint\": false"))
    }
}
