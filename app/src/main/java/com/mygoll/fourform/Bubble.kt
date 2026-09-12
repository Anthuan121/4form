package com.mygoll.fourform

import com.mygoll.fourform.scan.Choice
import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A BOLHA: a interface ativa do agente (desenho ditado por ele em 12/09, no ônibus a caminho
 * do hackathon). A tese é dele e define o app: "o nosso app funciona nas sombras, então a
 * bolinha vai ser o agente, é a nossa interface com o cliente".
 *
 * Por que uma janela PRÓPRIA e pequena, e não o painel de tela cheia: a janela acompanha o
 * tamanho da bolha, então o resto da tela continua do navegador. O painel antigo cobria tudo
 * e parava a pessoa para perguntar, que foi a reclamação dele às 06:55 do mesmo dia: "se ele
 * não vai marcar, segue pro próximo campo, não precisa me travar ali, isso trava".
 *
 * Continua em TYPE_ACCESSIBILITY_OVERLAY, a janela que o próprio serviço de acessibilidade
 * desenha SEM permissão nova. ⛔ SYSTEM_ALERT_WINDOW não é pedido em lugar nenhum deste app,
 * e isso é trava de produto, não detalhe: pedir "desenhar sobre outros apps" é o atrito que
 * derruba instalação.
 *
 * Posição: canto SUPERIOR DIREITO com respiro da borda, FIXA, decisão dele. Ele recusou a
 * bolha em cima do campo (taparia o preenchimento, que é a prova de que funcionou) e recusou
 * também acompanhar a altura do campo. Canto fixo é previsível: sempre se sabe onde olhar.
 */
class Bubble(
    private val service: AccessibilityService,
    private val aoTocar: () -> Unit,
    private val aoDescartar: () -> Unit,
) {

    enum class Estado { OCIOSO, INTERPRETANDO, PREENCHENDO, PENDENCIA, TERMINOU, ERRO }

    private val wm = service.getSystemService(WindowManager::class.java)
    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density).toInt()
    private fun cor(id: Int): Int = service.resources.getColor(id, null)

    private var vista: Vista? = null
    private var lixo: Lixeira? = null
    private var lp: WindowManager.LayoutParams? = null

    /** Tamanho de alvo de toque do Android: 60dp é confortável sem virar obstáculo. */
    private val diametro get() = dp(60)
    private val respiro get() = dp(14)

    fun mostrar() {
        if (vista != null) return
        val v = Vista()
        val p = WindowManager.LayoutParams(
            diametro, diametro,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_FOCUSABLE é o que faz o teclado e o toque continuarem indo para o
            // navegador embaixo. Sem isso a bolha rouba o foco do formulário.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = service.resources.displayMetrics.widthPixels - diametro - respiro
            y = respiro + dp(36) // abaixo da barra de status
        }
        lp = p
        vista = v
        runCatching { wm.addView(v, p) }
    }

    fun estado(e: Estado, pendentes: Int = 0) {
        val v = vista ?: return
        v.estado = e
        v.pendentes = pendentes
        v.invalidate()
    }

    fun fechar() {
        vista?.let { v -> runCatching { wm.removeView(v) } }
        vista = null
        lixo?.esconder()
        lixo = null
    }

    // ── arraste e descarte ────────────────────────────────────────────────────────────

    private var baseX = 0
    private var baseY = 0
    private var toqueX = 0f
    private var toqueY = 0f
    private var arrastando = false

    private fun aoTocarNaVista(e: MotionEvent): Boolean {
        val p = lp ?: return false
        val v = vista ?: return false
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                baseX = p.x; baseY = p.y
                toqueX = e.rawX; toqueY = e.rawY
                arrastando = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - toqueX
                val dy = e.rawY - toqueY
                // só vira arraste depois do limiar do sistema; sem isso um toque com o
                // dedo trêmulo vira arraste e a pessoa nunca consegue ABRIR a bolha.
                if (!arrastando && hypot(abs(dx), abs(dy)) < dp(12)) return true
                arrastando = true
                if (lixo == null) lixo = Lixeira().also { it.mostrar() }
                p.x = baseX + dx.toInt()
                p.y = baseY + dy.toInt()
                runCatching { wm.updateViewLayout(v, p) }
                lixo?.aproximando(sobreOLixo(p))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!arrastando) { aoTocar(); return true }
                if (sobreOLixo(p)) { estourar(); return true }
                lixo?.esconder(); lixo = null
                voltarAoCanto()
                return true
            }
        }
        return false
    }

    private fun sobreOLixo(p: WindowManager.LayoutParams): Boolean {
        val tela = service.resources.displayMetrics
        val centroX = p.x + diametro / 2f
        val centroY = p.y + diametro / 2f
        val alvoX = tela.widthPixels / 2f
        val alvoY = tela.heightPixels - dp(90).toFloat()
        return hypot(centroX - alvoX, centroY - alvoY) < dp(80)
    }

    private fun voltarAoCanto() {
        val p = lp ?: return
        val v = vista ?: return
        val destinoX = service.resources.displayMetrics.widthPixels - diametro - respiro
        val destinoY = respiro + dp(36)
        val deX = p.x; val deY = p.y
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                p.x = (deX + (destinoX - deX) * t).toInt()
                p.y = (deY + (destinoY - deY) * t).toInt()
                runCatching { wm.updateViewLayout(v, p) }
            }
            start()
        }
    }

    /**
     * ESTOURA, não quebra. Choice dele em 12/09, vendo as duas animações lado a lado.
     * O argumento que venceu: vidro quebrado diz "morreu, deu errado"; bolha estourando diz
     * "o trabalho desta tela acabou, e o agente não morreu". Encerrar a rodada ⛔ não é
     * desligar o serviço, e a animação precisa contar isso certo.
     */
    private fun estourar() {
        val v = vista ?: return
        lixo?.esconder(); lixo = null
        v.estourando = 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            addUpdateListener { a ->
                v.estourando = a.animatedValue as Float
                v.invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    fechar()
                    aoDescartar()
                }
            })
            start()
        }
    }

    // ── desenho ───────────────────────────────────────────────────────────────────────

    private inner class Vista : View(service) {
        var estado = Estado.INTERPRETANDO
        var pendentes = 0
        var estourando = -1f // <0 = não está estourando

        private val tinta = Paint(Paint.ANTI_ALIAS_FLAG)
        private var fase = 0f

        init {
            setOnTouchListener { _, e -> aoTocarNaVista(e) }
            // um único laço de animação para todos os estados: mais barato que um
            // ValueAnimator por estado, e a fase única mantém tudo em sincronia.
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1400
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { a -> fase = a.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val r = width / 2f
            val cx = r
            val cy = r
            if (estourando >= 0f) { desenharEstouro(canvas, cx, cy, r); return }

            // miolo: a superfície escura, sempre. É o corpo do agente.
            tinta.style = Paint.Style.FILL
            tinta.color = cor(R.color.superficie)
            canvas.drawCircle(cx, cy, r - dp(3), tinta)

            val acento = when (estado) {
                Estado.PENDENCIA -> cor(R.color.aviso)
                Estado.TERMINOU -> cor(R.color.sucesso)
                // brasa só aqui: erro é a ÚNICA coisa que interrompe a família do ouro.
                // Se vermelho aparecesse em pendência também, ele deixaria de significar
                // "algo deu errado" e viraria decoração.
                Estado.ERRO -> cor(R.color.erro)
                else -> cor(R.color.primaria)
            }

            // ANEL: é ele que carrega o estado. Ele muda por MOVIMENTO e INTENSIDADE, não
            // por matiz, porque a paleta dele tem 2 cores dominantes e semáforo colorido
            // brigaria com "Cosmic Luxury". Ver notas do desenho de 12/09.
            tinta.style = Paint.Style.STROKE
            tinta.strokeWidth = dp(3).toFloat()
            tinta.strokeCap = Paint.Cap.ROUND
            val caixa = RectF(dp(3).toFloat(), dp(3).toFloat(), width - dp(3f).toFloat(), height - dp(3).toFloat())
            when (estado) {
                Estado.OCIOSO -> {
                    tinta.color = comAlfa(acento, 0.35f + 0.15f * sin(fase * 2 * Math.PI).toFloat())
                    canvas.drawCircle(cx, cy, r - dp(3), tinta)
                }
                Estado.INTERPRETANDO -> {
                    // arco girando: está LENDO a tela
                    tinta.color = comAlfa(acento, 0.20f)
                    canvas.drawCircle(cx, cy, r - dp(3), tinta)
                    tinta.color = acento
                    canvas.drawArc(caixa, fase * 360f, 90f, false, tinta)
                }
                Estado.PREENCHENDO -> {
                    // anel cheio pulsando: está AGINDO no campo
                    tinta.color = comAlfa(acento, 0.75f + 0.25f * sin(fase * 4 * Math.PI).toFloat())
                    canvas.drawCircle(cx, cy, r - dp(3), tinta)
                }
                Estado.PENDENCIA, Estado.TERMINOU -> {
                    tinta.color = acento
                    canvas.drawCircle(cx, cy, r - dp(3), tinta)
                }
                Estado.ERRO -> {
                    // pulso LENTO, ⛔ não piscar rápido: precisa chamar atenção sem virar
                    // alarme. A bolha vive sobre o formulário que a pessoa está usando.
                    tinta.color = comAlfa(acento, 0.6f + 0.4f * sin(fase * 2 * Math.PI).toFloat())
                    canvas.drawCircle(cx, cy, r - dp(3), tinta)
                }
            }

            desenharMiolo(canvas, cx, cy, acento)
            if (pendentes > 0 && (estado == Estado.PENDENCIA || estado == Estado.ERRO)) {
                desenharBadge(canvas, pendentes, estado == Estado.ERRO)
            }
        }

        /** O miolo diz a AÇÃO; o anel diz o estado. Dois canais, nenhum depende de cor. */
        private fun desenharMiolo(canvas: Canvas, cx: Float, cy: Float, acento: Int) {
            tinta.style = Paint.Style.FILL
            tinta.color = acento
            when (estado) {
                Estado.OCIOSO -> canvas.drawCircle(cx, cy, dp(4).toFloat(), tinta)
                Estado.INTERPRETANDO -> {
                    // traço que varre lateralmente, como olho lendo uma linha
                    val desloc = sin(fase * 2 * Math.PI).toFloat() * dp(6)
                    canvas.drawRoundRect(
                        cx - dp(7) + desloc, cy - dp(1.5f).toFloat(),
                        cx + dp(7) + desloc, cy + dp(1.5f).toFloat(),
                        dp(2).toFloat(), dp(2).toFloat(), tinta,
                    )
                }
                Estado.PREENCHENDO -> {
                    // três barrinhas surgindo em sequência: texto entrando no campo
                    val larguras = intArrayOf(dp(14), dp(10), dp(7))
                    for (i in larguras.indices) {
                        val vez = ((fase * 3f).toInt() % 3)
                        tinta.alpha = if (i <= vez) 255 else 60
                        val y = cy - dp(6) + i * dp(6).toFloat()
                        canvas.drawRoundRect(
                            cx - larguras[i] / 2f, y - dp(1.5f).toFloat(),
                            cx + larguras[i] / 2f, y + dp(1.5f).toFloat(),
                            dp(2).toFloat(), dp(2).toFloat(), tinta,
                        )
                    }
                    tinta.alpha = 255
                }
                Estado.PENDENCIA -> canvas.drawCircle(cx, cy, dp(4).toFloat(), tinta)
                Estado.ERRO -> {
                    // "!" desenhado: sobrevive sem fonte e em qualquer tamanho de tela
                    canvas.drawRoundRect(
                        cx - dp(1.5f).toFloat(), cy - dp(8).toFloat(),
                        cx + dp(1.5f).toFloat(), cy + dp(2).toFloat(),
                        dp(2).toFloat(), dp(2).toFloat(), tinta,
                    )
                    canvas.drawCircle(cx, cy + dp(6).toFloat(), dp(2).toFloat(), tinta)
                }
                Estado.TERMINOU -> {
                    tinta.style = Paint.Style.STROKE
                    tinta.strokeWidth = dp(2.5f).toFloat()
                    val c = android.graphics.Path().apply {
                        moveTo(cx - dp(7), cy)
                        lineTo(cx - dp(2), cy + dp(5))
                        lineTo(cx + dp(7), cy - dp(5))
                    }
                    canvas.drawPath(c, tinta)
                }
            }
        }

        /**
         * Badge DENTRO da borda, de propósito: a bolha vive encostada no canto da tela, e
         * badge pendurado para fora seria cortado pela borda do display.
         */
        private fun desenharBadge(canvas: Canvas, n: Int, erro: Boolean) {
            val bx = dp(13).toFloat()
            val by = height - dp(13).toFloat()
            tinta.style = Paint.Style.FILL
            tinta.color = if (erro) cor(R.color.erro) else cor(R.color.aviso)
            canvas.drawCircle(bx, by, dp(9).toFloat(), tinta)
            tinta.color = cor(R.color.superficie)
            tinta.textSize = dp(11).toFloat()
            tinta.textAlign = Paint.Align.CENTER
            canvas.drawText(n.toString(), bx, by + dp(4), tinta)
        }

        private fun desenharEstouro(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val t = estourando
            tinta.color = cor(R.color.primaria)
            // onda expandindo e sumindo
            tinta.style = Paint.Style.STROKE
            tinta.strokeWidth = dp(3) * (1f - t)
            tinta.alpha = ((1f - t) * 255).toInt()
            canvas.drawCircle(cx, cy, r * (0.6f + t * 0.9f), tinta)
            // respingos: 6 pingos saindo do centro
            tinta.style = Paint.Style.FILL
            for (i in 0 until 6) {
                val ang = i * 60.0 * Math.PI / 180.0
                val d = r * (0.3f + t * 1.1f)
                val raio = dp(4) * (1f - t)
                canvas.drawCircle(
                    cx + (cos(ang) * d).toFloat(),
                    cy + (sin(ang) * d).toFloat(),
                    raio.coerceAtLeast(0f), tinta,
                )
            }
            tinta.alpha = 255
        }

        private fun comAlfa(c: Int, a: Float): Int =
            Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

        private fun dp(v: Float): Int = (v * service.resources.displayMetrics.density).toInt()
    }

    /**
     * O alvo de descarte: padrão conhecido do Android (a lixeira que sobe do rodapé no
     * arraste). Só existe enquanto a bolha está sendo arrastada.
     */
    private inner class Lixeira {
        private var vista: View? = null
        private var perto = false

        fun mostrar() {
            val v = object : View(service) {
                private val tinta = Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(canvas: Canvas) {
                    val cx = width / 2f
                    val cy = height / 2f
                    tinta.style = Paint.Style.FILL
                    tinta.color = if (perto) cor(R.color.erro) else cor(R.color.superficie_recipiente_alto)
                    val lado = (if (perto) dp(30) else dp(26)).toFloat()
                    // quadrado de canto curto: a escala de raio vale para a lixeira também
                    canvas.drawRoundRect(
                        cx - lado, cy - lado, cx + lado, cy + lado,
                        dp(8).toFloat(), dp(8).toFloat(), tinta,
                    )
                    tinta.color = cor(R.color.texto)
                    tinta.style = Paint.Style.STROKE
                    tinta.strokeWidth = dp(2).toFloat()
                    canvas.drawRect(cx - dp(9), cy - dp(6).toFloat(), cx + dp(9), cy + dp(12).toFloat(), tinta)
                    canvas.drawLine(cx - dp(13).toFloat(), cy - dp(6).toFloat(), cx + dp(13).toFloat(), cy - dp(6).toFloat(), tinta)
                }
            }
            val p = WindowManager.LayoutParams(
                dp(96), dp(96),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = service.resources.displayMetrics.widthPixels / 2 - dp(48)
                y = service.resources.displayMetrics.heightPixels - dp(90) - dp(48)
            }
            vista = v
            runCatching { wm.addView(v, p) }
        }

        fun aproximando(sim: Boolean) {
            if (perto == sim) return
            perto = sim
            vista?.invalidate()
        }

        fun esconder() {
            vista?.let { runCatching { wm.removeView(it) } }
            vista = null
        }
    }
}
