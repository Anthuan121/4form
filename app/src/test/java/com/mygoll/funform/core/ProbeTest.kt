package com.mygoll.funform.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SondaTest {

    @Test
    fun `dois envios no mesmo segundo nao colidem`() {
        // O ponto da sonda é ver a SEQUÊNCIA de varreduras. Se dois PUTs caíssem no mesmo
        // nome, o segundo apagaria o primeiro no servidor e a evolução sumiria.
        assertNotEquals(Probe.nome(1789000000001L), Probe.nome(1789000000002L))
    }

    @Test
    fun `nome tem prefixo proprio para nao se misturar com o Binspector`() {
        // A pasta da sonda é compartilhada: os dumps do Binspector se chamam dump-*.json.gz.
        assertTrue(Probe.nome(1789000000000L).startsWith("preenche-"))
        assertEquals("preenche-1789000000000.json", Probe.nome(1789000000000L))
    }

    @Test
    fun `url e https e cai dentro do caminho da sonda`() {
        val url = Probe.url(1789000000000L)
        // http puro mandaria o diagnóstico em texto claro pela rede do evento.
        assertTrue(url.startsWith("https://"))
        assertEquals(Probe.BASE + Probe.nome(1789000000000L), url)
        // Sem barra dupla nem caminho quebrado ao concatenar.
        assertEquals(1, url.split("hooks.mygoll.com").size - 1)
        assertTrue(url.endsWith(".json"))
    }
}
