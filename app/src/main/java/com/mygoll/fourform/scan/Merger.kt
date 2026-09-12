package com.mygoll.fourform.scan

/**
 * Merges the pairs confirmed on screen over the profile that already exists, key by key:
 * a matching key (normalized, EXACT equality: "nome" doesn't run over "nome completo")
 * has its value replaced in the same line; a new key gets appended at the end; a line
 * that isn't a pair stays as it is. Learned entries live in a different file and don't
 * go through here. A person's correction still beats the base profile, as always
 * (rule from 240).
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
