package com.mygoll.fourform.agent

import com.mygoll.fourform.scan.Json
import com.mygoll.fourform.scan.Labeler
import com.mygoll.fourform.scan.Field
import com.mygoll.fourform.scan.Choice

/**
 * The structured report of ONE scan (brief 242, part C). Replaces the 240 Relatorio
 * because this file LEAVES the device via the share button, and the rule changes: label
 * and decision YES; the VALUE written to a field and anything from a password field,
 * NEVER. If it carried personal data, the product that promises not to leak would have leaked.
 *
 * Per field, besides the decision: which level of the ladder resolved the label and,
 * when none did, what WAS available on the node. This is what turns the next round of
 * debugging into reading a file instead of guessing from a screenshot.
 */
object Diagnostics {

    fun json(
        build: String,
        pacote: String?,
        quando: String,
        totalNos: Int,
        voltasDeRolagem: Int,
        paradaPor: String,
        registros: List<Session.Registro>,
        aprendidosNaSessao: Int,
        // Loop instrumentation (round closer, 09/10): one line per scan with the CHOSEN
        // scrollable container and the wait used. Without this, "scrolled and no new
        // field showed up" can't tell apart "the form ended" from "picked the wrong
        // container" or "the tree didn't settle within the wait". Empty default: doesn't
        // break the old caller.
        laco: List<String> = emptyList(),
        // LLM episodes per field key (brief 245): outcome, confidence, and latency. The
        // RESPONSE and the ANCHOR never go in. The type doesn't even carry them; this
        // file leaves the device and the 242 rule (zero personal content) still applies.
        llm: Map<String, Llm.LlmDiagnostico> = emptyMap(),
        // Census of CHOICE fields the scan saw go by and didn't collect (only isEditable
        // used to go in). It's the measurement that answers "did the app not fill it, or
        // not see it?". Label and type only: the checked/unchecked STATE is personal data
        // and doesn't leave here.
        escolhas: List<Choice> = emptyList(),
        /** ACTION_CLICK tally on choices: separates "the tree refused" from "the node disappeared". */
        cliques: String = "",
    ): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"build\": ${Json.str(build)},\n")
        sb.append("  \"quando\": ${Json.str(quando)},\n")
        sb.append("  \"pacote\": ${Json.str(pacote ?: "?")},\n")
        sb.append("  \"totalNos\": $totalNos,\n")
        sb.append("  \"camposDeTexto\": ${registros.size},\n")
        sb.append("  \"camposDeEscolha\": ${escolhas.size},\n")
        val totalRespondivel = registros.size + escolhas.size
        val cegueira = if (totalRespondivel > 0) escolhas.size * 100 / totalRespondivel else 0
        sb.append("  \"cegueiraPct\": $cegueira,\n")
        sb.append("  \"cliques\": ${Json.str(cliques)},\n")
        sb.append("  \"escolhasClicaveis\": ${escolhas.count { it.clicavel }},\n")
        sb.append("  \"escolhas\": [")
        sb.append(
            escolhas.joinToString(", ") { e ->
                "{\"tipo\": ${Json.str(e.tipo)}, \"rotulo\": ${Json.str(e.rotulo ?: "?")}, " +
                    "\"viewId\": ${Json.str(e.viewId ?: "")}, \"webview\": ${e.dentroDeWebView}}"
            }
        )
        sb.append("],\n")
        sb.append("  \"voltasDeRolagem\": $voltasDeRolagem,\n")
        sb.append("  \"paradaPor\": ${Json.str(paradaPor)},\n")
        // just the COUNT: a learned entry carries a value the user typed, and value doesn't leave
        sb.append("  \"aprendidosNaSessao\": $aprendidosNaSessao,\n")
        sb.append("  \"laco\": [")
        sb.append(laco.joinToString(", ") { Json.str(it) })
        sb.append("],\n")
        sb.append("  \"campos\": [\n")
        registros.forEachIndexed { i, r ->
            sb.append("    {")
            sb.append("\"rotulo\": ${Json.str(r.rotulo ?: "?")}, ")
            sb.append("\"nivel\": ${Json.str(r.origemRotulo ?: "nenhum")}, ")
            sb.append("\"viewId\": ${Json.str(r.campo.viewId ?: "")}, ")
            sb.append("\"webview\": ${r.campo.dentroDeWebView}, ")
            sb.append("\"senha\": ${r.campo.senha}, ")
            sb.append("\"acao\": ${Json.str(r.acao)}, ")
            if (r.acao == "preencheu") {
                sb.append("\"fonte\": ${Json.str(r.fonte ?: "")}, ")
            } else {
                sb.append("\"motivo\": ${Json.str(r.motivo ?: "")}, ")
            }
            sb.append("\"sinais\": ${sinais(r.campo)}")
            llm[r.campo.chave]?.let { l ->
                sb.append(", \"llm\": {")
                sb.append("\"desfecho\": ${Json.str(l.desfecho)}, ")
                sb.append("\"confianca\": ${l.confianca?.let { c -> Json.str(c) } ?: "null"}, ")
                sb.append("\"latenciaMs\": ${l.latenciaMs ?: "null"}")
                sb.append("}")
            }
            sb.append("}").append(if (i < registros.size - 1) ",\n" else "\n")
        }
        sb.append("  ]\n}\n")
        return sb.toString()
    }

    /** What was available on the node for the ladder to try. The input for 09/12's debugging. */
    private fun sinais(c: Field): String {
        val labeledBy = when {
            c.labeledBy != null -> "com texto"
            c.labeledByPresente -> "presente mas sem texto"
            else -> "não"
        }
        return "{" +
            "\"labeledBy\": ${Json.str(labeledBy)}, " +
            "\"hint\": ${c.hint != null}, " +
            "\"descricao\": ${c.descricao != null}, " +
            "\"irmao\": ${c.rotuloIrmao != null}, " +
            "\"vizinhoPx\": ${c.rotuloVizinho?.distanciaPx ?: "null"}, " +
            "\"viewIdCru\": ${c.viewId?.let { Labeler.viewIdCru(it) } ?: false}" +
            "}"
    }
}
