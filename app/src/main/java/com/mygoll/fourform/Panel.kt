package com.mygoll.fourform

import com.mygoll.fourform.scan.Field
import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mygoll.fourform.Ui.ancora
import com.mygoll.fourform.Ui.botaoPrimario
import com.mygoll.fourform.Ui.botaoSecundario
import com.mygoll.fourform.Ui.dp
import com.mygoll.fourform.Ui.itemComPonto
import com.mygoll.fourform.Ui.kicker
import com.mygoll.fourform.scan.Choice
import com.mygoll.fourform.agent.Llm
import com.mygoll.fourform.agent.Session

/**
 * THE RESULT ON SCREEN (brief 242, part B · Anthuan's reversal on 09/09: "it has to show
 * up on screen so you know what it filled... even if it's just a modal"). Appears AFTER
 * the scan, when there's no more active field to cover · that's why it doesn't clash with
 * the 241 guideline, which measured the panel DURING the fill.
 *
 * SOLID surface (the one 241 approved, 11.89:1 contrast), via TYPE_ACCESSIBILITY_OVERLAY:
 * it's the window the accessibility service itself can draw WITHOUT a new permission ·
 * SYSTEM_ALERT_WINDOW is not requested anywhere in this app. The area outside the card is
 * pure transparent (it only catches the tap-outside to close), not a reading surface.
 *
 * Interactive, which was the request: undo per filled item, type the value right there
 * per open item. Closes via button and via tap-outside; never traps the person on the screen.
 *
 * 🎓 VISUAL (09/12): this card is the expansion of the bubble from the design approved in
 * HTML, and speaks the same language as the screens via Ui.kt · which became Context
 * extensions specifically so the AccessibilityService could use them. The grammar: amber
 * dot = DECLARED gap (not an error, it's category 4 of his rule: a question the resume
 * doesn't answer, and the app gets credit for saying so instead of guessing), gold ⌁
 * anchor = provenance, AI card with a gold outline = suggestion, not fact.
 */
class Panel(
    private val service: AccessibilityService,
    private val registros: () -> List<Session.Registro>,
    private val aoDesfazer: (String) -> Boolean,
    private val aoEscrever: (String, String) -> Boolean,
    private val aoFechar: () -> Unit,
    // brief 245: the AI suggestion per field key (null = none), whether it's still
    // querying, and the two outcomes that don't go through the regular EditText
    private val sugestao: (String) -> Llm.Sugestao? = { null },
    private val consultando: (String) -> Boolean = { false },
    private val aoUsar: (String) -> Boolean = { false },
    private val aoDescartar: (String) -> Unit = {},
    // this round's census: how many CHOICE fields the app saw go by that it still can't
    // operate. On screen because measuring it from the dump requires uploading the file
    // and opening it on a computer; the number that changes the decision has to show up
    // where the decision happens.
    private val escolhasVistas: () -> Int = { 0 },
    // this round's choices and clicking on them. ⛔ The app does NOT check them on its own
    // on purpose: "UX/UI Specialist" or "Spontaneous application" is intent, not profile
    // data, and guessing here is exactly the invention that competitors do and the product
    // promises not to do. The app offers and the person decides: it's the "clear and
    // controllable" from the rubric.
    private val escolhasLista: () -> List<Choice> = { emptyList() },
    private val aoMarcar: (String) -> Boolean = { false },
    /**
     * WHAT'S HAPPENING RIGHT NOW, one line per scan, plus the reason it stopped once it
     * has. Requested by him on 09/12 after watching the bubble spin endlessly: "my
     * intention was, when I tapped the bubble, to SEE WHAT'S HAPPENING".
     *
     * 🎓 Why this is worth more than a log file: the dump only helps afterward, on a
     * computer. Whoever has the form open needs the cause RIGHT NOW, and the cause is
     * what decides whether they tap again, scroll by hand, or give up on the screen.
     */
    private val estadoAoVivo: () -> List<String> = { emptyList() },
    /**
     * Run again on WHAT'S LEFT, without starting over. Requested by him on 09/12, after
     * seeing the loop stop midway and having to tap the accessibility button again: the
     * round that failed midway ⛔ cannot cost all the work already done.
     */
    private val aoTentarDeNovo: () -> Unit = {},
) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private var raiz: FrameLayout? = null
    private lateinit var lista: LinearLayout
    private lateinit var titulo: TextView

    // fields where the person tapped "Edit" on the AI suggestion: the card turns into a
    // pre-filled EditText and the write follows the regular path (which learns the correction)
    private val emEdicao = mutableSetOf<String>()

    private fun cor(id: Int): Int = service.resources.getColor(id, null)
    private fun dp(v: Int): Int = service.dp(v)

    fun mostrar() {
        if (raiz != null) return
        val root = FrameLayout(service)
        root.setOnClickListener { fechar() } // tap outside the card closes it

        val cartao = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cor(R.color.superficie_recipiente_alto)) // solid, no alpha
                // SHORT radius scale, his order on 09/12: "almost square, but still
                // rounded". Family 14/10/8/6/4; a large container uses 10. A large radius
                // reads as soft and friendly (a consumer app), a short radius reads as
                // precise, and that's what talks to Cosmic Black and Celestial Gold,
                // which are instrument colors.
                cornerRadii = floatArrayOf(
                    dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat(),
                    0f, 0f, 0f, 0f,
                )
                // outline stroke on top: in the design the card is born from the bubble and
                // separates from the other page's content with a 1px border, not a shadow
                // (an overlay has no elevation)
                setStroke(dp(1), cor(R.color.contorno))
            }
            setPadding(dp(20), dp(16), dp(20), dp(16))
            isClickable = true // swallows the touch so it doesn't leak to root and close
        }

        // header in the design's vocabulary: gold overline saying WHO's speaking
        // ("4Form · on this page") and below it the round's tally as the title
        cartao.addView(service.kicker("4Form · on this page"))
        titulo = TextView(service).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cor(R.color.texto))
            setPadding(0, 0, 0, dp(10))
        }
        cartao.addView(titulo)

        lista = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        val rolo = ScrollView(service).apply { addView(lista) }
        cartao.addView(rolo, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // TRY AGAIN gets the gold: when the loop stopped midway, it IS the screen's action.
        // Close stays secondary on purpose: closing isn't THE action, it's the exit.
        cartao.addView(
            service.botaoPrimario("Try again on what's left") {
                fechar()
                aoTentarDeNovo()
            }
        )
        cartao.addView(service.botaoSecundario("Close") { fechar() })

        // bottom half of the screen: the work is already done, there's no focused field to cover
        val altura = (service.resources.displayMetrics.heightPixels * 0.55f).toInt()
        root.addView(cartao, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, altura, Gravity.BOTTOM))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            0, // focusable window: the "type right there" EditText needs the keyboard
            PixelFormat.TRANSLUCENT,
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        // if the window fails on this device, the demo degrades to a notice · never a crash
        val deu = runCatching { wm.addView(root, lp) }
        if (deu.isFailure) {
            Notices.texto(service, "The panel couldn't open: ${deu.exceptionOrNull()?.message}")
            aoFechar()
            return
        }
        raiz = root
        montar()
    }

    fun visivel(): Boolean = raiz != null

    /** Rebuilds the list when something changes externally (an AI suggestion arrives, for example). */
    fun atualizar() {
        if (raiz != null) montar()
    }

    fun fechar() {
        val r = raiz ?: return
        raiz = null
        runCatching { wm.removeView(r) }
        aoFechar()
    }

    /** Rebuilds the list from the session's current state (called after undo/write). */
    private fun montar() {
        val regs = registros()
        val preenchidos = regs.filter { it.acao == "preencheu" }
        val abertos = regs.filter { it.acao == "aberto" }
        val escolhas = escolhasVistas()
        titulo.text = buildString {
            append("Filled ${preenchidos.size} · ${abertos.size} open")
            if (escolhas > 0) append(" · $escolhas choice field(s) (still can't operate)")
        }
        lista.removeAllViews()

        // THE LOOP'S STATE comes FIRST when the round hasn't ended yet: at that moment
        // his question isn't "what did you fill in", it's "what are you doing".
        val passos = estadoAoVivo()
        if (passos.isNotEmpty()) {
            lista.addView(secao("What's happening"))
            for (linhaPasso in passos) {
                lista.addView(texto(linhaPasso, R.color.texto_secundario))
            }
            lista.addView(espaco(10))
        }

        // ⛔ WHAT WAS FILLED DOES NOT GO IN THE LIST (his rule, 09/10, testing on
        // Greenhouse): "no need for that caution; if I want to undo it I'll just go there
        // and write in the field". Confirming already-done work is pure friction · in his
        // test the panel showed 9 items when only 2 needed a decision.
        //
        // 🎓 Why the count STAYS in the title: he needs to know HOW MANY were touched
        // (that's what gives confidence the app acted), without having to approve one by
        // one. And undo doesn't disappear from the product: the correction is made right
        // in the field, and the app learns from it via TYPE_VIEW_TEXT_CHANGED · the same
        // mechanism as the 3rd act.
        if (abertos.isNotEmpty()) lista.addView(secao("Left for you"))
        for (r in abertos) {
            // AMBER dot, not ember: a declared gap is a product virtue, not a failure.
            // The reason comes in as a detail so the sentence explains why at a glance.
            lista.addView(
                service.itemComPonto(r.rotulo ?: "unnamed field", r.motivo ?: "", R.color.aviso)
            )
            val chave = r.campo.chave
            val sug = sugestao(chave)
            if (sug != null && chave !in emEdicao) {
                lista.addView(cartaoIa(chave, sug))
                continue
            }
            if (consultando(chave)) {
                lista.addView(texto("asking the AI…", R.color.texto_secundario))
            }
            val caixa = campoTexto("type the value and tap Write").apply {
                // "Edit" from the suggestion: starts from the AI's text; whatever comes out of here is the person's
                if (chave in emEdicao && sug != null) setText(sug.resposta)
            }
            lista.addView(caixa)
            // gold and COMPACT button: it's a per-item action, not the whole screen's
            // action · gold as a full-width bar here would turn into a wall of gold for
            // every open field
            lista.addView(linhaDeBotoes(botaoCompacto("Write in field", primario = true) {
                val v = caixa.text.toString()
                if (v.isNotBlank()) {
                    if (aoEscrever(chave, v)) {
                        emEdicao.remove(chave)
                        montar()
                    } else {
                        caixa.error = "couldn't write: did the field leave the screen?"
                    }
                }
            }))
        }

        // ⛔ A CHOICE DOES NOT BECOME A BUTTON IN THE PANEL. His rule, 09/12, testing on
        // the bus: "if it's not going to check it, no need to show it on screen, that
        // gets in the way. If it's going to check it, it checks it and the game moves on;
        // if not, it moves to the next field". A list of buttons asking for a decision is
        // the friction the panel exists to remove · the same reason filled fields don't
        // go in the list either. What the app didn't resolve on its own becomes ONE
        // summary line, with no dangling action.
        val naoMarcadas = escolhasLista().count { !it.marcada }
        if (naoMarcadas > 0) {
            lista.addView(
                service.itemComPonto(
                    "Left $naoMarcadas choice(s) blank",
                    "It's a decision for the moment, not for the profile · marking it for you would be inventing.",
                    R.color.aviso,
                )
            )
        }
    }

    /**
     * The AI response card (brief 245): visually DISTINCT from the rest because it's a
     * SUGGESTION, not a fact · background one step darker and outline in the primary
     * color, which throughout the app marks "the agent is speaking here". Shows the
     * response, the ⌁ ANCHOR of where it came from (the "I don't make things up about
     * you" promise is proven with provenance, not marketing copy), and the confidence ·
     * plus three outcomes: Use / Edit / Discard. ⛔ Nothing here writes to the field
     * without the person's tap.
     */
    private fun cartaoIa(chave: String, sug: Llm.Sugestao): LinearLayout = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(cor(R.color.superficie_recipiente))
            setStroke(dp(1), cor(R.color.primaria))
            cornerRadius = dp(8).toFloat() // card, step 8 of the short radius scale
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6); bottomMargin = dp(4) }
        // confidence arrives in Portuguese (JSON contract with the LLM, see Llm.kt):
        // translated only for display, without touching the data contract or the tests
        // that verify it.
        val confiancaEmIngles = when (sug.confianca) {
            "alta" -> "high"
            "media" -> "medium"
            "baixa" -> "low"
            else -> sug.confianca
        }
        addView(service.kicker("AI suggestion · confidence $confiancaEmIngles"))
        addView(TextView(service).apply {
            text = "“${sug.resposta}”"
            textSize = 15f
            setTextColor(cor(R.color.texto))
        })
        // the anchor with the gold ⌁ glyph: the SAME provenance piece as the other screens
        addView(service.ancora(sug.ancora))
        addView(linhaDeBotoes(
            botaoCompacto("Use", primario = true) {
                if (aoUsar(chave)) montar()
                else Notices.texto(service, "couldn't write: did the field leave the screen?")
            },
            botaoCompacto("Edit", primario = false) {
                emEdicao.add(chave)
                montar()
            },
            botaoCompacto("Discard", primario = false) {
                aoDescartar(chave)
                montar()
            },
        ))
    }

    // ── local pieces: only what the overlay needs and the screens don't have ────────────

    /** List section header: the same gold overline from the design, with breathing room. */
    private fun secao(rotulo: String): TextView = service.kicker(rotulo).apply {
        setPadding(0, dp(6), 0, dp(4))
    }

    private fun texto(t: String, corId: Int): TextView = TextView(service).apply {
        text = t
        textSize = 13f
        setTextColor(cor(corId))
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun espaco(altura: Int) = TextView(service).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(altura))
    }

    /**
     * System text field: recessed background (one step darker than the card, as in the
     * design), outline stroke, and radius 6, the field step in the 14/10/8/6/4 scale.
     * 🎓 The standard Android EditText only shows the bottom line, which disappears on a
     * dark surface; the filled background is what makes the field feel like a PLACE to write.
     */
    private fun campoTexto(dica: String): EditText = EditText(service).apply {
        hint = dica
        textSize = 14f
        setTextColor(cor(R.color.texto))
        setHintTextColor(cor(R.color.texto_secundario))
        background = GradientDrawable().apply {
            setColor(cor(R.color.superficie_recipiente))
            setStroke(dp(1), cor(R.color.contorno))
            cornerRadius = dp(6).toFloat()
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) }
    }

    /**
     * Compact button for a PER-ITEM action (Use, Edit, Write to field): same language as
     * the buttons in Ui.kt (solid gold = acts, outline = supporting, radius 6) but at line
     * size, because in a panel with several open fields each action is local, not the
     * whole screen's.
     */
    private fun botaoCompacto(t: String, primario: Boolean, aoTocar: () -> Unit): Button =
        Button(service).apply {
            text = t
            isAllCaps = false
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            stateListAnimator = null // no elevation shadow: flat overlay, as in the design
            if (primario) {
                setTextColor(cor(R.color.sobre_primaria))
                background = GradientDrawable().apply {
                    setColor(cor(R.color.primaria))
                    cornerRadius = dp(6).toFloat()
                }
            } else {
                setTextColor(cor(R.color.texto))
                background = GradientDrawable().apply {
                    setColor(cor(R.color.superficie_recipiente_alto))
                    setStroke(dp(1), cor(R.color.contorno))
                    cornerRadius = dp(6).toFloat()
                }
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { aoTocar() }
        }

    /** Horizontal row of compact buttons, with breathing room between them. */
    private fun linhaDeBotoes(vararg botoes: Button): LinearLayout =
        LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
            for (b in botoes) {
                addView(b, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { rightMargin = dp(8) })
            }
        }
}
