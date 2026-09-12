package com.mygoll.funform.core

/**
 * A parte PURA da sonda de bancada: como se chama o arquivo que sobe e para onde ele vai.
 * Fica aqui, longe de Android, para ter teste — a parte que fala com a rede (Bench.kt)
 * é fina de propósito, porque rede não se testa em JVM.
 *
 * Contexto (10/09/2026): mesma sonda que o Binspector usa desde 12/08. É PUT puro no módulo
 * WebDAV do nginx: sem serviço, sem código de servidor, sem porta nova. O caminho tem um
 * segredo no meio da URL e o nginx recusa qualquer método que não seja PUT, então nem quem
 * descobrir o endereço consegue LER de volta o que subiu.
 */
object Probe {

    const val BASE = "https://hooks.mygoll.com/sonda/fxjSuQ7VJfaa1ZOiSK0dI8jHgwI4/"

    /**
     * Um arquivo por envio, nunca sobrescrito, com o milissegundo no nome: é o que deixa o
     * CC ver a SEQUÊNCIA de varreduras ("na 1ª volta leu 3 campos, na 2ª apareceram mais 4")
     * em vez de só o retrato final. Prefixo "preenche-" separa dos dumps do Binspector, que
     * moram na mesma pasta.
     */
    fun nome(quandoMs: Long): String = "preenche-$quandoMs.json"

    fun url(quandoMs: Long): String = BASE + nome(quandoMs)
}
