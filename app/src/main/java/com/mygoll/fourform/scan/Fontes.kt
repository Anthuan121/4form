package com.mygoll.fourform.scan

/**
 * One pair as it entered the profile: the key, the value, WHERE it came from (a file name, or
 * "typed by hand") and WHEN. The store keeps every entry ever imported, append-only: nothing
 * is thrown away just because a newer import touched the same key.
 */
data class Entrada(val chave: String, val valor: String, val fonte: String, val quandoMs: Long)

/**
 * Brief 258: the profile ACCUMULATES across imports instead of one file replacing the last.
 * Pure functions, no Context, no disk: Store.kt wires this to files.
 *
 * The model is an event log: every confirmed import appends entries, never rewrites them.
 * The effective value for a key is simply its most recent live entry. Removing a source is
 * just dropping its entries and recomputing: a key that also came from another source keeps
 * standing on that other entry, with no extra bookkeeping needed.
 */
object Fontes {

    /** Pure accumulation: appends [novos] on top of [existentes]. Nothing already there is lost. */
    fun acumular(existentes: List<Entrada>, novos: List<Entrada>): List<Entrada> = existentes + novos

    /** Drops every entry that came from [fonte]. Entries from any other source are untouched. */
    fun removerFonte(entradas: List<Entrada>, fonte: String): List<Entrada> =
        entradas.filterNot { it.fonte == fonte }

    /** One winner per key: the most recent entry (ties keep the one seen first). */
    fun efetivo(entradas: List<Entrada>): List<Entrada> =
        entradas.groupBy { Matcher.normalizar(it.chave) }
            .values
            .map { grupo -> grupo.reduce { a, b -> if (b.quandoMs > a.quandoMs) b else a } }

    /**
     * Keys where live entries disagree on the value. ⛔ The screen must show this instead of
     * silently picking the newest: that silent pick is exactly what item 5 of the brief bans.
     */
    fun conflitos(entradas: List<Entrada>): Set<String> =
        entradas.groupBy { Matcher.normalizar(it.chave) }
            .filterValues { grupo -> grupo.map { it.valor }.distinct().size > 1 }
            .keys

    /** Renders the effective entries back into "key: value" lines, oldest key first. */
    fun textoPerfil(entradas: List<Entrada>): String =
        efetivo(entradas)
            .sortedBy { it.quandoMs }
            .joinToString("\n") { "${it.chave.trim()}: ${it.valor.trim()}" }

    /** One row per source for the sources screen: name, when it was last used, how many keys stand from it. */
    data class ResumoFonte(val fonte: String, val ultimoUsoMs: Long, val itensAtivos: Int)

    fun resumoPorFonte(entradas: List<Entrada>): List<ResumoFonte> {
        val ativos = efetivo(entradas)
        return entradas.groupBy { it.fonte }
            .map { (fonte, doFonte) ->
                ResumoFonte(
                    fonte = fonte,
                    ultimoUsoMs = doFonte.maxOf { it.quandoMs },
                    itensAtivos = ativos.count { it.fonte == fonte },
                )
            }
            .sortedByDescending { it.ultimoUsoMs }
    }
}
