package com.mygoll.fourform.scan

/**
 * Serialização caseira e mínima. TSV com escape para o armazenamento interno
 * (aprendidos, recibo) e JSON só de ESCRITA para o relatório. Sem biblioteca de
 * propósito: o app é 100% local e nada aqui é parseado por terceiros.
 */
object Tsv {
    fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    fun des(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> sb.append('\t')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}

object Json {
    fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}

// O antigo objeto Relatorio (240) morava aqui e gravava VALORES escritos. Saiu no 242:
// o diagnóstico agora é exportável pelo botão de compartilhar, e valor não sai do
// aparelho — ver nucleo/Diagnostics.kt.
