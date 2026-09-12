package com.mygoll.fourform.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Brief 258: the profile ACCUMULATES across imports. Import A, then B: what only A had must
 * survive, a repeated key must land on B's value, and removing B must bring A's value back:
 * no separate undo log needed, since Fontes.efetivo just recomputes from what's left.
 */
class FontesTest {

    @Test
    fun bSomaEmCimaDeANaoSubstitui() {
        val a = listOf(
            Entrada("nome", "Maria", "cv-design.md", 1000),
            Entrada("cidade", "Dublin", "cv-design.md", 1000),
        )
        val b = listOf(
            Entrada("nome", "Maria da Graça", "cv-produto.md", 2000), // chave repetida, B é mais novo
            Entrada("cargo", "Product Designer", "cv-produto.md", 2000), // chave nova
        )
        val acumulado = Fontes.acumular(a, b)
        val efetivo = Fontes.efetivo(acumulado).associateBy { it.chave }

        // só existia em A: sobrevive
        assertEquals("Dublin", efetivo["cidade"]?.valor)
        // chave repetida: fica com o valor de B, o mais recente
        assertEquals("Maria da Graça", efetivo["nome"]?.valor)
        // só existia em B: entra
        assertEquals("Product Designer", efetivo["cargo"]?.valor)
    }

    @Test
    fun removerFonteDevolveOValorDaOutraFonte() {
        val a = listOf(Entrada("nome", "Maria", "cv-design.md", 1000))
        val b = listOf(Entrada("nome", "Maria da Graça", "cv-produto.md", 2000))
        val acumulado = Fontes.acumular(a, b)

        val semB = Fontes.removerFonte(acumulado, "cv-produto.md")
        val efetivo = Fontes.efetivo(semB).associateBy { it.chave }

        assertEquals("Maria", efetivo["nome"]?.valor)
        assertEquals(1, efetivo.size)
    }

    @Test
    fun removerFonteSoTiraOQueVeioSODela() {
        val a = listOf(
            Entrada("nome", "Maria", "cv-design.md", 1000),
            Entrada("cidade", "Dublin", "cv-design.md", 1000),
        )
        val b = listOf(Entrada("cargo", "Product Designer", "cv-produto.md", 2000))
        val acumulado = Fontes.acumular(a, b)

        val semB = Fontes.removerFonte(acumulado, "cv-produto.md")
        val efetivo = Fontes.efetivo(semB).associateBy { it.chave }

        assertEquals(setOf("nome", "cidade"), efetivo.keys)
        assertEquals("Maria", efetivo["nome"]?.valor)
    }

    @Test
    fun valoresDiferentesParaAMesmaChaveViramConflito() {
        val entradas = listOf(
            Entrada("cidade", "Dublin", "cv-design.md", 1000),
            Entrada("cidade", "Madrid", "cv-produto.md", 2000),
        )
        assertTrue(Fontes.conflitos(entradas).contains(Matcher.normalizar("cidade")))
        // o mais recente vence, mas o conflito continua registrado
        assertEquals("Madrid", Fontes.efetivo(entradas).first().valor)
    }

    @Test
    fun textoPerfilRendersEfetivoComoLinhasChaveValor() {
        val entradas = listOf(
            Entrada("nome", "Maria", "cv-design.md", 1000),
            Entrada("cidade", "Dublin", "cv-design.md", 1000),
        )
        val texto = Fontes.textoPerfil(entradas)
        assertTrue(texto.contains("nome: Maria"))
        assertTrue(texto.contains("cidade: Dublin"))
    }

    @Test
    fun resumoPorFonteContaSoOQueAindaEstaEmUso() {
        val a = listOf(
            Entrada("nome", "Maria", "cv-design.md", 1000),
            Entrada("cidade", "Dublin", "cv-design.md", 1000),
        )
        val b = listOf(Entrada("nome", "Maria da Graça", "cv-produto.md", 2000))
        val acumulado = Fontes.acumular(a, b)
        val resumo = Fontes.resumoPorFonte(acumulado).associateBy { it.fonte }

        // cv-design.md perdeu "nome" para cv-produto.md, mas "cidade" ainda é dele
        assertEquals(1, resumo["cv-design.md"]?.itensAtivos)
        assertEquals(1, resumo["cv-produto.md"]?.itensAtivos)
    }
}
