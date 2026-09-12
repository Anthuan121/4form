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
    private val aoEscrever: (String, String) -> Boolean,
    private val aoFechar: () -> Unit,
    // brief 245: a sugestão da IA por chave de campo (null = não há), se ainda está
    // consultando, e as duas saídas que não passam pelo EditText comum
    private val sugestao: (String) -> Llm.Sugestao? = { null },
    private val consultando: (String) -> Boolean = { false },
    private val aoUsar: (String) -> Boolean = { false },
    private val aoDescartar: (String) -> Unit = {},
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
     * O QUE ESTÁ ACONTECENDO AGORA, uma linha por varredura, mais o motivo da parada quando
     * já parou. Pedido dele em 12/09 depois de ver a bolha girar sem fim: "a minha intenção
     * era, quando eu clicasse na bolinha, VER O QUE ESTÁ ACONTECENDO".
     *
     * 🎓 Por que isso vale mais que um log no arquivo: o dump só ajuda depois, num
     * computador. Quem está com o formulário aberto precisa da causa NA HORA, e é a causa
     * que decide se ele toca de novo, rola na mão ou desiste da tela.
     */
    private val estadoAoVivo: () -> List<String> = { emptyList() },
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

    // campos em que a pessoa tocou "Editar" na sugestão da IA: o cartão vira EditText
    // pré-preenchido e a escrita segue o caminho comum (que aprende a correção)
    private val emEdicao = mutableSetOf<String>()

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
            0, // janela focável: o EditText de "digitar ali mesmo" precisa do teclado
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

        // O ESTADO DO LAÇO vem PRIMEIRO quando a rodada ainda não acabou: nesse momento a
        // pergunta dele não é "o que você preencheu", é "o que você está fazendo".
        val passos = estadoAoVivo()
        if (passos.isNotEmpty()) {
            lista.addView(secao("What's happening"))
            for (linhaPasso in passos) {
                lista.addView(texto(linhaPasso, R.color.texto_secundario))
            }
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
        if (abertos.isNotEmpty()) lista.addView(secao("Left for you"))
        for (r in abertos) {
            // ponto ÂMBAR, não brasa: lacuna declarada é virtude do produto, não falha.
            // O motivo entra como detalhe pra frase dizer o porquê no mesmo olhar.
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
                // "Editar" da sugestão: começa do texto da IA; o que sair daqui é da pessoa
                if (chave in emEdicao && sug != null) setText(sug.resposta)
            }
            lista.addView(caixa)
            // botão dourado e COMPACTO: é ação por item, não a ação da tela inteira ·
            // dourado em faixa cheia aqui viraria uma parede de ouro a cada campo aberto
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
     * O cartão da resposta da IA (brief 245): visualmente DISTINTO do resto porque é
     * SUGESTÃO, não fato · fundo um degrau abaixo e contorno na cor primária, que em todo
     * o app marca "aqui o agente está falando". Mostra a resposta, a ÂNCORA ⌁ de onde ela
     * saiu (a promessa "não invento sobre você" se prova com procedência, não com texto de
     * marketing) e a confiança · e três saídas: Usar / Editar / Descartar. ⛔ Nada aqui
     * escreve no campo sem o toque da pessoa.
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
     * Field de texto do sistema: fundo recuado (um degrau abaixo do cartão, como no
     * desenho), fio de contorno e raio 6, o degrau de campo na escala 14/10/8/6/4.
     * 🎓 O EditText padrão do Android traz só a linha de baixo, que some sobre superfície
     * escura; o fundo fechado é o que faz o campo parecer LUGAR onde se escreve.
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
     * Botão compacto pra ação POR ITEM (Usar, Editar, Escrever no campo): mesma língua dos
     * botões de Ui.kt (dourado cheio = age, contorno = coadjuvante, raio 6) mas em tamanho
     * de linha, porque num painel com vários campos abertos cada ação é local, não da tela.
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
