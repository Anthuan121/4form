package com.mygoll.fourform.agent

import com.mygoll.fourform.scan.Json
import com.mygoll.fourform.scan.Labeler
import com.mygoll.fourform.scan.Field
import com.mygoll.fourform.scan.Choice

/**
 * O relatório estruturado de UMA varredura (brief 242, parte C). Substitui o Relatorio
 * do 240 porque este arquivo SAI do aparelho pelo botão de compartilhar, e a régua muda:
 * rótulo e decisão SIM; o VALOR escrito num campo e qualquer coisa de campo de senha,
 * NUNCA. Se ele carregasse dado pessoal, o produto que promete não vazar teria vazado.
 *
 * Por campo, além da decisão: qual nível da escada resolveu o rótulo e, quando nenhum
 * resolveu, o que HAVIA disponível no nó — é o que transforma a próxima volta de
 * depuração em leitura de arquivo em vez de adivinhação por print.
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
        // Instrumentação do laço (fechador da rodada 10/09): uma linha por varredura com o
        // contêiner rolável ESCOLHIDO e a espera usada. Sem isso, "rolei e não apareceu
        // campo novo" não distingue "o formulário acabou" de "escolhi o contêiner errado"
        // ou "a árvore não assentou na espera". Default vazio: não quebra chamador velho.
        laco: List<String> = emptyList(),
        // Episódios de LLM por chave de campo (brief 245): desfecho, confiança e latência.
        // A RESPOSTA e a ÂNCORA nunca entram — o tipo nem as carrega; este arquivo sai do
        // aparelho e a régua do 242 (zero conteúdo pessoal) continua valendo.
        llm: Map<String, Llm.LlmDiagnostico> = emptyMap(),
        // Censo dos campos de ESCOLHA que a varredura via passar e não coletava (só
        // isEditable entrava). É a medição que responde "o app não preencheu, ou não viu?".
        // Rótulo e tipo apenas: o ESTADO marcado/desmarcado é dado pessoal e não sai daqui.
        escolhas: List<Choice> = emptyList(),
        /** Placar do ACTION_CLICK nas escolhas: separa "a árvore recusou" de "o nó sumiu". */
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
                // origem: "perfil" | "ia" | "nenhum" (a decisão ainda não rodou, ou nada bateu).
                // Sem isto, zero cliques tinha três causas possíveis e nenhuma forma de
                // distinguir de fora: sem rótulo, rótulo sem match no perfil, ou IA descartada.
                "{\"tipo\": ${Json.str(e.tipo)}, \"rotulo\": ${Json.str(e.rotulo ?: "?")}, " +
                    "\"viewId\": ${Json.str(e.viewId ?: "")}, \"webview\": ${e.dentroDeWebView}, " +
                    "\"origem\": ${Json.str(e.origemDecisao ?: "nenhum")}}"
            }
        )
        sb.append("],\n")
        sb.append("  \"voltasDeRolagem\": $voltasDeRolagem,\n")
        sb.append("  \"paradaPor\": ${Json.str(paradaPor)},\n")
        // só a CONTAGEM: o aprendido carrega valor digitado pelo usuário, e valor não sai
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

    /** O que havia no nó para a escada tentar — o insumo da depuração do dia 12/09. */
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
