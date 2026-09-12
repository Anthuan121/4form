package com.mygoll.fourform.scan

/**
 * Aprendizado de CAMINHO (brief 242): além de aprender valores, o app aprende COMO achar
 * rótulo em cada lugar. O que se guarda é só a ESTATÍSTICA de qual nível da escada
 * resolveu, por contexto (pacote do app; para web, o pacote do navegador):
 *
 *     com.android.chrome  labeledBy  9
 *     com.android.chrome  vizinho    1
 *
 * ⛔ Nunca viewId, coordenada, índice de posição nem texto de rótulo alheio: id expira na
 * página seguinte (lição medida no Binspector: âncora é texto, não id) e rótulo é conteúdo
 * da página de outrem, sem por que ficar no aparelho. A whitelist de níveis é a trava.
 * O nível preferido é ATALHO: se não resolver na visita seguinte, a escada roda inteira.
 */
class PathMemory private constructor(
    private val contagens: MutableMap<Pair<String, String>, Int>,
) {

    companion object {
        fun vazio() = PathMemory(mutableMapOf())

        /** Formato: uma linha "contexto \t nivel \t contagem". Linha estranha é ignorada. */
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

        /** "vizinho:12px" → "vizinho": a distância é confiança do momento, não estatística. */
        fun nivelDaOrigem(origem: String): String = origem.substringBefore(':')
    }

    fun registrar(contexto: String, origem: String) {
        if (contexto.isBlank()) return
        val nivel = nivelDaOrigem(origem)
        if (nivel !in Labeler.NIVEIS) return // whitelist: viewId-cru e lixo nunca entram
        contagens.merge(contexto to nivel, 1, Int::plus)
    }

    fun nivelPreferido(contexto: String): String? =
        contagens.entries.filter { it.key.first == contexto }.maxByOrNull { it.value }?.key?.second

    fun serializar(): String =
        contagens.entries.joinToString("\n") { "${it.key.first}\t${it.key.second}\t${it.value}" }
}
