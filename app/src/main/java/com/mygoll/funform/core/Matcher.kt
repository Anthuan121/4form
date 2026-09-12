package com.mygoll.funform.core

import java.text.Normalizer

/**
 * Casa rótulo de campo com chave do perfil por texto, e é burro DE PROPÓSITO:
 * a inteligência é o trabalho do dia do evento; aqui só o esqueleto.
 * A comparação é por PALAVRA inteira, não por substring: "Sobrenome" NÃO casa com a
 * chave "nome" (substring casaria, e preencheria o campo errado).
 */
object Matcher {

    fun normalizar(s: String): String {
        val semAcento = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return semAcento.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun casa(rotulo: String, chave: String): Boolean {
        val r = normalizar(rotulo)
        val c = normalizar(chave)
        if (r.isEmpty() || c.isEmpty()) return false
        if (r == c) return true
        // "E-mail" normaliza para "e mail"; sem isto não casaria com a chave "email"
        if (r.replace(" ", "") == c.replace(" ", "")) return true
        val palavrasR = r.split(' ').toSet()
        val palavrasC = c.split(' ').toSet()
        // um lado contido no outro, palavra a palavra: "nome" casa "nome completo",
        // mas "nome" não casa "sobrenome".
        return palavrasR.containsAll(palavrasC) || palavrasC.containsAll(palavrasR)
    }
}
