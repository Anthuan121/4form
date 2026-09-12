package com.mygoll.fourform

import com.mygoll.fourform.scan.Extractor
import com.mygoll.fourform.scan.Matcher

/**
 * One pillar of the "Your profile" card (drawing of 12/09, tela 1): an area's label, the
 * number/percentage to show, how tall the column is (0f..1f), and whether it draws DASHED.
 *
 * 🎓 `tracejado` is never a "something broke" flag. It's the fourth category of the
 * product's own rule: a dashed column is a gap the app DECLARES, on purpose, instead of
 * making up a value. That's the exact opposite of an error, so it never gets the
 * solid-column shape that means "the resume backs this up".
 */
data class Pilar(val rotulo: String, val valor: String, val fracao: Float, val tracejado: Boolean)

data class Pilares(val itens: List<Pilar>, val lacunas: Int)

/**
 * Turns the profile text (one "key: value" line per fact, same format `Profile.kt` reads)
 * into the 4 pillars of the drawing: Contact / Experience / Education as solid columns with
 * a real percentage, plus "No answer" as the dashed column with a real count.
 *
 * PURE on purpose (no Context, no Android view): pulled out of the Activity so it can be
 * unit-tested without an emulator (brief 256, critério de pronto item 4).
 */
object PerfilCompletude {

    // Each list below is made of WRITTEN keys that already exist in scan/Extractor.kt's
    // synonym table (read there, not invented here) and only serve to fetch that key's
    // language twins through the public Extractor.bilingue(). "Pessoal" doesn't get its own
    // visible column (the drawing has only 3 content columns + "No answer"), but its fields
    // still count toward which areas are empty, because the brief asks to group them too.
    private val CONTATO = listOf("email", "telefone", "linkedin", "portfolio")
    private val PESSOAL = listOf("nome", "sobrenome", "cidade", "pais", "nacionalidade")
    private val EXPERIENCIA = listOf("cargo", "empresa", "anos de experiencia", "area de atuacao")
    private val FORMACAO = listOf("formacao", "idiomas")

    /** null when the profile has no parseable "key: value" line at all: the caller already
     *  has a message for that case ("Empty. Without a profile..."), and a chart of nothing
     *  is worse than that sentence, so this function stays silent instead of faking zeros. */
    fun calcular(perfilTexto: String): Pilares? {
        val chavesDoPerfil = chavesPreenchidas(perfilTexto)
        if (chavesDoPerfil.isEmpty()) return null

        val contato = presentes(CONTATO, chavesDoPerfil) to CONTATO.size
        val pessoal = presentes(PESSOAL, chavesDoPerfil) to PESSOAL.size
        val experiencia = presentes(EXPERIENCIA, chavesDoPerfil) to EXPERIENCIA.size
        val formacao = presentes(FORMACAO, chavesDoPerfil) to FORMACAO.size

        // "No answer" (item 4 do brief): não existe, hoje, uma lista fixa das perguntas que
        // um formulário de vaga vai fazer, então o fallback que o próprio brief autoriza é
        // a contagem de ÁREAS que o perfil deixa 100% vazias, nunca um número inventado.
        val areas = listOf(contato, pessoal, experiencia, formacao)
        val areasVazias = areas.count { (presentes, _) -> presentes == 0 }

        val itens = listOf(
            pilarDeArea("Contact", contato),
            pilarDeArea("Experience", experiencia),
            pilarDeArea("Education", formacao),
            Pilar(
                rotulo = "No answer",
                valor = areasVazias.toString(),
                fracao = areasVazias.toFloat() / areas.size,
                tracejado = true,
            ),
        )
        return Pilares(itens, areasVazias)
    }

    private fun pilarDeArea(rotulo: String, par: Pair<Int, Int>): Pilar {
        val percentual = (par.first * 100f / par.second).toInt()
        return Pilar(rotulo = rotulo, valor = "$percentual%", fracao = par.first.toFloat() / par.second, tracejado = false)
    }

    private fun presentes(grupo: List<String>, chavesDoPerfil: Set<String>): Int =
        grupo.count { semente -> aceitas(semente).any { it in chavesDoPerfil } }

    private fun chavesPreenchidas(perfilTexto: String): Set<String> =
        perfilTexto.lines().mapNotNull { linha ->
            val i = linha.indexOf(':')
            if (i <= 0) return@mapNotNull null
            val chave = linha.substring(0, i).trim()
            val valor = linha.substring(i + 1).trim()
            if (chave.isBlank() || valor.isBlank()) null else Matcher.normalizar(chave)
        }.toSet()

    private fun aceitas(semente: String): Set<String> =
        Extractor.bilingue(semente).map { Matcher.normalizar(it) }.toSet()
}
