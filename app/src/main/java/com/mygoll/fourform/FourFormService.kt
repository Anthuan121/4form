package com.mygoll.fourform

import com.mygoll.fourform.scan.Scanner
import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.mygoll.fourform.scan.Box
import com.mygoll.fourform.scan.Field
import com.mygoll.fourform.agent.Diagnostics
import com.mygoll.fourform.scan.Choice
import com.mygoll.fourform.scan.WindowRule
import com.mygoll.fourform.agent.Llm
import com.mygoll.fourform.agent.Engine
import com.mygoll.fourform.agent.Session
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * O serviço SÓ age quando chamado pelo botão de acessibilidade (gatilho explícito,
 * decisão do Anthuan: nada de bolha, nada de agir sozinho). Desde o 242 o gatilho abre
 * um LAÇO: varre a tela, preenche o que sabe um a um (pausa visível entre campos, pedido
 * dele: "eu vendo ele tendo ação é melhor do que chegar tudo preenchido já"), rola,
 * varre de novo, até o Engine mandar parar. No fim, o painel de resultado NA TELA.
 */
class FourFormService : AccessibilityService() {

    companion object {
        @Volatile
        var ativo = false

        // ponytail: 12s cobre a espera pós-rolagem mais a chamada de IA mais folga. Se um
        // formulário real estourar isso com o laço SAUDÁVEL, sobe o número; o vigia existe
        // contra callback que não volta, não contra formulário grande.
        const val TETO_SEM_PASSO_MS = 12_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var sessao: Session? = null
    private var motor: Engine? = null
    private var pacoteDaSessao: String? = null
    private var painel: Panel? = null

    /**
     * A bolha é a interface ATIVA do agente (desenho dele, 12/09). O painel deixou de abrir
     * sozinho no fim da rodada: agora quem abre é o toque NA BOLHA. Régua dele no mesmo dia:
     * "se ele não vai marcar, segue pro próximo campo, não precisa me travar ali". Panel
     * que aparece sem ser chamado é exatamente o travamento que ele descreveu.
     */
    private var bolha: Bubble? = null

    /** Monta o painel desta sessão sob demanda (o toque na bolha). */
    private var abrirPainel: (() -> Unit)? = null

    // estado da rodada em curso, acumulado varredura a varredura
    private val nosPorChave = mutableMapOf<String, AccessibilityNodeInfo>()
    private var rolavel: AccessibilityNodeInfo? = null
    private var totalNos = 0
    private var arquivoDiagnostico: File? = null
    private var motivoDaParada = ""
    private var voltasDaRodada = 0

    // uma linha por varredura: qual contêiner rolável foi escolhido e com que espera.
    // É o que faz o teste no aparelho devolver CAUSA em vez de só "parou cedo".
    private val laco = mutableListOf<String>()

    // censo dos campos de escolha da rodada inteira. Map por chave visual para não contar
    // duas vezes o mesmo checkbox quando ele reaparece depois da rolagem — mesma razão do
    // dedupe do Engine, só que aqui a chave é viewId+rótulo porque não há ação nem fila.
    private val escolhas = linkedMapOf<String, Choice>()
    private val nosEscolha = mutableMapOf<String, AccessibilityNodeInfo>()

    // placar do ACTION_CLICK. Existe porque "não marcou" tem duas causas muito diferentes:
    // a árvore recusou a ação, ou o nó já tinha saído da tela. Sem o placar no diagnóstico,
    // o teste em aparelho volta como "não funcionou" e não dá pra agir em cima disso.
    private var cliquesFeitos = 0
    private var cliquesFalhos = 0

    // qual meio de rolagem está em uso nesta rodada (escada: ação da árvore, depois gesto)
    private var usandoGesto = false

    // brief 245: sugestões da IA por chave de campo (esperando o toque no painel) e o
    // rastro por campo para o diagnóstico (desfecho + confiança + latência, sem conteúdo)
    private val sugestoesLlm = mutableMapOf<String, Llm.Sugestao>()
    private val llmDiag = mutableMapOf<String, Llm.LlmDiagnostico>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        ativo = true
        serviceInfo = serviceInfo?.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON
        }
        accessibilityButtonController.registerAccessibilityButtonCallback(
            object : AccessibilityButtonController.AccessibilityButtonCallback() {
                override fun onClicked(controller: AccessibilityButtonController) = preencher()
            }
        )
        Notices.canal(this)
    }

    override fun onDestroy() {
        ativo = false
        handler.removeCallbacksAndMessages(null)
        painel?.fechar()
        bolha?.fechar()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> aoMudarTexto(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> aoMudarJanela(event)
        }
    }

    /** O gatilho. Abre a rodada: sessão nova, motor novo, primeira varredura, e o laço anda. */
    private fun preencher() {
        abortarRodadaSeAtiva("you triggered it again mid-round")
        painel?.fechar()
        fecharSessao() // gatilho novo com sessão velha aberta: salva o que a velha aprendeu
        val raiz = rootInActiveWindow ?: run {
            Notices.texto(this, "No active window to read.")
            return
        }
        abrirPainel = null
        // a bolha nasce junto com a rodada e já conta que está lendo a tela
        bolha?.fechar()
        bolha = Bubble(
            this,
            aoTocar = { abrirPainel?.invoke() },
            // arrastar para o lixo encerra a RODADA, ⛔ não desliga o serviço. Decisão dele
            // em 12/09, e a distinção é de confiança: um app que lê a tela não pode dar a
            // sensação de "desliguei" quando continua ligado. Desligar segue nas configurações.
            aoDescartar = {
                painel?.fechar()
                abortarRodadaSeAtiva("you dismissed the bubble")
            },
        ).also { it.mostrar(); it.estado(Bubble.Estado.INTERPRETANDO) }
        pacoteDaSessao = raiz.packageName?.toString()
        // atalho aprendido: o nível que mais resolveu rótulo NESTE app; se falhar num
        // campo, a escada roda inteira (atalho, nunca trava)
        val preferido = Store.carregarCaminho(this).nivelPreferido(pacoteDaSessao ?: "")
        val s = Session(Store.perfil(this), nivelPreferido = preferido)
        sessao = s
        // o toque na bolha funciona DESDE JÁ, não só no fim. No 2.0 isto só era ligado em
        // encerrarRodada, então laço travado = toque morto, que foi o que ele mediu.
        abrirPainel = { montarPainel(s) }
        motor = Engine()
        nosPorChave.clear()
        rolavel = null
        totalNos = 0
        arquivoDiagnostico = null
        laco.clear()
        escolhas.clear()
        nosEscolha.clear()
        cliquesFeitos = 0
        cliquesFalhos = 0
        usandoGesto = false
        sugestoesLlm.clear()
        llmDiag.clear()
        varrer(raiz)
        avancar()
    }

    /**
     * TENTAR DE NOVO sem começar do zero (pedido dele, 12/09): "não precisa começar do zero,
     * pra ele tentar novamente nos campos que ele não conseguiu".
     *
     * 🎓 O truque é não precisar de lista de pendências: a MESMA sessão continua (com tudo
     * que ela aprendeu e registrou), e só o MOTOR é novo. Field já preenchido tem texto, e a
     * régua "já tem texto: não sobrescrevo o que não fui eu que pus" o descarta sozinho. Ou
     * seja, o retomar cai naturalmente no que faltou, sem código de exceção.
     */
    private fun retomar() {
        val s = sessao ?: return preencher() // sessão morta: aí é rodada nova mesmo
        painel?.fechar()
        handler.removeCallbacksAndMessages(null)
        val raiz = rootInActiveWindow ?: run {
            Notices.texto(this, "No active window to read.")
            return
        }
        motor = Engine()
        nosPorChave.clear()
        rolavel = null
        laco.clear()
        usandoGesto = false
        motivoDaParada = ""
        bolha?.estado(Bubble.Estado.INTERPRETANDO)
        abrirPainel = { montarPainel(s) }
        varrer(raiz)
        avancar()
    }

    private fun varrer(raiz: AccessibilityNodeInfo) {
        val m = motor ?: return
        val saida = Scanner.varrer(raiz)
        nosPorChave.putAll(saida.nos)
        saida.rolavel?.let { rolavel = it }
        totalNos += saida.totalNos
        val escolhasNovas = mutableListOf<Choice>()
        for (e in saida.escolhas) {
            if (escolhas.putIfAbsent(e.chave, e) == null) escolhasNovas.add(e)
        }
        nosEscolha.putAll(saida.nosEscolha)
        laco.add(
            descreverRolavel(saida.rolavel, saida.campos.size, saida.totalNos, escolhasNovas.size)
        )
        m.receberVarredura(
            raiz.packageName?.toString(), saida.campos, escolhasNovas, saida.assinatura,
        )
    }

    /** Só forma e tamanho do contêiner: nenhum texto de campo entra aqui (o arquivo sai do aparelho). */
    private fun descreverRolavel(
        no: AccessibilityNodeInfo?,
        campos: Int,
        nos: Int,
        escolhasNovas: Int,
    ): String {
        // o total de NÓS por varredura é o que distingue "rolei e a tela era só de radios"
        // de "pedi rolagem, ela disse ok e nada se moveu". Sem esse número os dois casos
        // produzem o mesmo log e a próxima depuração vira adivinhação.
        val sufixo = "· $campos fields · +$escolhasNovas choices · $nos nodes · wait ${Pace.POS_ROLAGEM_MS}ms"
        if (no == null) return "scan ${laco.size}: NO scrollable container visible $sufixo"
        val r = Rect().also { no.getBoundsInScreen(it) }
        val id = no.viewIdResourceName ?: "no id"
        return "scan ${laco.size}: scrollable ${no.className} ($id) ${r.width()}x${r.height()} $sufixo"
    }

    /**
     * Vigia do laço. Cada passo reagenda este alarme; se ele dispara, é porque o laço parou
     * de andar sem encerrar a rodada, e a bolha ficaria girando para sempre.
     *
     * 🎓 Por que um vigia e não "consertar o travamento": o laço depende de callbacks do
     * SISTEMA (o gesto de rolagem avisa quando terminou). Callback que não volta é uma
     * classe inteira de falha que não se elimina por código, então o certo é ter um prazo e
     * contar a verdade quando ele estoura. Medido por ele em 12/09: "está girando girando e
     * nada aconteceu" era o laço mudo, e nada na tela dizia isso.
     */
    private val vigiaDoLaco = Runnable {
        if (motor == null) return@Runnable
        laco.add("WATCHDOG: no step in ${TETO_SEM_PASSO_MS}ms, stopping as a safety measure")
        encerrarRodada("the loop stopped responding (no step in ${TETO_SEM_PASSO_MS / 1000}s)")
    }

    private fun renovarVigia() {
        handler.removeCallbacks(vigiaDoLaco)
        if (motor != null) handler.postDelayed(vigiaDoLaco, TETO_SEM_PASSO_MS)
    }

    /** Um passo do laço por vez; a pausa entre campos é o ritmo visível (Pace.ENTRE_CAMPOS_MS). */
    private fun avancar() {
        renovarVigia()
        val m = motor ?: return
        val s = sessao ?: return
        when (val passo = m.proximoPasso()) {
            is Engine.Passo.Agir -> {
                bolha?.estado(Bubble.Estado.PREENCHENDO)
                val campo = passo.campo
                when (val d = s.decidir(campo)) {
                    is Session.Decisao.Preencher -> {
                        val ok = escrever(nosPorChave[campo.chave], d.valor)
                        s.registrar(campo, d, ok)
                    }
                    is Session.Decisao.DeixarAberto -> s.registrar(campo, d)
                }
                m.campoTratado(campo.chave)
                handler.postDelayed(::avancar, Pace.ENTRE_CAMPOS_MS)
            }
            is Engine.Passo.Escolher -> {
                bolha?.estado(Bubble.Estado.PREENCHENDO)
                val e = passo.escolha
                if (s.decidirEscolha(e) is Session.Decisao.Preencher) {
                    // ACTION_CLICK é a ação da própria árvore, não gesto por coordenada: o
                    // 247 provou que checkbox e radio a expõem direto, e gesto por pixel
                    // quebraria em tela de outro tamanho.
                    val ok = nosEscolha[e.chave]
                        ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    if (ok) {
                        cliquesFeitos++
                        escolhas[e.chave] = e.copy(marcada = true, origemDecisao = "perfil")
                    } else {
                        cliquesFalhos++
                        escolhas[e.chave] = e.copy(origemDecisao = "perfil")
                    }
                } else {
                    // Camada 2 (brief 257) tenta de novo mais tarde, em grupo, quando a
                    // rodada acabar: por ora fica registrado que nem o perfil resolveu.
                    escolhas[e.chave] = e.copy(origemDecisao = "nenhum")
                }
                // ⛔ Sem else: não marcar NÃO interrompe e não pergunta nada. O laço segue.
                m.campoTratado(e.chave)
                handler.postDelayed(::avancar, Pace.ENTRE_CAMPOS_MS)
            }
            Engine.Passo.Rolar -> {
                // rolar é ler a tela de novo, não agir: volta ao arco girando
                bolha?.estado(Bubble.Estado.INTERPRETANDO)
                rolar(m)
            }
            is Engine.Passo.Fim -> {
                // "a tela não se move mais" pode ser fim do formulário OU o meio de rolagem
                // não servir para este app. Antes de encerrar, troca o meio uma vez: pedir
                // à árvore falha em WebView (medido), e o gesto de arrastar funciona onde a
                // ação não funciona. Só encerra quando os DOIS meios não moveram nada.
                if (m.telaNaoSeMoveu && !usandoGesto) {
                    usandoGesto = true
                    m.tentarOutroMeioDeRolagem()
                    laco.add("the tree's action didn't move the screen: switching to drag gesture")
                    rolar(m)
                } else {
                    encerrarRodada(passo.motivo)
                }
            }
        }
    }

    /**
     * Rolar em dois meios, nesta ordem:
     * 1. ACTION_SCROLL_FORWARD, a ação da própria árvore. É o meio limpo e funciona em app
     *    nativo. Em WebView ele retorna sucesso e não move nada (medido no Edge, 12/09).
     * 2. Gesto de arrastar. Funciona onde a ação não funciona, mas depende de coordenada,
     *    então é calculado dos bounds REAIS do contêiner e nunca de número fixo: assim
     *    sobrevive a tela de outro tamanho, que era a objeção original ao gesto.
     */
    private fun rolar(m: Engine) {
        if (!usandoGesto) {
            val pediu = rolavel?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
            if (!pediu) {
                // recusou na cara: nem vale esperar, já troca de meio
                if (usandoGesto) { m.rolagemFalhou(); avancar(); return }
                usandoGesto = true
                laco.add("the tree refused ACTION_SCROLL_FORWARD: switching to gesture")
                rolar(m)
                return
            }
            handler.postDelayed({ varrerDeNovo(m) }, Pace.POS_ROLAGEM_MS)
            return
        }
        val alvo = rolavel ?: run { m.rolagemFalhou(); avancar(); return }
        val r = Rect().also { alvo.getBoundsInScreen(it) }
        // arrasta do terço de baixo para o terço de cima, dentro do próprio contêiner:
        // uma "tela" de cada vez, com sobreposição, para nenhum campo passar batido entre
        // duas varreduras. Margem de 10% nas bordas evita o gesto virar puxar-para-atualizar.
        val x = (r.left + r.right) / 2f
        val de = r.bottom - r.height() * 0.15f
        val ate = r.top + r.height() * 0.25f
        val caminho = Path().apply {
            moveTo(x, de)
            lineTo(x, ate)
        }
        val gesto = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(caminho, 0L, Pace.GESTO_MS))
            .build()
        // MEDIDO NOS DUMPS DE 12/09 (6 rodadas seguidas, Edge/WebView): dispatchGesture
        // devolve true, o gesto acontece, e NEM onCompleted NEM onCancelled disparam. O laço
        // ficava pendurado esperando um callback que não vem e só o vigia o soltava, 12s
        // depois. Era isto que fazia "ele não rolou até o final".
        //
        // 🎓 A correção não é caçar o motivo do callback sumir (é do sistema, fora do nosso
        // alcance): é parar de depender de UMA fonte de verdade. Despacha o gesto E agenda a
        // continuação por tempo; o primeiro que chegar assume e o outro vira no-op. Mesmo
        // princípio do vigia, só que local e em 1,5s em vez de 12.
        var jaSeguiu = false
        val seguir = {
            if (!jaSeguiu && motor === m) {
                jaSeguiu = true
                varrerDeNovo(m)
            }
        }
        val despachou = dispatchGesture(
            gesto,
            object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) {
                    handler.postDelayed({ seguir() }, Pace.POS_ROLAGEM_MS)
                }

                override fun onCancelled(d: GestureDescription?) {
                    if (jaSeguiu) return
                    jaSeguiu = true
                    m.rolagemFalhou()
                    avancar()
                }
            },
            handler,
        )
        // a rede: duração do gesto + a espera da tela assentar + folga
        handler.postDelayed({ seguir() }, Pace.GESTO_MS + Pace.POS_ROLAGEM_MS + 400L)
        if (!despachou) {
            m.rolagemFalhou()
            avancar()
        }
    }

    private fun varrerDeNovo(m: Engine) {
        rootInActiveWindow?.let { varrer(it) } ?: m.rolagemFalhou()
        avancar()
    }

    private fun escrever(no: AccessibilityNodeInfo?, valor: String): Boolean {
        no ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, valor)
        }
        return no.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * Fim do laço: grava o diagnóstico, alimenta o aprendizado de caminho, mostra o
     * resultado NA TELA (painel; a notificação vira só registro de histórico) — mas
     * painel só quando a rodada viu campo: painel de nada é ruído.
     */
    private fun encerrarRodada(motivo: String) {
        handler.removeCallbacks(vigiaDoLaco)
        val m = motor ?: return
        motor = null
        val s = sessao ?: return
        motivoDaParada = motivo
        val registros = s.registros()

        if (registros.isNotEmpty()) {
            val caminho = Store.carregarCaminho(this)
            registros.forEach { r -> r.origemRotulo?.let { caminho.registrar(pacoteDaSessao ?: "", it) } }
            Store.salvarCaminho(this, caminho)
        }
        gravarDiagnostico(s, m.voltasDeRolagem, aprendidos = 0)

        val abertos = s.abertosAgora()
        val escritos = registros.count { it.acao == "preencheu" }
        Notices.resumo(this, escritos, abertos) // registro silencioso; o canal principal é a tela

        if (!m.achouAlgumCampo) {
            // o censo entra AQUI também: "não achei campo de texto, mas vi 12 de escolha"
            // é diagnóstico; "não achei nada" mandava consertar a varredura à toa.
            val cegos = escolhas.size
            Notices.texto(
                this,
                if (cegos > 0) "No text field here, but I saw $cegos choice field(s) I still can't operate."
                else "I didn't find a text field on this screen."
            )
            bolha?.fechar()
            bolha = null
            return
        }
        // A bolha passa a CONTAR o que ficou aberto, e fica quieta. ⛔ O painel NÃO abre
        // sozinho: quem abre é o toque nela. Separar "estou trabalhando" de "preciso de
        // você" é o ponto inteiro do desenho de 12/09.
        // ERRO ⛔ não é o mesmo que "sobrou campo": parada por falha do laço é problema DO
        // APP, e a bolha precisa dizer isso em vermelho. Pedido dele em 12/09: "se houver
        // qualquer erro ou qualquer tipo de informação, ele deveria me notificar na bolinha".
        val falhou = motivo.contains("stopped responding") || motivo.contains("doesn't move")
        bolha?.estado(
            when {
                falhou -> Bubble.Estado.ERRO
                abertos.isNotEmpty() -> Bubble.Estado.PENDENCIA
                else -> Bubble.Estado.TERMINOU
            },
            pendentes = abertos.size,
        )
        consultarLlm(s)
        consultarLlmEscolhas(s)
    }

    /**
     * Monta o painel desta sessão. Vive FORA do fim da rodada de propósito: o toque na
     * bolha precisa funcionar a QUALQUER momento, inclusive com o laço ainda andando e
     * inclusive com o laço TRAVADO. Foi exatamente o que faltou no 2.0, medido por ele no
     * aparelho em 12/09: "a bolinha está girando girando e nada aconteceu, e a minha
     * intenção era quando eu clicasse na bolinha ver o que está acontecendo".
     */
    private fun montarPainel(s: Session) {
        if (painel != null) return // já aberto: o toque não empilha janela
        painel = Panel(
            this,
            registros = { s.registros() },
            aoDesfazer = { chave ->
                // limpa o campo de verdade e só então marca desfeito
                escrever(nosPorChave[chave], "") && s.desfazer(chave)
            },
            aoFechar = { painel = null },
            sugestao = { chave -> sugestoesLlm[chave] },
            consultando = { chave -> llmDiag[chave]?.desfecho == "consultando" },
            aoUsar = { chave ->
                val sug = sugestoesLlm[chave]
                val ok = sug != null && escrever(nosPorChave[chave], sug.resposta) &&
                    s.escreverAgora(chave, sug.resposta, fonte = "AI · approved by you")
                if (ok) {
                    sugestoesLlm.remove(chave)
                    fecharEpisodioLlm(s, chave, "aceita")
                }
                ok
            },
            escolhasVistas = { escolhas.size },
            escolhasLista = { escolhas.values.toList() },
            aoMarcar = { chave ->
                // ACTION_CLICK é a ação da própria árvore, não gesto por coordenada: o
                // 247 provou que checkbox e radio a expõem direto. Gesto por pixel quebra
                // em tela de outro tamanho; a ação da árvore vale em qualquer aparelho.
                val ok = nosEscolha[chave]?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                if (ok) cliquesFeitos++ else cliquesFalhos++
                ok
            },
            aoTentarDeNovo = { retomar() },
            // brief 255: only the EXCEPTION he approved reaches the panel · a round that
            // ended in a real failure (watchdog fired, or the screen stopped scrolling).
            // While the loop is still running (motor != null) there's nothing to show
            // here: the bubble already reports that (Bubble.Estado), and the step-by-step
            // log had become developer noise.
            linhaDeFalha = { if (motor == null) linhaHumanaDaFalha(motivoDaParada) else null },
        ).also { it.mostrar() }
    }

    /**
     * Translates the technical stop reason into a single plain-language sentence, no
     * jargon, no node number · only for the two causes that ARE app failures (brief 255):
     * the watchdog fired, or no scrolling method moved the screen. A normal end of the
     * form (reached the end, screen changed, no field found) is not a failure and
     * doesn't reach this function.
     */
    private fun linhaHumanaDaFalha(motivo: String): String? = when {
        motivo.contains("stopped responding") ->
            "Something got stuck while filling the form. Try again, or scroll down yourself and tap the bubble."
        motivo.contains("doesn't move") ->
            "I couldn't scroll any further on this screen. Scroll down yourself, then tap the bubble to try again."
        else -> null
    }

    /**
     * Os campos que ficaram em "não tenho esse dado" viram pergunta à LLM da casa
     * (brief 245), um a um, numa THREAD única: a main thread aqui é a do serviço de
     * acessibilidade, e rede nela congela o preenchimento na cara da pessoa. ⛔ A
     * resposta NUNCA é escrita no campo por este caminho: vira cartão no painel
     * esperando o toque. Qualquer falha (sem rede, timeout, 500, JSON quebrado) deixa
     * o campo exatamente como está — o runCatching aqui e o Llm.avaliar garantem que
     * a rodada nunca cai por causa da IA.
     */
    private fun consultarLlm(s: Session) {
        val candidatos = Llm.candidatos(s.registros())
        if (candidatos.isEmpty()) return
        val linhas = Llm.linhasDePerfil(Store.perfilTexto(this), Store.aprendidos(this))
        if (linhas.isEmpty()) return // sem perfil não existe âncora possível; perguntar seria pedir invenção
        candidatos.forEach { llmDiag[it.campo.chave] = Llm.LlmDiagnostico("consultando", null, null) }
        painel?.atualizar()
        // UMA THREAD POR CAMPO, em paralelo. MEDIDO no teste dele de 10/09: sequencial dava
        // ~1,6s por campo e o 4º só chegava aos 6,5s — ele fechava o painel antes e dois
        // campos ficavam em "consultando" para sempre. Em paralelo o último chega em ~2s.
        // 🎓 Por que Thread crua e não pool: são no máximo alguns campos por rodada, cada um
        // com timeout de 20s; um executor traria ciclo de vida para gerenciar sem ganho real.
        // A aplicação do resultado continua serializada no handler da main thread, então
        // sugestoesLlm e llmDiag nunca são tocados de duas threads ao mesmo tempo.
        for (reg in candidatos) {
            val rotulo = reg.rotulo ?: continue
            Thread {
                if (sessao !== s) return@Thread // a sessão morreu: parar de gastar rede
                val t0 = System.currentTimeMillis()
                val resultado = runCatching { LlmBridge.chamar(Llm.corpo(rotulo, linhas)) }
                val veredito = Llm.avaliar(resultado, linhas)
                val latencia = System.currentTimeMillis() - t0
                handler.post {
                    if (sessao !== s) return@post
                    val desfecho = Llm.aplicar(reg, veredito, sugestoesLlm)
                    val confianca = (veredito as? Llm.Veredito.Responder)?.sugestao?.confianca
                    llmDiag[reg.campo.chave] = Llm.LlmDiagnostico(desfecho, confianca, latencia)
                    painel?.atualizar()
                    gravarDiagnostico(s, voltas = null, aprendidos = 0)
                }
            }.start()
        }
    }

    /**
     * Camada 2 de escolha (brief 257): quando o perfil sozinho não decidiu uma opção mas a
     * pergunta do grupo é legível, a IA entra com a LISTA FECHADA de opções daquele grupo.
     * Mesmo desenho da IA de texto (consultarLlm): thread por grupo, aplicação serializada
     * no handler, e a régua "se vai marcar, marcou e o jogo segue" continua valendo, então
     * isto roda DEPOIS da rodada terminar, nunca bloqueando o laço.
     */
    private fun consultarLlmEscolhas(s: Session) {
        val grupos = Llm.agruparPorPergunta(escolhas.values.toList())
        if (grupos.isEmpty()) return
        val linhas = Llm.linhasDePerfil(Store.perfilTexto(this), Store.aprendidos(this))
        if (linhas.isEmpty()) return // sem perfil não existe âncora possível
        for ((pergunta, grupo) in grupos) {
            val opcoes = grupo.mapNotNull { it.rotulo }.distinct()
            if (opcoes.isEmpty()) continue
            Thread {
                if (sessao !== s) return@Thread
                val resultado = runCatching { LlmBridge.chamar(Llm.corpoEscolha(pergunta, opcoes, linhas)) }
                val veredito = Llm.avaliarEscolha(resultado, opcoes, linhas)
                handler.post {
                    if (sessao !== s) return@post
                    when (veredito) {
                        is Llm.VereditoEscolha.Marcar -> {
                            val alvo = grupo.firstOrNull { it.rotulo?.trim() == veredito.opcao }
                            val ok = alvo != null &&
                                nosEscolha[alvo.chave]?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                            if (ok && alvo != null) {
                                cliquesFeitos++
                                escolhas[alvo.chave] = alvo.copy(marcada = true, origemDecisao = "ia")
                            } else {
                                if (alvo != null) cliquesFalhos++
                            }
                        }
                        is Llm.VereditoEscolha.NaoMarcar -> Unit // segue "nenhum": nada a fazer
                    }
                    gravarDiagnostico(s, voltas = null, aprendidos = 0)
                }
            }.start()
        }
    }

    /** Desfecho final de um episódio de IA (aceita/editada/descartada), preservando confiança e latência. */
    private fun fecharEpisodioLlm(s: Session, chave: String, desfecho: String) {
        val antes = llmDiag[chave]
        llmDiag[chave] = Llm.LlmDiagnostico(desfecho, antes?.confianca, antes?.latenciaMs)
        gravarDiagnostico(s, voltas = null, aprendidos = 0)
    }

    /** Evento de janela no MEIO do laço: aborta com o motivo certo antes de fechar a sessão. */
    private fun abortarRodadaSeAtiva(motivo: String) {
        if (motor == null) return
        handler.removeCallbacksAndMessages(null)
        encerrarRodada(motivo)
    }

    /** O usuário digitou (ou ditou pelo teclado: para o app é a mesma coisa). */
    private fun aoMudarTexto(event: AccessibilityEvent) {
        val s = sessao ?: return
        val no = event.source ?: return
        if (no.isPassword) return // trava dura: senha não é lida nem aqui
        val r = Rect()
        no.getBoundsInScreen(r)
        val chave = Scanner.chaveEstavel(
            no.viewIdResourceName,
            no.hintText?.toString()?.takeIf { it.isNotBlank() },
            no.contentDescription?.toString()?.takeIf { it.isNotBlank() },
            Box(r.left, r.top, r.right, r.bottom),
        )
        val novo = if (no.isShowingHintText) "" else (no.text?.toString() ?: return)
        s.textoMudou(chave, novo)
    }

    /** A tela mudou = fim daquela sessão (multi-página inclusive). Teclado e o próprio app não contam. */
    private fun aoMudarJanela(event: AccessibilityEvent) {
        if (sessao == null) return
        if (!WindowRule.encerraSessao(event.packageName?.toString(), event.className?.toString(), packageName)) return
        abortarRodadaSeAtiva("the screen changed to another app mid-round")
        painel?.fechar() // o painel fala de uma tela que já era
        // ⛔ A BOLHA NÃO FECHA AQUI. Régua dele, 12/09: "a bolinha só deve fechar na hora
        // que eu jogo ela no lixo". Fechar a bolha na troca de janela obrigava a pessoa a
        // apertar o botão de acessibilidade de novo a cada ida e volta, e era o que fazia
        // parecer que o toque fora do painel matava o agente.
        fecharSessao()
    }

    private fun fecharSessao() {
        val s = sessao ?: return
        sessao = null
        val recibo = s.fechar() ?: return // sessão sem preenchimento nem aprendizado: recibo não pisca
        if (recibo.aprendidos.isNotEmpty()) Store.adicionarAprendidos(this, recibo.aprendidos)
        Store.gravarRecibo(this, recibo)
        // regrava o diagnóstico da rodada com o estado FINAL (painel e aprendizados inclusos)
        if (arquivoDiagnostico != null) gravarDiagnostico(s, voltas = null, aprendidos = recibo.aprendidos.size)
        Notices.recibo(this, recibo)
    }

    /**
     * Grava mesmo com ZERO campos: "vi 400 nós e nenhum era campo de texto" é exatamente
     * o dado que substitui o print quando a varredura falha numa tela cheia de campos.
     */
    private fun gravarDiagnostico(s: Session, voltas: Int?, aprendidos: Int) {
        if (voltas != null) voltasDaRodada = voltas
        val quando = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val json = Diagnostics.json(
            BuildConfig.VERSION_NAME, pacoteDaSessao, quando, totalNos,
            voltasDaRodada, motivoDaParada, s.registros(), aprendidos, laco.toList(),
            llm = llmDiag.toMap(),
            escolhas = escolhas.values.toList(),
            cliques = "$cliquesFeitos ok / $cliquesFalhos falhou",
        )
        arquivoDiagnostico = Store.gravarDiagnostico(this, json, sobrescrever = arquivoDiagnostico)
        // Probe de bancada: só na build de teste, um arquivo por envio, falha em silêncio.
        // Fica DEPOIS da gravação local de propósito — o arquivo no aparelho é a fonte, a
        // sonda é a cópia de conveniência; se a ordem fosse inversa, uma rede lenta atrasaria
        // o que já está garantido.
        Bench.enviar(json, System.currentTimeMillis())
    }
}
