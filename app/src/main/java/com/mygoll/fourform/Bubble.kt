package com.mygoll.fourform

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * THE BUBBLE: the agent's active interface (design dictated by him on 09/12, on the bus on
 * the way to the hackathon). The thesis is his and it defines the app: "our app works in the
 * shadows, so the bubble is going to be the agent, it's our interface with the customer".
 *
 * Why its OWN small window, and not the full-screen panel: the window matches the size of the
 * bubble, so the rest of the screen stays the browser's. The old panel covered everything
 * and stopped the person to ask a question, which was his complaint at 06:55 the same day:
 * "if it's not going to check it, move on to the next field, no need to block me there, that
 * gets in the way".
 *
 * Still TYPE_ACCESSIBILITY_OVERLAY, the window that the accessibility service itself draws
 * WITHOUT a new permission. ⛔ SYSTEM_ALERT_WINDOW is not requested anywhere in this app,
 * and that's a product constraint, not a detail: asking to "draw over other apps" is the
 * friction that kills installs.
 *
 * Position: TOP RIGHT corner with breathing room from the edge, FIXED, his decision. He
 * rejected the bubble sitting on top of the field (it would cover the fill, which is the
 * proof that it worked) and also rejected tracking the field's height. A fixed corner is
 * predictable: you always know where to look.
 *
 * MOTION AND SURFACE follow the HTML he approved on 09/12 (preenche-bolha-estados): the
 * outer ring is the state channel, the core is the action channel, the body has a top-left
 * sheen instead of flat black, and the idle breathing cycle is 3.4s. Every rhythm here is
 * an integer multiple of that master cycle so the loop wraps without a visible jump.
 */
class Bubble(
    private val service: AccessibilityService,
    private val aoTocar: () -> Unit,
    private val aoDescartar: () -> Unit,
) {

    enum class Estado { OCIOSO, INTERPRETANDO, PREENCHENDO, PENDENCIA, TERMINOU, ERRO }

    private val wm = service.getSystemService(WindowManager::class.java)
    private val densidade get() = service.resources.displayMetrics.density
    private fun dp(v: Int): Int = (v * densidade).toInt()
    private fun cor(id: Int): Int = service.resources.getColor(id, null)

    private var vista: Vista? = null
    private var lixo: Lixeira? = null
    private var lp: WindowManager.LayoutParams? = null

    /** Android touch target size: 60dp is comfortable without becoming an obstacle. */
    private val diametro get() = dp(60)
    private val respiro get() = dp(14)

    fun mostrar() {
        if (vista != null) return
        val v = Vista()
        val p = WindowManager.LayoutParams(
            diametro, diametro,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_FOCUSABLE is what keeps the keyboard and touches going to the
            // browser underneath. Without it the bubble steals the form's focus.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = service.resources.displayMetrics.widthPixels - diametro - respiro
            y = respiro + dp(36) // below the status bar
        }
        lp = p
        vista = v
        runCatching { wm.addView(v, p) }
    }

    fun estado(e: Estado, pendentes: Int = 0) {
        vista?.trocarEstado(e, pendentes)
    }

    fun fechar() {
        vista?.let { v -> runCatching { wm.removeView(v) } }
        vista = null
        lixo?.esconder()
        lixo = null
    }

    // ── drag and discard ────────────────────────────────────────────────────────────

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
                // only becomes a drag past the system threshold; without this a tap with a
                // shaky finger turns into a drag and the person can never OPEN the bubble.
                if (!arrastando && hypot(abs(dx), abs(dy)) < dp(12)) return true
                arrastando = true
                v.emArrasto = true
                if (lixo == null) lixo = Lixeira().also { it.mostrar() }
                p.x = baseX + dx.toInt()
                p.y = baseY + dy.toInt()
                runCatching { wm.updateViewLayout(v, p) }
                lixo?.aproximando(sobreOLixo(p))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.emArrasto = false
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
     * POPS, doesn't shatter. His choice on 09/12, watching both animations side by side.
     * The argument that won: broken glass says "it died, something went wrong"; a popping
     * bubble says "this screen's work is done, and the agent didn't die". Ending the round
     * ⛔ is not turning off the service, and the animation needs to tell that story right.
     *
     * The window GROWS before the pop: the wave expands to ~2.3x the bubble and the
     * droplets fly up to ~56dp out, and clipping all of that to the 60dp bubble box is
     * exactly what made the first Kotlin version look poor next to the approved HTML.
     */
    private fun estourar() {
        val v = vista ?: return
        val p = lp ?: return
        lixo?.esconder(); lixo = null
        p.width = diametro * 3
        p.height = diametro * 3
        p.x -= diametro
        p.y -= diametro
        runCatching { wm.updateViewLayout(v, p) }
        v.emArrasto = false
        v.estourando = 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            // linear on purpose: each piece (squash, wave, droplets) shapes its own
            // ease-out curve, like the HTML keyframes do.
            interpolator = LinearInterpolator()
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

    // ── drawing ───────────────────────────────────────────────────────────────────────

    private inner class Vista : View(service) {
        var estado = Estado.INTERPRETANDO
        var pendentes = 0
        var estourando = -1f // <0 = not popping
        var emArrasto = false
            set(value) { field = value; invalidate() }

        private val tinta = Paint(Paint.ANTI_ALIAS_FLAG)
        private var fase = 0f
        // one-shot progress for state entrances (badge pop, check drawing itself)
        private var entrada = 1f
        private var entradaAnim: ValueAnimator? = null
        private var corpoShader: RadialGradient? = null

        init {
            setOnTouchListener { _, e -> aoTocarNaVista(e) }
            // a single animation loop for every state: cheaper than one ValueAnimator
            // per state, and the single phase keeps everything in sync. 3400ms is the
            // idle breathing cycle from the approved HTML; the faster rhythms below are
            // integer multiples of it, so the loop wraps with no visible jump.
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 3400
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { a -> fase = a.animatedValue as Float; invalidate() }
                start()
            }
        }

        fun trocarEstado(e: Estado, pend: Int) {
            val mudou = e != estado
            estado = e
            pendentes = pend
            // idle bubble sits at 82% opacity, the HTML's rule: you can read the form
            // through its presence. Working states are fully opaque.
            alpha = if (e == Estado.OCIOSO) 0.82f else 1f
            if (mudou && (e == Estado.TERMINOU || e == Estado.PENDENCIA)) {
                entrada = 0f
                entradaAnim?.cancel()
                entradaAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = if (e == Estado.TERMINOU) 500 else 350
                    // badge pops in with an overshoot; the check draws itself, decelerating
                    interpolator =
                        if (e == Estado.TERMINOU) DecelerateInterpolator() else OvershootInterpolator(2f)
                    addUpdateListener { a -> entrada = a.animatedValue as Float; invalidate() }
                    start()
                }
            }
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w <= 0) return
            // top-left sheen over the body, the HTML's radial-gradient: without it the
            // bubble is a flat black hole instead of a soap-film surface.
            corpoShader = RadialGradient(
                w * 0.3f, h * 0.25f, w * 1.2f,
                intArrayOf(cor(R.color.bolha_brilho), cor(R.color.bolha_corpo)),
                floatArrayOf(0f, 0.6f),
                Shader.TileMode.CLAMP,
            )
        }

        /** 0..1 rising and falling once per x cycles of the master loop. */
        private fun meio(ciclos: Float): Float =
            0.5f + 0.5f * sin(fase * ciclos * 2 * Math.PI).toFloat()

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            if (estourando >= 0f) { desenharEstouro(canvas, cx, cy); return }
            val r = width / 2f

            // body: the agent's surface. Sheen shader, never flat black.
            tinta.style = Paint.Style.FILL
            tinta.shader = corpoShader
            canvas.drawCircle(cx, cy, r - dp(4.5f), tinta)
            tinta.shader = null

            val acento = when (estado) {
                Estado.TERMINOU -> cor(R.color.sucesso)
                // ember only here: error is the ONLY thing that interrupts the gold family.
                // If red also showed up for pending items, it would stop meaning
                // "something went wrong" and become decoration.
                Estado.ERRO -> cor(R.color.erro)
                // PENDENCIA stays in the gold family on purpose: in the approved design
                // the bubble RETURNS to idle (work goes on in the other fields) and the
                // amber lives only in the badge. An amber ring would read as alarm.
                else -> cor(R.color.primaria)
            }

            // RING: it's what carries the state. It changes by MOVEMENT and INTENSITY, not
            // by hue, because his palette has 2 dominant colors and a colored traffic light
            // would clash with "Cosmic Luxury". See the 09/12 design notes.
            tinta.style = Paint.Style.STROKE
            tinta.strokeWidth = dp(2).toFloat()
            tinta.strokeCap = Paint.Cap.ROUND
            val rAnel = r - dp(1.5f)
            val caixa = RectF(
                dp(1.5f).toFloat(), dp(1.5f).toFloat(),
                width - dp(1.5f).toFloat(), height - dp(1.5f).toFloat(),
            )
            if (emArrasto) {
                // while held: neutral ring and core in the ink color, like the HTML's
                // drag state. The bubble stops "speaking" while the hand decides.
                tinta.color = comAlfa(cor(R.color.texto), 0.5f)
                canvas.drawCircle(cx, cy, rAnel, tinta)
                tinta.style = Paint.Style.FILL
                tinta.color = cor(R.color.texto)
                canvas.drawCircle(cx, cy, dp(4).toFloat(), tinta)
                return
            }
            when (estado) {
                Estado.OCIOSO, Estado.PENDENCIA -> {
                    // quiet ring at constant 35%; the breathing lives in the core dot
                    tinta.color = comAlfa(acento, 0.35f)
                    canvas.drawCircle(cx, cy, rAnel, tinta)
                }
                Estado.INTERPRETANDO -> {
                    // only the spinning arc, no base ring: it's READING the screen.
                    // One turn every ~1.13s, close to the HTML's 1.1s.
                    tinta.color = acento
                    canvas.drawArc(caixa, fase * 3f * 360f, 80f, false, tinta)
                }
                Estado.PREENCHENDO -> {
                    // full ring pulsing (~1.13s, HTML: 1.2s): it's ACTING on the field
                    tinta.color = comAlfa(acento, 0.55f + 0.45f * meio(3f))
                    canvas.drawCircle(cx, cy, rAnel, tinta)
                }
                Estado.TERMINOU -> {
                    tinta.color = comAlfa(acento, 0.85f)
                    canvas.drawCircle(cx, cy, rAnel, tinta)
                }
                Estado.ERRO -> {
                    // SLOW pulse, ⛔ no fast blinking: needs to grab attention without
                    // becoming an alarm. The bubble sits over the form the person is using.
                    tinta.color = comAlfa(acento, 0.6f + 0.4f * meio(1f))
                    canvas.drawCircle(cx, cy, rAnel, tinta)
                }
            }

            desenharMiolo(canvas, cx, cy, acento)
            if (pendentes > 0 && (estado == Estado.PENDENCIA || estado == Estado.ERRO)) {
                desenharBadge(canvas, pendentes, estado == Estado.ERRO)
            }
        }

        /** The core says the ACTION; the ring says the state. Two channels, neither relies on color. */
        private fun desenharMiolo(canvas: Canvas, cx: Float, cy: Float, acento: Int) {
            tinta.style = Paint.Style.FILL
            tinta.color = acento
            when (estado) {
                Estado.OCIOSO, Estado.PENDENCIA -> {
                    // the dot breathes (0.45..0.95 over 3.4s), the HTML's idle heartbeat
                    tinta.color = comAlfa(acento, 0.45f + 0.5f * meio(1f))
                    canvas.drawCircle(cx, cy, dp(4).toFloat(), tinta)
                }
                Estado.INTERPRETANDO -> {
                    // a stroke sweeping sideways, like an eye reading a line
                    // (~1.13s each way, the HTML's 1.1s alternate)
                    val desloc = sin(fase * 1.5f * 2 * Math.PI).toFloat() * dp(7)
                    canvas.drawRoundRect(
                        cx - dp(7) + desloc, cy - dp(1.5f).toFloat(),
                        cx + dp(7) + desloc, cy + dp(1.5f).toFloat(),
                        dp(2).toFloat(), dp(2).toFloat(), tinta,
                    )
                }
                Estado.PREENCHENDO -> desenharDigitacao(canvas, cx, cy)
                Estado.ERRO -> {
                    // hand-drawn "!": survives without a font and at any screen size
                    canvas.drawRoundRect(
                        cx - dp(1.5f).toFloat(), cy - dp(8).toFloat(),
                        cx + dp(1.5f).toFloat(), cy + dp(2).toFloat(),
                        dp(2).toFloat(), dp(2).toFloat(), tinta,
                    )
                    canvas.drawCircle(cx, cy + dp(6).toFloat(), dp(2).toFloat(), tinta)
                }
                Estado.TERMINOU -> {
                    // the check DRAWS itself (entrada 0..1), like the HTML's stroke-dashoffset
                    tinta.style = Paint.Style.STROKE
                    tinta.strokeWidth = dp(2.5f).toFloat()
                    val c = Path().apply {
                        moveTo(cx - dp(7), cy)
                        lineTo(cx - dp(2), cy + dp(5))
                        lineTo(cx + dp(7), cy - dp(5))
                    }
                    if (entrada >= 1f) {
                        canvas.drawPath(c, tinta)
                    } else {
                        val medida = PathMeasure(c, false)
                        val parcial = Path()
                        var fim = medida.length * entrada.coerceIn(0f, 1f)
                        medida.getSegment(0f, fim, parcial, true)
                        // the check is two segments; walk into the second one if needed
                        if (medida.nextContour()) {
                            fim -= medida.length
                            if (fim > 0f) medida.getSegment(0f, fim, parcial, true)
                        }
                        canvas.drawPath(parcial, tinta)
                    }
                }
            }
        }

        /**
         * Typing miniature, faithful to the HTML: four ink bars of uneven heights coming
         * up in a staggered sequence next to a blinking gold caret. Text being typed,
         * seen from far away.
         */
        private fun desenharDigitacao(canvas: Canvas, cx: Float, cy: Float) {
            val alturas = floatArrayOf(10f, 14f, 8f, 12f)
            val larguraBarra = dp(3).toFloat()
            val passo = dp(6).toFloat() // bar plus gap
            val base = cy + dp(7).toFloat() // bars grow upward from a shared baseline
            var x = cx - dp(13).toFloat()
            val ciclo = (fase * 3f) % 1f // ~1.13s per typing cycle (HTML: 1s)
            tinta.style = Paint.Style.FILL
            for (i in alturas.indices) {
                // each bar turns on later than the previous (0.16 stagger) and holds
                val proprio = (ciclo - 0.16f * i + 1f) % 1f
                tinta.color = cor(R.color.texto)
                tinta.alpha = if (proprio > 0.6f) 230 else 0
                canvas.drawRoundRect(
                    x, base - alturas[i] * densidade, x + larguraBarra, base,
                    dp(2).toFloat(), dp(2).toFloat(), tinta,
                )
                x += passo
            }
            // gold caret blinking every ~0.57s (HTML: 0.55s)
            tinta.color = cor(R.color.primaria)
            tinta.alpha = if ((fase * 6f) % 1f < 0.5f) 255 else 0
            canvas.drawRect(x, base - dp(15).toFloat(), x + dp(2).toFloat(), base, tinta)
            tinta.alpha = 255
        }

        /**
         * Badge INSIDE the edge, on purpose: the bubble sits flush against the screen
         * corner, and a badge hanging outside would get clipped by the display edge.
         * Enters once with an overshoot pop, then only a subtle reminder pulse once per
         * master loop: the HTML is explicit that the badge must not keep vibrating.
         */
        private fun desenharBadge(canvas: Canvas, n: Int, erro: Boolean) {
            val bx = dp(13).toFloat()
            val by = height - dp(13).toFloat()
            val lembra = if (fase < 0.06f) 1f + 0.18f * (1f - abs(fase - 0.03f) / 0.03f) else 1f
            val escala = entrada.coerceAtLeast(0f) * lembra
            canvas.save()
            canvas.scale(escala, escala, bx, by)
            tinta.style = Paint.Style.FILL
            tinta.color = if (erro) cor(R.color.erro) else cor(R.color.aviso)
            canvas.drawCircle(bx, by, dp(10).toFloat(), tinta)
            // a surface-colored ring separates the badge from the body, like the HTML border
            tinta.style = Paint.Style.STROKE
            tinta.strokeWidth = dp(2).toFloat()
            tinta.color = cor(R.color.superficie)
            canvas.drawCircle(bx, by, dp(10).toFloat(), tinta)
            tinta.style = Paint.Style.FILL
            tinta.textSize = dp(11).toFloat()
            tinta.textAlign = Paint.Align.CENTER
            tinta.isFakeBoldText = true
            canvas.drawText(n.toString(), bx, by + dp(4), tinta)
            tinta.isFakeBoldText = false
            canvas.restore()
        }

        /**
         * The pop, in three voices like the approved HTML: the body squashes and hands
         * over, a soap-film wave expands to ~2.3x and fades, and six gold droplets fly
         * out on the HTML's own trajectories: mostly up and sideways, pulled down by a
         * touch of gravity at the end. ⛔ Not a uniform hexagon: uniform reads mechanical.
         */
        private fun desenharEstouro(canvas: Canvas, cx: Float, cy: Float) {
            val t = estourando
            val rb = diametro / 2f // the bubble's own radius; the window grew for the pop
            val ouro = cor(R.color.primaria)

            // 1 · the body squashes on impact and vanishes fast
            if (t < 0.12f) {
                tinta.style = Paint.Style.FILL
                tinta.color = cor(R.color.bolha_corpo)
                tinta.alpha = ((1f - t / 0.12f) * 255).toInt()
                canvas.save()
                canvas.scale(1.08f, 0.72f, cx, cy)
                canvas.drawCircle(cx, cy, rb - dp(4.5f), tinta)
                canvas.restore()
            }

            // 2 · the wave: ease-out expansion from 0.7x to 2.3x while fading
            val tw = 1f - (1f - t) * (1f - t)
            tinta.style = Paint.Style.STROKE
            tinta.strokeWidth = dp(2).toFloat()
            tinta.color = ouro
            tinta.alpha = (0.9f * (1f - tw) * 255).toInt()
            canvas.drawCircle(cx, cy, rb * (0.7f + 1.6f * tw), tinta)

            // 3 · the droplets: the six target vectors from the HTML, in dp
            val alvos = arrayOf(
                44f to -30f, -40f to -36f, 56f to 8f,
                -56f to 12f, 16f to -52f, -14f to -48f,
            )
            val td = 1f - (1f - t) * (1f - t) * (1f - t) // stronger ease-out for flight
            val surgimento = (t / 0.08f).coerceAtMost(1f) // quick fade-in right after impact
            tinta.style = Paint.Style.FILL
            tinta.color = ouro
            for ((dxDp, dyDp) in alvos) {
                val px = cx + dxDp * densidade * td
                val py = cy + dyDp * densidade * td + 14f * densidade * t * t // gravity
                val raio = 3.5f * densidade * (1f - 0.7f * td)
                tinta.alpha = (surgimento * (1f - td) * 255).toInt()
                canvas.drawCircle(px, py, raio.coerceAtLeast(0f), tinta)
            }
            tinta.alpha = 255
        }

        private fun comAlfa(c: Int, a: Float): Int =
            Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

        private fun dp(v: Float): Int = (v * densidade).toInt()
    }

    /**
     * The discard target: a familiar Android pattern (the trash can that rises from the
     * bottom while dragging). Only exists while the bubble is being dragged.
     * Faithful to the HTML: a translucent well with a DASHED border, not a solid block;
     * near the target it grows and turns ember (drop here ends the round).
     */
    private inner class Lixeira {
        private var vista: View? = null
        private var perto = false

        fun mostrar() {
            val v = object : View(service) {
                private val tinta = Paint(Paint.ANTI_ALIAS_FLAG)
                private val tracejado = DashPathEffect(
                    floatArrayOf(6f * densidade, 5f * densidade), 0f,
                )

                override fun onDraw(canvas: Canvas) {
                    val cx = width / 2f
                    val cy = height / 2f
                    val meio = (if (perto) dp(35) else dp(29)).toFloat()
                    val caixa = RectF(cx - meio, cy - meio, cx + meio, cy + meio)
                    val raio = dp(8).toFloat() // short-corner square: the radius scale applies here too
                    // translucent well
                    tinta.pathEffect = null
                    tinta.style = Paint.Style.FILL
                    tinta.color =
                        if (perto) comAlfa(cor(R.color.erro), 0.15f)
                        else Color.argb(90, 0, 0, 0)
                    canvas.drawRoundRect(caixa, raio, raio, tinta)
                    // dashed border
                    tinta.style = Paint.Style.STROKE
                    tinta.strokeWidth = 1.5f * densidade
                    tinta.pathEffect = tracejado
                    tinta.color =
                        if (perto) cor(R.color.erro)
                        else comAlfa(cor(R.color.texto), 0.4f)
                    canvas.drawRoundRect(caixa, raio, raio, tinta)
                    tinta.pathEffect = null
                    // the glyph: lid with handle, body, two ribs
                    tinta.color = if (perto) cor(R.color.erro) else cor(R.color.texto_secundario)
                    tinta.strokeWidth = dp(2).toFloat()
                    tinta.strokeCap = Paint.Cap.ROUND
                    val topo = cy - dp(7).toFloat()
                    canvas.drawLine(cx - dp(12), topo, cx + dp(12), topo, tinta)
                    canvas.drawRect(cx - dp(5), cy - dp(11).toFloat(), cx + dp(5), topo, tinta)
                    canvas.drawRect(cx - dp(9), topo, cx + dp(9), cy + dp(11).toFloat(), tinta)
                    canvas.drawLine(cx - dp(4), cy - dp(3).toFloat(), cx - dp(4), cy + dp(7).toFloat(), tinta)
                    canvas.drawLine(cx + dp(4), cy - dp(3).toFloat(), cx + dp(4), cy + dp(7).toFloat(), tinta)
                }

                private fun comAlfa(c: Int, a: Float): Int =
                    Color.argb((a * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
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
