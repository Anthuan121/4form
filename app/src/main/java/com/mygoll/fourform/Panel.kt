package com.mygoll.fourform

import com.mygoll.fourform.scan.Field
import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
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
 * O RESULTADO NA TELA (brief 242, parte B · reversão do Anthuan em 09/09: "tem que
 * aparecer na tela pra saber o que ele preencheu... nem que seja um modal"). Aparece
 * DEPOIS da varredura, quando não há mais campo ativo para tapar · por isso não briga
 * com a régua do 241, que mediu o painel DURANTE o preenchimento.
 *
 * Superfície SÓLIDA (a que o 241 aprovou, contraste 11,89:1), via TYPE_ACCESSIBILITY_OVERLAY:
 * é a janela que o próprio serviço de acessibilidade pode desenhar SEM permissão nova ·
 * SYSTEM_ALERT_WINDOW não é pedido em lugar nenhum deste app. A área fora do cartão é
 * transparente pura (só pega o toque-fora para fechar), não é superfície de leitura.
 *
 * Interativo, que é o pedido: desfazer por item preenchido, digitar o valor ali mesmo
 * por item aberto. Fecha por botão e por toque fora; nunca prende a pessoa na tela.
 *
 * 🎓 VISUAL (12/09): este cartão é a expansão da bolha do desenho aprovado em HTML, e fala
 * a mesma língua das telas via Ui.kt · que virou extensions de Context justamente para o
 * AccessibilityService poder usá-las. A gramática: ponto âmbar = lacuna DECLARADA (não é
 * erro, é a categoria 4 da régua dele: pergunta que o currículo não responde, e o app tem
 * o mérito de avisar em vez de chutar), âncora ⌁ dourada = procedência, cartão da IA com
 * contorno dourado = sugestão, não fato.
 */
class Panel(
    private val service: AccessibilityService,
    private val registros: () -> List<Session.Registro>,
    private val aoDesfazer: (String) -> Boolean,
    private val aoFechar: () -> Unit,
    // brief 245: the AI suggestion per field key (null = none) and whether it's still
    // asking. brief 255: the panel stopped accepting typed input (item 2), so the only
    // action per suggestion is USE (item 3) · Edit/Discard left together with the typing.
    private val sugestao: (String) -> Llm.Sugestao? = { null },
    private val consultando: (String) -> Boolean = { false },
    private val aoUsar: (String) -> Boolean = { false },
    // censo desta rodada: quantos campos de ESCOLHA o app viu passar e ainda não opera.
    // Na tela porque medir no dump exige subir o arquivo e abrir no computador; o número
    // que muda decisão tem que aparecer onde a decisão acontece.
    private val escolhasVistas: () -> Int = { 0 },
    // as escolhas desta rodada e o clique nelas. ⛔ O app NÃO marca sozinho de propósito:
    // "Especialista UX/UI" ou "Candidatura espontânea" é intenção, não dado de perfil, e
    // chutar aqui é exatamente a invenção que os concorrentes fazem e o produto promete não
    // fazer. O app oferece e a pessoa decide: é o "clear and controllable" da rubrica.
    private val escolhasLista: () -> List<Choice> = { emptyList() },
    private val aoMarcar: (String) -> Boolean = { false },
    /**
     * brief 255, fixing 245: the step-by-step scan log ("scan 1: scrollable ...") had
     * become developer noise on the user's screen · he asked to SEE what was happening
     * and the app handed him code. Collection keeps going to the diagnostics file
     * (FourFormService still writes `laco` there); only the EXCEPTION he approved stays
     * here: when the round ended in FAILURE (watchdog fired, screen stopped scrolling),
     * one plain-language line, no jargon, no node number. `null` when there was no
     * failure · most rounds show nothing here.
     */
    private val linhaDeFalha: () -> String? = { null },
    /**
     * Rodar de novo NO QUE FALTOU, sem recomeçar. Pedido dele em 12/09, depois de ver o laço
     * parar no meio e ter que apertar o botão de acessibilidade outra vez: a rodada que
     * falhou no meio ⛔ não pode custar todo o trabalho já feito.
     */
    private val aoTentarDeNovo: () -> Unit = {},
) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private var raiz: FrameLayout? = null
    private lateinit var lista: LinearLayout
    private lateinit var titulo: TextView

    private fun cor(id: Int): Int = service.resources.getColor(id, null)
    private fun dp(v: Int): Int = service.dp(v)

    fun mostrar() {
        if (raiz != null) return
        val root = FrameLayout(service)
        root.setOnClickListener { fechar() } // toque fora do cartão fecha

        val cartao = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cor(R.color.superficie_recipiente_alto)) // sólida, sem alfa
                // Escala de raio CURTO, ordem dele em 12/09: "quase quadrada, mas ainda
                // arredondada". Família 14/10/8/6/4; contêiner grande usa 10. Raio grande lê
                // como macio e amigável (app de consumo), raio curto lê como preciso, e é o
                // que conversa com Cosmic Black e Celestial Gold, que são cor de instrumento.
                cornerRadii = floatArrayOf(
                    dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat(), dp(10).toFloat(),
                    0f, 0f, 0f, 0f,
                )
                // fio de contorno em cima: no desenho o cartão nasce da bolha e se separa da
                // página alheia por uma borda de 1px, não por sombra (overlay não tem elevação)
                setStroke(dp(1), cor(R.color.contorno))
            }
            setPadding(dp(20), dp(16), dp(20), dp(16))
            isClickable = true // engole o toque para não vazar pro root e fechar
        }

        // cabeçalho no vocabulário do desenho: sobrescrita dourada dizendo QUEM fala
        // ("Preenche · nesta página") e embaixo o placar da rodada como título
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

        // TENTAR DE NOVO leva o dourado: quando o laço parou no meio, ela É a ação da tela.
        // Fechar fica em secundário de propósito: fechar não é A ação, é a saída.
        cartao.addView(
            service.botaoPrimario("Try again on what's left") {
                fechar()
                aoTentarDeNovo()
            }
        )
        cartao.addView(service.botaoSecundario("Close") { fechar() })

        // metade de baixo da tela: o trabalho já terminou, não há campo focado para cobrir
        val altura = (service.resources.displayMetrics.heightPixels * 0.55f).toInt()
        root.addView(cartao, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, altura, Gravity.BOTTOM))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            0, // focusable window: keeps the overlay's default behavior across reopenings
            PixelFormat.TRANSLUCENT,
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        // se a janela falhar neste aparelho, a demo degrada para aviso · nunca para crash
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

    /** Recompõe a lista quando algo muda por fora (sugestão da IA chegou, por exemplo). */
    fun atualizar() {
        if (raiz != null) montar()
    }

    fun fechar() {
        val r = raiz ?: return
        raiz = null
        runCatching { wm.removeView(r) }
        aoFechar()
    }

    /** Recompõe a lista do estado atual da sessão (chamado após desfazer/escrever). */
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

        // brief 255: only the FAILURE line comes first, plain, no jargon. The
        // step-by-step log that used to live here is gone for good (still in the diagnostics file).
        linhaDeFalha()?.let { falha ->
            lista.addView(texto(falha, R.color.erro))
            lista.addView(espaco(10))
        }

        // ⛔ O QUE FOI PREENCHIDO NÃO ENTRA NA LISTA (régua dele, 10/09, testando no
        // Greenhouse): "não precisa ter essa cautela; se eu quiser desfazer eu vou lá e
        // escrevo no campo". Confirmar trabalho já feito é atrito puro · no teste dele o
        // painel trazia 9 itens quando só 2 precisavam de decisão.
        //
        // 🎓 Por que a contagem FICA no título: ele precisa saber QUANTOS foram tocados
        // (é o que dá confiança de que o app agiu), sem ter que aprovar um por um. E o
        // desfazer não some do produto: a correção é feita no próprio campo, e o app
        // aprende dela pelo TYPE_VIEW_TEXT_CHANGED · o mesmo mecanismo do 3º ato.
        // brief 255: exactly three states per open field, never typing in the panel.
        // "no proposal" is already said by the line below (label + reason) · no button,
        // because not typing here is what he asked: "if I'm going to type, I type in the form".
        if (abertos.isNotEmpty()) lista.addView(secao("Left for you"))
        for (r in abertos) {
            // brief 260: reserved fields (negotiation, sensitive identity, legal
            // declaration) get the CONTORNO dot, never âmbar. Âmbar means "the résumé
            // doesn't answer this" (category 4, a gap); reserved means "I won't answer
            // this even when I could" (a boundary, not a gap) — same visual would tell
            // the person the app failed to find something it never tried to find.
            val corDoPonto = if (r.reservado) R.color.contorno else R.color.aviso
            lista.addView(
                service.itemComPonto(r.rotulo ?: "unnamed field", r.motivo ?: "", corDoPonto)
            )
            val chave = r.campo.chave
            // Reserved beats the AI too: no suggestion card and no "asking the AI" line
            // render for a reserved field, whatever the AI path upstream returned. And there
            // is no text box here by design (brief 255): if you are going to type, you type
            // in the form behind this panel, not in a parallel one.
            val sug = if (r.reservado) null else sugestao(chave)
            when {
                sug != null -> lista.addView(cartaoIa(chave, sug))
                !r.reservado && consultando(chave) ->
                    lista.addView(texto("asking the AI...", R.color.texto_secundario))
                // else: no proposal. The itemComPonto line above already says which field
                // it is and why it is blank · information, not action.
            }
        }

        // ⛔ ESCOLHA NÃO VIRA BOTÃO NO PAINEL. Régua dele, 12/09, testando no ônibus: "se
        // ele não vai marcar, não precisa mostrar na tela, isso trava. Se vai marcar,
        // marcou e o jogo segue; se não, segue pro próximo campo". Uma lista de botões
        // pedindo decisão é o atrito que o painel existe pra remover · o mesmo motivo pelo
        // qual o preenchido já não entra na lista. O que o app não resolveu sozinho vira
        // UMA linha de resumo, sem ação pendurada.
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
     * The AI answer card (brief 245, buttons cut down to just one in 255): visually
     * DISTINCT from the rest because it's a SUGGESTION, not a fact · background one step
     * down and outline in the primary color, which throughout the app marks "the agent is
     * speaking here". Shows the answer, the ANCHOR ⌁ it came from (the promise "it does
     * not invent things about you" is proven by provenance, not marketing copy) and the
     * confidence.
     *
     * ⛔ Edit and Discard are gone (brief 255): "typing inside the modal doesn't make
     * sense, if I'm going to type I type in the form". USE is the only action on the
     * card, and on the whole panel besides closing and "try again" · it writes to the
     * form field and the card leaves the list.
     */
    private fun cartaoIa(chave: String, sug: Llm.Sugestao): LinearLayout = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(cor(R.color.superficie_recipiente))
            setStroke(dp(1), cor(R.color.primaria))
            cornerRadius = dp(8).toFloat() // card, degrau 8 da escala de raio curto
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6); bottomMargin = dp(4) }
        // confianca chega em pt (contrato do JSON com a LLM, ver Llm.kt): traduzido só na
        // exibição, sem mexer no contrato de dados nem nos testes que o verificam.
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
        // a âncora com o glifo ⌁ dourado: a MESMA peça de procedência das outras telas
        addView(service.ancora(sug.ancora))
        addView(linhaDeBotoes(
            botaoCompacto("Use", primario = true) {
                if (aoUsar(chave)) montar()
                else Notices.texto(service, "couldn't write: did the field leave the screen?")
            },
        ))
    }

    // ── peças locais: só o que o overlay precisa e as telas não têm ────────────────────

    /** Cabeçalho de seção da lista: a mesma sobrescrita dourada do desenho, com respiro. */
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
     * Compact button for a PER-ITEM action (today only Use): same language as the
     * buttons in Ui.kt (solid gold = act, outline = secondary, radius 6) but line-sized,
     * because in a panel with several open fields each action is local, not for the whole screen.
     */
    private fun botaoCompacto(t: String, primario: Boolean, aoTocar: () -> Unit): Button =
        Button(service).apply {
            text = t
            isAllCaps = false
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            stateListAnimator = null // sem sombra de elevação: overlay plano, como o desenho
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

    /** Fileira horizontal de botões compactos, com o respiro entre eles. */
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
