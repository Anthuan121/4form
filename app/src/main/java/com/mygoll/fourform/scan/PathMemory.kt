package com.mygoll.fourform.scan

/**
 * PATH learning (brief 242): besides learning values, the app learns HOW to find a
 * label in each place. What's stored is just the STATISTIC of which ladder level
 * resolved it, per context (the app's package; for web, the browser's package):
 *
 *     com.android.chrome  labeledBy  9
 *     com.android.chrome  vizinho    1
 *
 * ⛔ Never a viewId, coordinate, position index, or someone else's label text: an id
 * expires on the next page (a lesson measured in Binspector: an anchor is text, not an
 * id) and a label is content from someone else's page, with no reason to stay on the
 * device. The level whitelist is the lock. The preferred level is a SHORTCUT: if it
 * doesn't resolve on the next visit, the whole ladder still runs.
 */
class PathMemory private constructor(
    private val contagens: MutableMap<Pair<String, String>, Int>,
) {

    companion object {
        fun vazio() = PathMemory(mutableMapOf())

        /** Format: one line "context \t level \t count". A malformed line is ignored. */
        fun de(tsv: String): PathMemory {
            val mapa = mutableMapOf<Pair<String, String>, Int>()
            for (linha in tsv.lines()) {
                val p = linha.split('\t')
                if (p.size != 3) continue
                val n = p[2].toIntOrNull() ?: continue
                if (p[1] !in Labeler.NIVEIS) continue
                mapa[p[0] to p[1]] = n
            }
            return PathMemory(mapa)
        }

        /** "vizinho:12px" -> "vizinho": the distance is a point-in-time confidence, not a statistic. */
        fun nivelDaOrigem(origem: String): String = origem.substringBefore(':')
    }

    fun registrar(contexto: String, origem: String) {
        if (contexto.isBlank()) return
        val nivel = nivelDaOrigem(origem)
        if (nivel !in Labeler.NIVEIS) return // whitelist: raw viewId and junk never get in
        contagens.merge(contexto to nivel, 1, Int::plus)
    }

    fun nivelPreferido(contexto: String): String? =
        contagens.entries.filter { it.key.first == contexto }.maxByOrNull { it.value }?.key?.second

    fun serializar(): String =
        contagens.entries.joinToString("\n") { "${it.key.first}\t${it.key.second}\t${it.value}" }
}
