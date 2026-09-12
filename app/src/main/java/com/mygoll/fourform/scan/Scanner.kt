package com.mygoll.fourform.scan

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.mygoll.fourform.scan.Box
import com.mygoll.fourform.scan.Matcher
import com.mygoll.fourform.scan.Field
import com.mygoll.fourform.scan.Choice
import com.mygoll.fourform.scan.Labeler

/**
 * Walks the accessibility tree ONCE and returns: the editable fields (as Field, pure and
 * testable), the visible texts (candidates for a neighboring label), each field's real
 * node (for ACTION_SET_TEXT), and the SCROLLABLE node with the largest area (for the
 * Engine's loop to request ACTION_SCROLL_FORWARD, the tree's own action, not a coordinate
 * gesture, on purpose: a gesture breaks on a screen of a different size). Visible nodes
 * only: what the user can't see, the app doesn't touch.
 */
object Scanner {

    // ponytail: node cap against a pathological tree (an infinite web page); if it's
    // exceeded, the field beyond the cap simply doesn't join the round.
    private const val MAX_NOS = 1500

    class Saida(
        val campos: List<Field>,
        val nos: Map<String, AccessibilityNodeInfo>,
        val rolavel: AccessibilityNodeInfo?,
        val totalNos: Int,
        /** Census of CHOICE fields: count and label only, no action (see Choice). */
        val escolhas: List<Choice> = emptyList(),
        /** Each choice's real node, for the ACTION_CLICK the person triggers from the panel. */
        val nosEscolha: Map<String, AccessibilityNodeInfo> = emptyMap(),
        /**
         * This scan's fingerprint: how many nodes, and what was seen. Feeds the Engine's
         * stopping rule (did the screen stop moving?). Structural identity only, no typed value goes in here.
         */
        val assinatura: String = "",
    )

    /**
     * Classifies a NON-editable node that's still an answerable field.
     * Sources (brief 247, reading Chromium's source): checkbox/radio arrive with
     * isCheckable propagated from Blink; a closed <select> arrives as a Spinner and is
     * always a leaf (the <option>s never become children); <input type=date> arrives
     * with inputType DATETIME. Returns null for the rest, which is regular page text.
     */
    private fun tipoDeEscolha(no: AccessibilityNodeInfo): String? {
        val classe = no.className?.toString() ?: ""
        return when {
            no.isCheckable && classe.contains("RadioButton") -> "radio"
            no.isCheckable && classe.contains("Switch") -> "switch"
            no.isCheckable -> "checkbox"
            classe.contains("Spinner") -> "select"
            no.isClickable && (no.inputType and INPUT_TYPE_DATETIME) != 0 -> "data"
            else -> null
        }
    }

    // android.text.InputType.TYPE_CLASS_DATETIME
    private const val INPUT_TYPE_DATETIME = 0x00000004

    fun varrer(raiz: AccessibilityNodeInfo): Saida {
        val brutos = mutableListOf<Field>()
        val escolhasBrutas = mutableListOf<Choice>()
        val nosEscolha = mutableMapOf<String, AccessibilityNodeInfo>()
        val nos = mutableMapOf<String, AccessibilityNodeInfo>()
        val textos = mutableListOf<Pair<String, Box>>()
        var rolavel: AccessibilityNodeInfo? = null
        var areaRolavel = 0L
        var visitados = 0

        fun caixaDe(no: AccessibilityNodeInfo): Box {
            val r = Rect()
            no.getBoundsInScreen(r)
            return Box(r.left, r.top, r.right, r.bottom)
        }

        fun anda(no: AccessibilityNodeInfo?, dentroWeb: Boolean) {
            if (no == null || visitados >= MAX_NOS) return
            visitados++
            val ehWeb = dentroWeb || no.className?.contains("WebView") == true
            if (no.isVisibleToUser) {
                // the scrollable node with the LARGEST area tends to be the page's main
                // container, not some small carousel in the middle of it
                if (no.isScrollable) {
                    val c = caixaDe(no)
                    val area = (c.dir - c.esq).toLong() * (c.baixo - c.topo).toLong()
                    if (area > areaRolavel) {
                        areaRolavel = area
                        rolavel = no
                    }
                }
                if (no.isEditable) {
                    val senha = no.isPassword
                    val hint = no.hintText?.toString()?.takeIf { it.isNotBlank() }
                    val descricao = no.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    val viewId = no.viewIdResourceName
                    val caixa = caixaDe(no)
                    // password: the text is NEVER read; a hint shown as text doesn't count either
                    val texto = when {
                        senha -> null
                        no.isShowingHintText -> null
                        else -> no.text?.toString()
                    }
                    // labeledBy: the label the page's AUTHOR declared for this field
                    val noRotulo = no.labeledBy
                    val labeledBy = noRotulo?.let {
                        it.text?.toString()?.takeIf { t -> t.isNotBlank() }
                            ?: it.contentDescription?.toString()?.takeIf { t -> t.isNotBlank() }
                    }
                    val campo = Field(
                        chave = chaveEstavel(viewId, hint, descricao, caixa),
                        inputType = no.inputType,
                        hint = hint,
                        descricao = descricao,
                        viewId = viewId,
                        senha = senha,
                        caixa = caixa,
                        textoAtual = texto,
                        dentroDeWebView = ehWeb,
                        labeledBy = labeledBy?.trim(),
                        labeledByPresente = noRotulo != null,
                        rotuloIrmao = if (ehWeb) rotuloIrmao(no) else null,
                    )
                    brutos.add(campo)
                    nos[campo.chave] = no
                } else {
                    val t = no.text?.toString()
                    // keeps feeding the label candidates BEFORE classifying: a checkbox
                    // usually carries its own text, and it already served as a label for
                    // its neighbors. Touching this would be a silent regression.
                    if (!t.isNullOrBlank()) textos.add(t to caixaDe(no))
                    tipoDeEscolha(no)?.let { tipo ->
                        val e = Choice(
                            tipo = tipo,
                            rotulo = t?.takeIf { it.isNotBlank() }
                                ?: no.contentDescription?.toString()?.takeIf { it.isNotBlank() },
                            viewId = no.viewIdResourceName,
                            caixa = caixaDe(no),
                            dentroDeWebView = ehWeb,
                            // ACTION_CLICK requires isClickable; a radio inside a <label>
                            // sometimes arrives not clickable and the parent is what responds.
                            clicavel = no.isClickable || no.parent?.isClickable == true,
                            // already checked (by the person or by the site's default)
                            // leaves the loop with no action: clicking here would UNCHECK
                            // what they chose.
                            marcada = no.isChecked,
                        )
                        escolhasBrutas.add(e)
                        nosEscolha[e.chave] = if (no.isClickable) no else (no.parent ?: no)
                    }
                }
            }
            for (i in 0 until no.childCount) anda(no.getChild(i), ehWeb)
        }

        anda(raiz, false)
        val hintsRepetidos = brutos.mapNotNull { it.hint?.trim() }
            .groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys
        val campos = brutos.map {
            it.copy(
                rotuloVizinho = Labeler.vizinhoMaisProximo(it.caixa, textos),
                hintRepetidoNaTela = it.hint?.trim() in hintsRepetidos,
            )
        }
        // a choice with no text of its own inherits the geometric neighbor, same ladder as the editables
        val rotulosDeOpcao = escolhasBrutas.mapNotNull { it.rotulo?.let(Matcher::normalizar) }.toSet()
        val escolhas = escolhasBrutas.map {
            val comRotulo =
                if (it.rotulo != null) it
                else it.copy(rotulo = Labeler.vizinhoMaisProximo(it.caixa, textos)?.texto)
            comRotulo.copy(
                pergunta = Labeler.perguntaAcima(it.caixa, textos, rotulosDeOpcao),
            )
        }
        // the fingerprint includes POSITION (caixa.topo) on purpose: scrolling half a
        // screen keeps the same fields in the tree, just higher up. Without position,
        // "scrolled half a screen" would read as "nothing changed" and the loop would die
        // before the end of the form.
        val assinatura = buildString {
            append(visitados).append('#')
            campos.forEach { append(it.chave).append(':').append(it.caixa.topo).append(',') }
            append('#')
            escolhas.forEach { append(it.chave).append(':').append(it.caixa.topo).append(',') }
        }
        return Saida(campos, nos, rolavel, visitados, escolhas, nosEscolha, assinatura)
    }

    /**
     * STRUCTURAL label in WebView: the <label> usually becomes a sibling node of the
     * <input> in the same parent. Walks the preceding siblings, from closest to
     * farthest, and grabs the first with text. No geometry, so it survives tight layouts.
     */
    private fun rotuloIrmao(no: AccessibilityNodeInfo): String? {
        val pai = no.parent ?: return null
        var indice = -1
        for (i in 0 until pai.childCount) {
            if (pai.getChild(i) == no) {
                indice = i
                break
            }
        }
        if (indice <= 0) return null
        for (i in indice - 1 downTo 0) {
            val irmao = pai.getChild(i) ?: continue
            if (irmao.isEditable) continue // another field isn't a label
            val t = irmao.text?.toString()?.takeIf { it.isNotBlank() }
                ?: irmao.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            if (t != null) return t.trim()
        }
        return null
    }

    /**
     * A field's stable identity between the scan and the text events that arrive later.
     * viewId > hint > description > box; the box is the worst signal because it changes
     * when the keyboard opens and the screen scrolls, which is why the Engine has the 2nd
     * defense of dedupe by label+text for a field seen again after scrolling.
     */
    fun chaveEstavel(viewId: String?, hint: String?, descricao: String?, caixa: Box): String =
        viewId?.takeIf { it.isNotBlank() }
            ?: hint?.takeIf { it.isNotBlank() }?.let { "hint:$it" }
            ?: descricao?.takeIf { it.isNotBlank() }?.let { "desc:$it" }
            ?: "caixa:${caixa.esq},${caixa.topo},${caixa.dir},${caixa.baixo}"
}
