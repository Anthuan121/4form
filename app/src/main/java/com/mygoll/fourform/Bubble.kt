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
     * POPS, doesn't shatter. His choice on 09/12, watching both animations side by side.
     * The argument that won: broken glass says "it died, something went wrong"; a popping
     * bubble says "this screen's work is done, and the agent didn't die". Ending the round
     * ⛔ is not turning off the service, and the animation needs to tell that story right.
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

    // ── drawing ───────────────────────────────────────────────────────────────────────

    private inner class Vista : View(service) {
        var estado = Estado.INTERPRETANDO
        var pendentes = 0
        var estourando = -1f // <0 = not popping

        private val tinta = Paint(Paint.ANTI_ALIAS_FLAG)
        private var fase = 0f

        init {
            setOnTouchListener { _, e -> aoTocarNaVista(e) }
            // a single animation loop for every state: cheaper than one ValueAnimator
            // per state, and the single phase keeps everything in sync.
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

            // core: the dark surface, always. It's the agent's body.
            tinta.style = Paint.Style.FILL
            tinta.color = cor(R.color.superficie)
            canvas.drawCircle(cx, cy, r - dp(3), tinta)

            val acento = when (estado) {
                Estado.PENDENCIA -> cor(R.color.aviso)
                Estado.TERMINOU -> cor(R.color.sucesso)
                // ember only here: error is the ONLY thing that interrupts the gold family.
                // If red also showed up for pending items, it would stop meaning
                // "something went wrong" and become decoration.
                Estado.ERRO -> cor(R.color.erro)
                else -> cor(R.color.primaria)
            }

            // RING: it's what carries the state. It changes by MOVEMENT and INTENSITY, not
            // by hue, because his palette has 2 dominant colors and a colored traffic light
            // would clash with "Cosmic Luxury". See the 09/12 design notes.
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
                    // spinning arc: it's READING the screen
                    tinta.color = comAlfa(acento, 0.20f)
                    canvas.drawCircle(cx, cy, r - dp(3), tinta)
                    tinta.color = acento
                    canvas.drawArc(caixa, fase * 360f, 90f, false, tinta)
                }
                Estado.PREENCHENDO -> {
                    // full ring pulsing: it's ACTING on the field
                    tinta.color = comAlfa(acento, 0.75f + 0.25f * sin(fase * 4 * Math.PI).toFloat())
                    canvas.drawCircle(cx, cy, r - dp(3), tinta)
                }
                Estado.PENDENCIA, Estado.TERMINOU -> {
                    tinta.color = acento
                    canvas.drawCircle(cx, cy, r - dp(3), tinta)
                }
                Estado.ERRO -> {
                    // SLOW pulse, ⛔ no fast blinking: needs to grab attention without
                    // becoming an alarm. The bubble sits over the form the person is using.
                    tinta.color = comAlfa(acento, 0.6f + 0.4f * sin(fase * 2 * Math.PI).toFloat())
                    canvas.drawCircle(cx, cy, r - dp(3), tinta)
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
                Estado.OCIOSO -> canvas.drawCircle(cx, cy, dp(4).toFloat(), tinta)
                Estado.INTERPRETANDO -> {
                    // a stroke sweeping sideways, like an eye reading a line
                    val desloc = sin(fase * 2 * Math.PI).toFloat() * dp(6)
                    canvas.drawRoundRect(
                        cx - dp(7) + desloc, cy - dp(1.5f).toFloat(),
                        cx + dp(7) + desloc, cy + dp(1.5f).toFloat(),
                        dp(2).toFloat(), dp(2).toFloat(), tinta,
                    )
                }
                Estado.PREENCHENDO -> {
                    // three small bars appearing in sequence: text entering the field
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
                    // hand-drawn "!": survives without a font and at any screen size
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
         * Badge INSIDE the edge, on purpose: the bubble sits flush against the screen
         * corner, and a badge hanging outside would get clipped by the display edge.
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
            // wave expanding and fading
            tinta.style = Paint.Style.STROKE
            tinta.strokeWidth = dp(3) * (1f - t)
            tinta.alpha = ((1f - t) * 255).toInt()
            canvas.drawCircle(cx, cy, r * (0.6f + t * 0.9f), tinta)
            // splatter: 6 droplets flying out from the center
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
     * The discard target: a familiar Android pattern (the trash can that rises from the
     * bottom while dragging). Only exists while the bubble is being dragged.
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
                    // short-corner square: the radius scale applies to the trash can too
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
