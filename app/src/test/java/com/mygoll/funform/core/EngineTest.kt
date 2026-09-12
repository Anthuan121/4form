package com.mygoll.funform.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun campo(
    chave: String,
    topo: Int = 0,
    hint: String? = null,
    texto: String? = null,
    vizinho: RotuloVizinho? = null,
) = Field(
    chave = chave,
    hint = hint,
    caixa = Box(0, topo, 400, topo + 60),
    textoAtual = texto,
    rotuloVizinho = vizinho,
)

/** As QUATRO paradas do laço são o critério 3 do brief: laço sem parada provada é reprovado. */
class MotorTest {

    @Test
    fun `campo ja tratado NAO e preenchido de novo quando reaparece depois da rolagem`() {
        val m = Engine()
        m.receberVarredura("com.android.chrome", listOf(campo("a", hint = "First name")))
        assertEquals("a", (m.proximoPasso() as Engine.Passo.Agir).campo.chave)
        m.campoTratado("a")
        assertEquals(Engine.Passo.Rolar, m.proximoPasso())
        // a rolagem devolve o MESMO campo (mesma chave): não entra de novo
        val novos = m.receberVarredura("com.android.chrome", listOf(campo("a", hint = "First name")))
        assertEquals(0, novos)
    }

    @Test
    fun `campo sem chave firme que volta com caixa nova, mesmo rotulo e texto dentro, e revisita, nao campo novo`() {
        // sem viewId/hint/descrição a chave degrada para a caixa, e a caixa muda a cada
        // rolagem; a 2ª defesa (rótulo já visto + texto dentro) segura a duplicata
        val m = Engine()
        val antes = campo("caixa:0,500,400,560", topo = 500, vizinho = RotuloVizinho("Email", 10))
        m.receberVarredura("com.android.chrome", listOf(antes))
        m.campoTratado(antes.chave)
        assertEquals(Engine.Passo.Rolar, m.proximoPasso())
        val depois = campo(
            "caixa:0,120,400,180", topo = 120,
            vizinho = RotuloVizinho("Email", 10), texto = "maria@exemplo.com",
        )
        assertEquals(0, m.receberVarredura("com.android.chrome", listOf(depois)))
    }

    @Test
    fun `parada 1 - o laco termina quando a TELA NAO SE MOVE mais`() {
        // régua dele, 12/09: "o formulário só acaba quando você chega no fim da rolagem".
        // A régua velha (parar ao não achar campo novo) matava o laço em bloco do meio.
        val m = Engine()
        m.receberVarredura("com.android.chrome", listOf(campo("a", hint = "Nome")), assinatura = "tela1")
        m.campoTratado("a")
        assertEquals(Engine.Passo.Rolar, m.proximoPasso())
        // rolou e a tela é IDÊNTICA: chegou no fim de verdade
        m.receberVarredura("com.android.chrome", listOf(campo("a", hint = "Nome")), assinatura = "tela1")
        val fim = m.proximoPasso()
        assertTrue(fim is Engine.Passo.Fim)
        assertTrue((fim as Engine.Passo.Fim).motivo.contains("não se move mais"))
    }

    @Test
    fun `bloco vazio no MEIO do formulario nao encerra o laco`() {
        // o caso que ele descreveu: nome, telefone, um upload de currículo gigante, e
        // depois mais campos. A tela do upload não traz campo novo, mas o form não acabou.
        val m = Engine()
        m.receberVarredura("com.android.chrome", listOf(campo("a", hint = "Nome")), assinatura = "topo")
        m.campoTratado("a")
        // rolou e caiu no bloco do upload: zero campo novo, mas a tela SE MOVEU
        m.receberVarredura("com.android.chrome", emptyList(), assinatura = "meio-upload")
        assertEquals(Engine.Passo.Rolar, m.proximoPasso())
        // rolou de novo e voltou a ter campo
        m.receberVarredura("com.android.chrome", listOf(campo("b", hint = "Salário")), assinatura = "baixo")
        assertTrue(m.proximoPasso() is Engine.Passo.Agir)
    }

    @Test
    fun `parada 2 - o laco termina ao bater o teto de voltas`() {
        val m = Engine(tetoDeVoltas = 2)
        m.receberVarredura("app", listOf(campo("c0", hint = "campo 0")))
        m.campoTratado("c0")
        // cada rolagem revela um campo novo (página "infinita"): o teto é a única trava
        for (volta in 1..2) {
            assertEquals(Engine.Passo.Rolar, m.proximoPasso())
            m.receberVarredura("app", listOf(campo("c$volta", hint = "campo $volta")))
            m.campoTratado("c$volta")
        }
        val fim = m.proximoPasso()
        assertTrue(fim is Engine.Passo.Fim)
        assertTrue((fim as Engine.Passo.Fim).motivo.contains("teto"))
        assertEquals(2, m.voltasDeRolagem)
    }

    @Test
    fun `parada 3 - o laco termina se o pacote da tela mudar`() {
        val m = Engine()
        m.receberVarredura("com.android.chrome", listOf(campo("a", hint = "Nome")))
        m.campoTratado("a")
        assertEquals(Engine.Passo.Rolar, m.proximoPasso())
        m.receberVarredura("com.outra.coisa", listOf(campo("b", hint = "Outro")))
        val fim = m.proximoPasso()
        assertTrue(fim is Engine.Passo.Fim)
        assertTrue((fim as Engine.Passo.Fim).motivo.contains("mudou de app"))
    }

    @Test
    fun `parada 4 - o laco termina quando a tela nao rola mais`() {
        val m = Engine()
        m.receberVarredura("app", listOf(campo("a", hint = "Nome")))
        m.campoTratado("a")
        assertEquals(Engine.Passo.Rolar, m.proximoPasso())
        m.rolagemFalhou() // ACTION_SCROLL_FORWARD recusado: fim da página
        val fim = m.proximoPasso()
        assertTrue(fim is Engine.Passo.Fim)
        assertTrue((fim as Engine.Passo.Fim).motivo.contains("não rola"))
    }

    @Test
    fun `varredura sem campo nenhum termina na hora e o painel nao aparece`() {
        // critério 6: achouAlgumCampo=false é o que o serviço consulta antes de abrir o painel
        val m = Engine()
        m.receberVarredura("app", emptyList())
        val fim = m.proximoPasso()
        assertTrue(fim is Engine.Passo.Fim)
        assertTrue((fim as Engine.Passo.Fim).motivo.contains("não achei campo"))
        assertFalse(m.achouAlgumCampo)
        assertEquals(0, m.voltasDeRolagem) // sem campo não se rola atrás de nada
    }

    @Test
    fun `os campos saem em ordem visual de cima para baixo`() {
        val m = Engine()
        m.receberVarredura(
            "app",
            listOf(
                campo("baixo", topo = 900, hint = "Terceiro"),
                campo("topo", topo = 100, hint = "Primeiro"),
                campo("meio", topo = 500, hint = "Segundo"),
            ),
        )
        for (esperado in listOf("topo", "meio", "baixo")) {
            val agir = m.proximoPasso() as Engine.Passo.Agir
            assertEquals(esperado, agir.campo.chave)
            m.campoTratado(agir.campo.chave)
        }
    }
}

/** Critério 9: o que persiste é contagem por nível e contexto, e NADA além disso. */
class CaminhoTest {

    @Test
    fun `conta qual nivel resolveu e elege o preferido por contexto`() {
        val c = PathMemory.vazio()
        c.registrar("com.android.chrome", "labeledBy")
        c.registrar("com.android.chrome", "labeledBy")
        c.registrar("com.android.chrome", "vizinho:12px") // a distância não vira estatística
        c.registrar("com.booking", "hint")
        assertEquals("labeledBy", c.nivelPreferido("com.android.chrome"))
        assertEquals("hint", c.nivelPreferido("com.booking"))
        assertNull(c.nivelPreferido("com.nunca.visto"))
    }

    @Test
    fun `viewId cru e lixo NUNCA entram na estatistica - whitelist de niveis`() {
        val c = PathMemory.vazio()
        c.registrar("com.android.chrome", "viewId-cru")
        c.registrar("com.android.chrome", "question 66138698")
        c.registrar("", "hint") // contexto vazio também não
        assertEquals("", c.serializar())
    }

    @Test
    fun `persiste APENAS contexto, nivel e contagem - nem viewId, nem coordenada, nem rotulo alheio`() {
        val c = PathMemory.vazio()
        c.registrar("com.android.chrome", "labeledBy")
        c.registrar("com.android.chrome", "vizinho:377px")
        c.registrar("com.booking", "hint")
        for (linha in c.serializar().lines()) {
            val partes = linha.split('\t')
            assertEquals(3, partes.size)
            assertTrue("nível fora da whitelist: ${partes[1]}", partes[1] in Labeler.NIVEIS)
            assertTrue(partes[2].toInt() > 0)
        }
    }

    @Test
    fun `sobrevive ao disco - serializar e reler da o mesmo preferido`() {
        val c = PathMemory.vazio()
        c.registrar("com.android.chrome", "labeledBy")
        c.registrar("com.android.chrome", "labeledBy")
        c.registrar("com.android.chrome", "irmao")
        val relido = PathMemory.de(c.serializar())
        assertEquals("labeledBy", relido.nivelPreferido("com.android.chrome"))
        assertEquals(c.serializar().lines().toSet(), relido.serializar().lines().toSet())
    }

    @Test
    fun `linha corrompida ou nivel desconhecido no disco e ignorado em silencio`() {
        val relido = PathMemory.de("com.x\tlabeledBy\t3\nlixo sem tab\ncom.x\tnivel-inventado\t9\ncom.x\thint\tNaN")
        assertEquals("labeledBy", relido.nivelPreferido("com.x"))
    }
}
