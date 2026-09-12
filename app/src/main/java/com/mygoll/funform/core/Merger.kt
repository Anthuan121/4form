package com.mygoll.funform.core

/**
 * Mescla os pares confirmados na tela por cima do perfil que já existe, chave a chave:
 * chave igual (normalizada, igualdade EXATA: "nome" não atropela "nome completo") tem o
 * valor substituído na própria linha; chave nova entra no fim; linha que não é par fica
 * como está. Os aprendidos vivem em outro arquivo e não passam por aqui — correção da
 * pessoa continua vencendo o perfil base, como sempre (regra do 240).
 */
object Merger {

    fun mesclar(base: String, novos: List<Pair<String, String>>): String {
        val pendentes = LinkedHashMap<String, Pair<String, String>>()
        for ((chave, valor) in novos) {
            val norm = Matcher.normalizar(chave)
            if (norm.isNotEmpty() && valor.isNotBlank() && norm !in pendentes) pendentes[norm] = chave to valor
        }

        val corpo = base.lines().map { linha ->
            val i = linha.indexOf(':')
            if (i <= 0) return@map linha
            val chave = linha.substring(0, i).trim()
            val novo = pendentes.remove(Matcher.normalizar(chave)) ?: return@map linha
            "$chave: ${novo.second.trim()}"
        }

        val extras = pendentes.values.map { "${it.first.trim()}: ${it.second.trim()}" }
        return (corpo + extras).joinToString("\n").trim()
    }
}
