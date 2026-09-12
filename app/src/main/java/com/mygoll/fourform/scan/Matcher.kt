package com.mygoll.fourform.scan

import java.text.Normalizer

/**
 * Matches a field label to a profile key by text, and is dumb ON PURPOSE: the
 * intelligence is the event day's work; here it's just the skeleton.
 * The comparison is by WHOLE WORD, not by substring: "Sobrenome" (Surname) does NOT
 * match the key "nome" (name) (a substring would match, and fill the wrong field).
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
        // "E-mail" normalizes to "e mail"; without this it wouldn't match the key "email"
        if (r.replace(" ", "") == c.replace(" ", "")) return true
        val palavrasR = r.split(' ').toSet()
        val palavrasC = c.split(' ').toSet()
        // one side contained in the other, word by word: "nome" (name) matches "nome
        // completo" (full name), but "nome" doesn't match "sobrenome" (surname).
        return palavrasR.containsAll(palavrasC) || palavrasC.containsAll(palavrasR)
    }
}
