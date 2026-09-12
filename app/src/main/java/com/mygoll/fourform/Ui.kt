package com.mygoll.fourform

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * UI montada em código, sem layout XML: as telas são listas dinâmicas e o sistema visual
 * de verdade chega pelo brief 241 (trocando só o themes.xml). Aqui, o mínimo legível.
 *
 * 🎓 As extensions são de Context, não de Activity, de propósito: o Panel do overlay é
 * desenhado por um AccessibilityService (que também é Context) e precisa falar a MESMA
 * língua visual das telas. Activity herda de Context, então as 5 Activities usam tudo
 * igual · e o sistema visual continua morando num arquivo só.
 */
object Ui {

    fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    fun Context.coluna(): Pair<ScrollView, LinearLayout> {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val rolo = ScrollView(this).apply { addView(col) }
        return rolo to col
    }

    fun Context.titulo(texto: String): TextView = TextView(this).apply {
        text = texto
        textSize = 26f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(12))
    }

    fun Context.secao(texto: String): TextView = TextView(this).apply {
        text = texto
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(6))
    }

    fun Context.linha(texto: String, mono: Boolean = false): TextView = TextView(this).apply {
        text = texto
        textSize = if (mono) 12f else 15f
        if (mono) typeface = Typeface.MONOSPACE
        setPadding(0, dp(2), 0, dp(2))
    }

    fun Context.botao(texto: String, aoTocar: (View) -> Unit): Button = Button(this).apply {
        text = texto
        isAllCaps = false
        setOnClickListener(aoTocar)
    }

    // ── componentes do sistema visual de 12/09 (paleta Cosmic Luxury) ──────────────────
    // Vêm do desenho aprovado por ele em HTML. Ficam AQUI, e não copiados tela a tela, para
    // que trocar o sistema visual continue sendo mexer num arquivo só.

    private fun Context.cor(id: Int): Int = resources.getColor(id, null)

    /**
     * O CARTÃO: a unidade de leitura das telas. Fundo um degrau acima da superfície e raio
     * curto (degrau 8 da escala 14/10/8/6/4, ordem dele: "quase quadrada, ainda arredondada").
     */
    fun Context.cartao(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(cor(R.color.superficie_recipiente))
            setStroke(dp(1), cor(R.color.contorno))
            cornerRadius = dp(8).toFloat()
        }
        setPadding(dp(14), dp(14), dp(14), dp(14))
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) }
        layoutParams = lp
    }

    /** Sobrescrita pequena e maiúscula: diz em que parte do app a pessoa está. */
    fun Context.kicker(texto: String): TextView = TextView(this).apply {
        text = texto.uppercase()
        textSize = 11f
        letterSpacing = 0.12f
        setTextColor(cor(R.color.primaria))
        setPadding(0, 0, 0, dp(6))
    }

    fun Context.h2(texto: String): TextView = TextView(this).apply {
        text = texto
        textSize = 22f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(cor(R.color.texto))
    }

    fun Context.explicacao(texto: String): TextView = TextView(this).apply {
        text = texto
        textSize = 13f
        setTextColor(cor(R.color.texto_secundario))
        setPadding(0, dp(4), 0, dp(8))
    }

    /**
     * A ÂNCORA: de onde no currículo aquele dado saiu. O glifo dourado é o mesmo do desenho.
     * 🎓 É a peça que sustenta a promessa do produto: "não invento sobre você" não se prova
     * com texto de marketing, se prova mostrando a procedência de cada dado.
     */
    fun Context.ancora(texto: String): TextView = TextView(this).apply {
        val s = SpannableString("⌁ $texto")
        s.setSpan(ForegroundColorSpan(cor(R.color.primaria)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text = s
        textSize = 11f
        setTextColor(cor(R.color.texto_secundario))
        setPadding(0, dp(3), 0, 0)
    }

    /** Rótulo do campo: pequeno, maiúsculo, acima do valor. */
    fun Context.rotuloCampo(texto: String): TextView = TextView(this).apply {
        text = texto.uppercase()
        textSize = 10.5f
        letterSpacing = 0.1f
        setTextColor(cor(R.color.texto_secundario))
    }

    /** O botão da AÇÃO principal da tela. Só um por tela, de propósito. */
    fun Context.botaoPrimario(texto: String, aoTocar: (View) -> Unit): Button = Button(this).apply {
        text = texto
        isAllCaps = false
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(cor(R.color.sobre_primaria))
        background = GradientDrawable().apply {
            setColor(cor(R.color.primaria))
            cornerRadius = dp(6).toFloat()
        }
        setOnClickListener(aoTocar)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(16) }
    }

    /** Botão de ação secundária: contorno, sem peso de fundo. Vários por tela é ok. */
    fun Context.botaoSecundario(texto: String, aoTocar: (View) -> Unit): Button = Button(this).apply {
        text = texto
        isAllCaps = false
        textSize = 14f
        setTextColor(cor(R.color.texto))
        background = GradientDrawable().apply {
            setColor(cor(R.color.superficie_recipiente_alto))
            setStroke(dp(1), cor(R.color.contorno))
            cornerRadius = dp(6).toFloat()
        }
        setOnClickListener(aoTocar)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
    }

    /**
     * Linha de navegação: título, uma linha de estado e a seta. Estado inline de propósito,
     * porque é o que responde "preciso entrar aqui?" sem a pessoa ter que entrar para ver.
     */
    fun Context.linhaNav(titulo: String, estado: String, aoTocar: (View) -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(12))
            isClickable = true
            setOnClickListener(aoTocar)
            val textos = LinearLayout(this@linhaNav).apply { orientation = LinearLayout.VERTICAL }
            textos.addView(
                TextView(this@linhaNav).apply {
                    text = titulo
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(cor(R.color.texto))
                }
            )
            textos.addView(
                TextView(this@linhaNav).apply {
                    text = estado
                    textSize = 12f
                    setTextColor(cor(R.color.texto_secundario))
                }
            )
            addView(textos, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                TextView(this@linhaNav).apply {
                    text = "›"
                    textSize = 20f
                    setTextColor(cor(R.color.primaria))
                }
            )
        }

    /**
     * Item com PONTO de estado. A gramática do desenho aprovado: sálvia = sustentado pelo
     * currículo, âmbar = lacuna DECLARADA, brasa = erro. ⛔ Lacuna não é erro, e a cor tem
     * que dizer isso: é a categoria 4 da régua dele (o app não responde, e avisa).
     */
    fun Context.itemComPonto(
        titulo: String,
        detalhe: String,
        corDoPonto: Int,
        procedencia: String? = null,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(10), 0, dp(10))
        addView(
            View(this@itemComPonto).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(cor(corDoPonto))
                }
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                    topMargin = dp(6); rightMargin = dp(10)
                }
            }
        )
        val textos = LinearLayout(this@itemComPonto).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(
            TextView(this@itemComPonto).apply {
                text = titulo
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(cor(R.color.texto))
            }
        )
        if (detalhe.isNotBlank()) {
            textos.addView(
                TextView(this@itemComPonto).apply {
                    text = detalhe
                    textSize = 12f
                    setTextColor(cor(R.color.texto_secundario))
                }
            )
        }
        procedencia?.let { textos.addView(ancora(it)) }
        addView(textos, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    /**
     * O PLACAR: numeral grande e leve, no estilo score do desenho. Peso 300 de propósito:
     * número grande em negrito grita; grande e leve informa.
     */
    fun Context.placar(numero: String, legenda: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(this@placar).apply {
                    text = numero
                    textSize = 40f
                    setTextColor(cor(R.color.primaria))
                }
            )
            addView(
                TextView(this@placar).apply {
                    text = legenda
                    textSize = 12f
                    setTextColor(cor(R.color.texto_secundario))
                }
            )
        }

    /** Selo de afirmação do produto. Sem cápsula: ele foi explícito, "fora todos os chips". */
    fun Context.selo(texto: String, corDoTexto: Int = R.color.sucesso): TextView =
        TextView(this).apply {
            text = texto
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cor(corDoTexto))
            setPadding(0, dp(8), 0, 0)
        }

    /**
     * OS PILARES: a régua de 4 categorias do desenho aprovado (tela 1) virando gráfico,
     * quatro retângulos e nada de biblioteca, como ele pediu. Cada [Pilar] traz sua própria
     * altura (0f a 1f) e se desenha tracejado ou não; esta função só sabe montar a view.
     *
     * 🎓 A coluna tracejada usa `dashWidth`/`dashGap` no `GradientDrawable` em vez do fundo
     * sólido das outras: é a MESMA gramática de cor (âmbar) que o resto do app usa pra
     * "lacuna declarada", só que aqui em forma de barra em vez de ponto ou palavra. Isso é
     * o que deixa óbvio, sem legenda, que aquela coluna não é um erro de leitura.
     */
    fun Context.pilares(dados: List<Pilar>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) }

        val altura = dp(56)
        dados.forEach { dado ->
            val coluna = LinearLayout(this@pilares).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            coluna.addView(
                TextView(this@pilares).apply {
                    text = dado.valor
                    textSize = 11f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(cor(R.color.texto))
                }
            )

            val moldura = LinearLayout(this@pilares).apply { orientation = LinearLayout.VERTICAL }
            moldura.layoutParams = LinearLayout.LayoutParams(dp(28), altura).apply { topMargin = dp(4) }
            // altura mínima visível: 0% de verdade some da tela e vira "sem coluna nenhuma",
            // que é outra mensagem (bug), não a que o produto quer dar (dado real é baixo).
            val cheio = dado.fracao.coerceIn(0.08f, 1f)
            moldura.addView(
                View(this@pilares),
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f - cheio),
            )
            val barra = View(this@pilares).apply {
                background = if (dado.tracejado) {
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(3).toFloat()
                        setStroke(dp(2), cor(R.color.aviso), dp(4).toFloat(), dp(3).toFloat())
                    }
                } else {
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(3).toFloat()
                        setColor(cor(R.color.primaria))
                    }
                }
            }
            moldura.addView(barra, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, cheio))
            coluna.addView(moldura)

            coluna.addView(
                TextView(this@pilares).apply {
                    text = dado.rotulo
                    textSize = 9.5f
                    setTextColor(cor(R.color.texto_secundario))
                    setPadding(0, dp(4), 0, 0)
                }
            )
            addView(coluna, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }
}
