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
 * The service ONLY acts when invoked via the accessibility button (explicit trigger,
 * Anthuan's decision: no bubble, no acting on its own). Since 242 the trigger opens a
 * LOOP: scans the screen, fills what it knows one at a time (visible pause between
 * fields, his request: "seeing it take action is better than everything showing up
 * already filled"), scrolls, scans again, until the Engine says stop. At the end, the
 * result panel shows ON SCREEN.
 */
class FourFormService : AccessibilityService() {

    companion object {
        @Volatile
        var ativo = false

        // ponytail: 12s covers the post-scroll wait plus the AI call plus slack. If a
        // real form blows past this with a HEALTHY loop, raise the number; the watchdog
        // exists against a callback that never returns, not against a large form.
        const val TETO_SEM_PASSO_MS = 12_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var sessao: Session? = null
    private var motor: Engine? = null
    private var pacoteDaSessao: String? = null
    private var painel: Panel? = null

    /**
     * The bubble is the agent's ACTIVE interface (his design, 09/12). The panel no longer
     * opens on its own at the end of a round: now it opens on a tap ON THE BUBBLE. His
     * rule the same day: "if it's not going to check it, move on to the next field, no
     * need to block me there". A Panel that shows up uninvited is exactly the blocking
     * behavior he described.
     */
    private var bolha: Bubble? = null

    /** Builds this session's panel on demand (the tap on the bubble). */
    private var abrirPainel: (() -> Unit)? = null

    // state of the round in progress, accumulated scan by scan
    private val nosPorChave = mutableMapOf<String, AccessibilityNodeInfo>()
    private var rolavel: AccessibilityNodeInfo? = null
    private var totalNos = 0
    private var arquivoDiagnostico: File? = null
    private var motivoDaParada = ""
    private var voltasDaRodada = 0

    // one line per scan: which scrollable container was chosen and what wait was used.
    // This is what makes the on-device test return a CAUSE instead of just "stopped early".
    private val laco = mutableListOf<String>()

    // census of choice fields for the whole round. Map keyed by visual key so the same
    // checkbox isn't counted twice when it reappears after scrolling. Same reason as the
    // Engine's dedupe, except here the key is viewId+label because there's no action or queue.
    private val escolhas = linkedMapOf<String, Choice>()
    private val nosEscolha = mutableMapOf<String, AccessibilityNodeInfo>()

    // ACTION_CLICK tally. Exists because "didn't check" has two very different causes:
    // the tree refused the action, or the node had already left the screen. Without the
    // tally in the diagnostic, the on-device test comes back as "didn't work" and there's
    // nothing to act on.
    private var cliquesFeitos = 0
    private var cliquesFalhos = 0

    // which scroll method is in use this round (ladder: tree action, then gesture)
    private var usandoGesto = false

    // brief 245: AI suggestions per field key (waiting for the panel tap) and the
    // per-field trail for the diagnostic (outcome + confidence + latency, no content)
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

    /** The trigger. Opens the round: new session, new engine, first scan, and the loop starts. */
    private fun preencher() {
        abortarRodadaSeAtiva("you triggered it again mid-round")
        painel?.fechar()
        fecharSessao() // new trigger with an old session still open: saves what the old one learned
        val raiz = rootInActiveWindow ?: run {
            Notices.texto(this, "No active window to read.")
            return
        }
        abrirPainel = null
        // the bubble is born together with the round and already shows it's reading the screen
        bolha?.fechar()
        bolha = Bubble(
            this,
            aoTocar = { abrirPainel?.invoke() },
            // dragging to the trash ends the ROUND, ⛔ it does not turn off the service.
            // His decision on 09/12, and the distinction is about trust: an app that
            // reads the screen can't give the feeling of "I turned it off" when it's
            // still on. Turning it off stays in settings.
            aoDescartar = {
                painel?.fechar()
                abortarRodadaSeAtiva("you dismissed the bubble")
            },
        ).also { it.mostrar(); it.estado(Bubble.Estado.INTERPRETANDO) }
        pacoteDaSessao = raiz.packageName?.toString()
        // learned shortcut: the level that resolved the label most often IN THIS app; if
        // it fails on a field, the whole ladder still runs (a shortcut, never a hard stop)
        val preferido = Store.carregarCaminho(this).nivelPreferido(pacoteDaSessao ?: "")
        val s = Session(Store.perfil(this), nivelPreferido = preferido)
        sessao = s
        // the tap on the bubble works RIGHT AWAY, not just at the end. In 2.0 this was
        // only wired up in encerrarRodada, so a stuck loop = a dead tap, which is what he measured.
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
     * TRY AGAIN without starting from zero (his request, 09/12): "no need to start from
     * zero, just have it try again on the fields it couldn't get".
     *
     * 🎓 The trick is not needing a pending-items list: the SAME session continues (with
     * everything it learned and recorded), and only the ENGINE is new. A field that's
     * already filled has text, and the rule "already has text: I don't overwrite what I
     * didn't put there" discards it on its own. In other words, resuming naturally lands
     * on what's left, with no special-case code.
     */
    private fun retomar() {
        val s = sessao ?: return preencher() // dead session: then it really is a new round
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

    /** Only the container's shape and size: no field text goes in here (this file leaves the device). */
    private fun descreverRolavel(
        no: AccessibilityNodeInfo?,
        campos: Int,
        nos: Int,
        escolhasNovas: Int,
    ): String {
        // the total NODE count per scan is what distinguishes "I scrolled and the screen
        // was just radios" from "I asked to scroll, it said ok, and nothing moved".
        // Without this number both cases produce the same log and the next debugging
        // session turns into guesswork.
        val sufixo = "· $campos fields · +$escolhasNovas choices · $nos nodes · wait ${Pace.POS_ROLAGEM_MS}ms"
        if (no == null) return "scan ${laco.size}: NO scrollable container visible $sufixo"
        val r = Rect().also { no.getBoundsInScreen(it) }
        val id = no.viewIdResourceName ?: "no id"
        return "scan ${laco.size}: scrollable ${no.className} ($id) ${r.width()}x${r.height()} $sufixo"
    }

    /**
     * The loop's watchdog. Every step reschedules this alarm; if it fires, it's because
     * the loop stopped moving without ending the round, and the bubble would spin forever.
     *
     * 🎓 Why a watchdog instead of "fixing the freeze": the loop depends on SYSTEM
     * callbacks (the scroll gesture reports when it's done). A callback that never
     * returns is a whole class of failure that can't be eliminated by code, so the right
     * move is to set a deadline and tell the truth when it's blown. Measured by him on
     * 09/12: "it's spinning and spinning and nothing happened" was the silent loop, and
     * nothing on screen said so.
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

    /** One loop step at a time; the pause between fields is the visible pace (Pace.ENTRE_CAMPOS_MS). */
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
                    // ACTION_CLICK is the tree's own action, not a coordinate gesture: 247
                    // proved that checkbox and radio expose it directly, and a pixel
                    // gesture would break on a screen of a different size.
                    val ok = nosEscolha[e.chave]
                        ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    if (ok) {
                        cliquesFeitos++
                        escolhas[e.chave] = e.copy(marcada = true)
                    } else {
                        cliquesFalhos++
                    }
                }
                // ⛔ No else: failing to check it does NOT interrupt and does not ask anything. The loop continues.
                m.campoTratado(e.chave)
                handler.postDelayed(::avancar, Pace.ENTRE_CAMPOS_MS)
            }
            Engine.Passo.Rolar -> {
                // scrolling means reading the screen again, not acting: back to the spinning arc
                bolha?.estado(Bubble.Estado.INTERPRETANDO)
                rolar(m)
            }
            is Engine.Passo.Fim -> {
                // "the screen doesn't move anymore" can mean the end of the form OR that
                // the scroll method doesn't work for this app. Before ending, switch
                // methods once: asking the tree fails in WebView (measured), and the drag
                // gesture works where the action doesn't. Only ends when NEITHER method
                // moved anything.
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
     * Scroll via two methods, in this order:
     * 1. ACTION_SCROLL_FORWARD, the tree's own action. It's the clean method and works in
     *    native apps. In WebView it returns success and moves nothing (measured on Edge, 09/12).
     * 2. Drag gesture. Works where the action doesn't, but depends on coordinates, so
     *    it's calculated from the container's REAL bounds and never a fixed number: that
     *    way it survives a screen of a different size, which was the original objection to
     *    the gesture.
     */
    private fun rolar(m: Engine) {
        if (!usandoGesto) {
            val pediu = rolavel?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
            if (!pediu) {
                // flat-out refused: not even worth waiting, switch methods right away
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
        // drags from the bottom third to the top third, inside the container itself: one
        // "screen" at a time, with overlap, so no field slips through unseen between two
        // scans. A 10% edge margin keeps the gesture from turning into pull-to-refresh.
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
        // MEASURED IN THE 09/12 DUMPS (6 rounds in a row, Edge/WebView): dispatchGesture
        // returns true, the gesture happens, and NEITHER onCompleted NOR onCancelled
        // fires. The loop would hang waiting for a callback that never comes, and only
        // the watchdog would release it, 12s later. This was what made it look like "it
        // didn't scroll to the end".
        //
        // 🎓 The fix isn't hunting down why the callback disappears (it's the system's
        // doing, out of our reach): it's not depending on a SINGLE source of truth
        // anymore. Dispatch the gesture AND schedule the continuation by time; whichever
        // arrives first wins, and the other becomes a no-op. Same principle as the
        // watchdog, just local and at 1.5s instead of 12.
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
        // the safety net: gesture duration + wait for the screen to settle + slack
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
     * End of the loop: writes the diagnostic, feeds the path learning, shows the result
     * ON SCREEN (panel; the notification becomes just a history record). But the panel
     * only shows when the round saw a field: a panel about nothing is noise.
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
        Notices.resumo(this, escritos, abertos) // silent record; the main channel is the screen

        if (!m.achouAlgumCampo) {
            // the census comes in HERE too: "found no text field, but saw 12 choice
            // fields" is a diagnostic; "found nothing" used to send you off to fix the
            // scan for nothing.
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
        // The bubble now COUNTS what's left open, and stays quiet. ⛔ The panel does NOT
        // open on its own: only a tap on it opens it. Separating "I'm working" from "I
        // need you" is the whole point of the 09/12 design.
        // ERROR ⛔ is not the same as "field left over": stopping due to a loop failure is
        // an APP problem, and the bubble needs to say so in red. His request on 09/12:
        // "if there's any error or any kind of information, it should notify me on the bubble".
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
    }

    /**
     * Builds this session's panel. Lives OUTSIDE the end of the round on purpose: the tap
     * on the bubble needs to work at ANY moment, including while the loop is still
     * running and even while the loop is STUCK. This was exactly what was missing in 2.0,
     * measured by him on the device on 09/12: "the bubble is spinning and spinning and
     * nothing happened, and my intention was that when I tapped the bubble I'd see what's happening".
     */
    private fun montarPainel(s: Session) {
        if (painel != null) return // already open: the tap doesn't stack a new window
        painel = Panel(
            this,
            registros = { s.registros() },
            aoDesfazer = { chave ->
                // clears the field for real and only then marks it undone
                escrever(nosPorChave[chave], "") && s.desfazer(chave)
            },
            aoEscrever = { chave, valor ->
                val ok = escrever(nosPorChave[chave], valor) && s.escreverAgora(chave, valor)
                // if there was an AI suggestion for this field, what the person wrote
                // decides the outcome: same as the suggestion = accepted; different =
                // edited (the correction already went into learned entries via
                // escreverAgora, and the AI is overridden next time)
                if (ok) sugestoesLlm.remove(chave)?.let { sug ->
                    fecharEpisodioLlm(s, chave, if (valor == sug.resposta) "aceita" else "editada")
                }
                ok
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
            aoDescartar = { chave ->
                if (sugestoesLlm.remove(chave) != null) fecharEpisodioLlm(s, chave, "descartada")
            },
            escolhasVistas = { escolhas.size },
            escolhasLista = { escolhas.values.toList() },
            aoMarcar = { chave ->
                // ACTION_CLICK is the tree's own action, not a coordinate gesture: 247
                // proved that checkbox and radio expose it directly. A pixel gesture
                // breaks on a screen of a different size; the tree's action works on any device.
                val ok = nosEscolha[chave]?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                if (ok) cliquesFeitos++ else cliquesFalhos++
                ok
            },
            aoTentarDeNovo = { retomar() },
            estadoAoVivo = {
                // the loop's log + which step the engine is on RIGHT NOW. Once the round
                // ends, the reason it stopped comes in too, which is what explains
                // "stopped early".
                buildList {
                    addAll(laco)
                    val m = motor
                    if (m != null) {
                        add("current step: ${m.proximoPasso()::class.simpleName}")
                        add("scroll rounds: ${m.voltasDeRolagem}")
                        add("scrolling via: ${if (usandoGesto) "drag gesture" else "tree action"}")
                    } else if (motivoDaParada.isNotBlank()) {
                        add("stopped because: $motivoDaParada")
                    }
                }
            },
        ).also { it.mostrar() }
    }

    /**
     * Fields that ended up as "I don't have this data" turn into a question to the
     * house LLM (brief 245), one at a time, on a single THREAD: the main thread here is
     * the accessibility service's, and networking on it freezes the fill right in front
     * of the person. ⛔ The response is NEVER written to the field through this path: it
     * becomes a card in the panel waiting for a tap. Any failure (no network, timeout,
     * 500, broken JSON) leaves the field exactly as it is. The runCatching here and
     * Llm.avaliar guarantee the round never crashes because of the AI.
     */
    private fun consultarLlm(s: Session) {
        val candidatos = Llm.candidatos(s.registros())
        if (candidatos.isEmpty()) return
        val linhas = Llm.linhasDePerfil(Store.perfilTexto(this), Store.aprendidos(this))
        if (linhas.isEmpty()) return // no profile means no possible anchor; asking would mean asking to invent
        candidatos.forEach { llmDiag[it.campo.chave] = Llm.LlmDiagnostico("consultando", null, null) }
        painel?.atualizar()
        // ONE THREAD PER FIELD, in parallel. MEASURED in his 09/10 test: sequential took
        // ~1.6s per field and the 4th only arrived at 6.5s. He'd close the panel before
        // that, and two fields stayed stuck on "asking" forever. In parallel the last one
        // arrives in ~2s.
        // 🎓 Why a raw Thread and not a pool: it's at most a few fields per round, each
        // with a 20s timeout; an executor would bring a lifecycle to manage with no real
        // gain. Applying the result stays serialized on the main thread's handler, so
        // sugestoesLlm and llmDiag are never touched by two threads at once.
        for (reg in candidatos) {
            val rotulo = reg.rotulo ?: continue
            Thread {
                if (sessao !== s) return@Thread // the session died: stop spending network
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

    /** Final outcome of an AI episode (accepted/edited/discarded), preserving confidence and latency. */
    private fun fecharEpisodioLlm(s: Session, chave: String, desfecho: String) {
        val antes = llmDiag[chave]
        llmDiag[chave] = Llm.LlmDiagnostico(desfecho, antes?.confianca, antes?.latenciaMs)
        gravarDiagnostico(s, voltas = null, aprendidos = 0)
    }

    /** Window event in the MIDDLE of the loop: aborts with the right reason before closing the session. */
    private fun abortarRodadaSeAtiva(motivo: String) {
        if (motor == null) return
        handler.removeCallbacksAndMessages(null)
        encerrarRodada(motivo)
    }

    /** The user typed (or dictated via keyboard: same thing to the app). */
    private fun aoMudarTexto(event: AccessibilityEvent) {
        val s = sessao ?: return
        val no = event.source ?: return
        if (no.isPassword) return // hard lock: password isn't read even here
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

    /** The screen changed = end of that session (multi-page included). Keyboard and the app itself don't count. */
    private fun aoMudarJanela(event: AccessibilityEvent) {
        if (sessao == null) return
        if (!WindowRule.encerraSessao(event.packageName?.toString(), event.className?.toString(), packageName)) return
        abortarRodadaSeAtiva("the screen changed to another app mid-round")
        painel?.fechar() // the panel talks about a screen that's now gone
        // ⛔ THE BUBBLE DOES NOT CLOSE HERE. His rule, 09/12: "the bubble should only close
        // when I throw it in the trash". Closing the bubble on window change forced the
        // person to hit the accessibility button again on every back-and-forth, and that's
        // what made it look like tapping outside the panel killed the agent.
        fecharSessao()
    }

    private fun fecharSessao() {
        val s = sessao ?: return
        sessao = null
        val recibo = s.fechar() ?: return // session with no fills or learning: receipt doesn't fire
        if (recibo.aprendidos.isNotEmpty()) Store.adicionarAprendidos(this, recibo.aprendidos)
        Store.gravarRecibo(this, recibo)
        // rewrites the round's diagnostic with the FINAL state (panel and learned entries included)
        if (arquivoDiagnostico != null) gravarDiagnostico(s, voltas = null, aprendidos = recibo.aprendidos.size)
        Notices.recibo(this, recibo)
    }

    /**
     * Records even with ZERO fields: "saw 400 nodes and none was a text field" is exactly
     * the data that replaces a screenshot when the scan fails on a screen full of fields.
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
        // Bench probe: test build only, one file per upload, fails silently. Comes AFTER
        // the local write on purpose. The file on the device is the source of truth, the
        // probe is the convenience copy; if the order were reversed, a slow network would
        // delay what's already guaranteed.
        Bench.enviar(json, System.currentTimeMillis())
    }
}
