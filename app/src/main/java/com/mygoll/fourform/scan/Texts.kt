package com.mygoll.fourform.scan

/**
 * Homegrown, minimal serialization. Escaped TSV for internal storage (learned entries,
 * receipt) and WRITE-only JSON for the report. No library on purpose: the app is 100%
 * local and nothing here is parsed by a third party.
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

// The old Relatorio object (240) used to live here and wrote out entered VALUES. It left
// in 242: the diagnostic is now exportable via the share button, and value never leaves
// the device. See nucleo/Diagnostics.kt.
