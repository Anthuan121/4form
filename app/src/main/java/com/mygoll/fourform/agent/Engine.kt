package com.mygoll.fourform.agent

import com.mygoll.fourform.scan.Labeler
import com.mygoll.fourform.scan.Field
import com.mygoll.fourform.scan.Choice

/**
 * The round's loop (brief 242): scan the screen -> act field by field, top to bottom ->
 * scroll -> scan again -> repeat until the form stops growing. The accessibility tree
 * only contains what's RENDERED (Chrome and Android don't create a node for what's
 * outside the viewport), so a single scan never sees a 3-screen form.
 *
 * Pure and synchronous on purpose: TIME (pause between fields, wait for the scroll to
 * settle) lives in the service; here it's just the next-step decision, testable in a JVM
 * test. The four mandatory stops: scroll with no new field, round cap, package change,
 * and a screen that no longer scrolls.
 */
class Engine(private val tetoDeVoltas: Int = TETO_DE_VOLTAS) {

    companion object {
        // ponytail: 15 scrolls comfortably covers a ~5-screen form; the cap exists
        // against an infinite page, not against a large form. Blows past it on a real form → raise it.
        const val TETO_DE_VOLTAS = 15
    }

    sealed class Passo {
        /** Decide and write THIS field now; confirm with campoTratado(). */
        data class Agir(val campo: Field) : Passo()

        /**
         * Decide THIS choice now (check it or leave it blank) and move on. His rule,
         * 09/12: "if it's going to check it, it checks it and the game moves on; if it's
         * not going to check it, it moves to the next field, no need to block me there".
         * That's why a choice is a loop STEP, in the same queue and the same visual
         * order as text fields, and ⛔ not a pending-items list at the end. The loop goes
         * through item by item until the end of the form.
         */
        data class Escolher(val escolha: Choice) : Passo()

        object Rolar : Passo()
        data class Fim(val motivo: String) : Passo()
    }

    /** false = the whole round saw no field at all; the result panel does NOT appear. */
    var achouAlgumCampo = false
        private set

    var voltasDeRolagem = 0
        private set

    private var houveVarredura = false
    private var pacoteInicial: String? = null
    private var mudouDePacote = false
    private var semRolagem = false
    private val chavesVistas = mutableSetOf<String>()
    private val rotulosVistos = mutableSetOf<String>()

    // A SINGLE queue of steps, ordered by top-of-screen position: text and choice live in
    // it together, in the order the person sees them. It used to be two lists (fields in
    // the loop, choices in a panel at the end), and that's what produced the freeze. The
    // app would fill 3 fields, reach the checkbox, and stop to ask. His rule, 09/12:
    // "checked it, the game moves on; didn't check it, moves to the next field".
    private val fila = ArrayDeque<Passo>()

    private fun chaveDe(p: Passo): String? = when (p) {
        is Passo.Agir -> p.campo.chave
        is Passo.Escolher -> p.escolha.chave
        else -> null
    }

    /**
     * Feeds the engine with what one scan saw. Returns how many NEW fields came in. From
     * the 2nd scan onward, each call counts as one completed scroll round, and coming
     * back with nothing new is the loop's natural stopping point.
     */
    fun receberVarredura(
        pacote: String?,
        campos: List<Field>,
        // How many brand-new CHOICE fields this scan brought in. Counts toward the
        // stopping decision because the old rule measured "new TEXT field", and that
        // killed the loop early: measured on 09/12 on a real job form, scrolling brought
        // in 7 radios and zero text boxes, and the loop read that as "reached the end"
        // and stopped on round 1. A scroll that reveals ANY answerable field was worth it.
        escolhasNovas: List<Choice> = emptyList(),
        // Fingerprint of what THIS scan saw on screen. The stopping rule became his
        // (09/12), and it's the right concept: "the form only ends when scrolling
        // reaches the bottom of the screen". Scrolling and finding no new field ⛔ is not
        // the end: a real form has huge blocks in the middle (the resume upload, a
        // consent text) and then has fields again. Stopping at the first empty stretch
        // loses the bottom half. So the only legitimate end is the screen not moving
        // anymore, and that's what the fingerprint measures: same twice = the scroll
        // moved nothing.
        assinatura: String = "",
    ): Int {
        if (!houveVarredura) {
            houveVarredura = true
            pacoteInicial = pacote
        } else {
            voltasDeRolagem++
            if (pacote != pacoteInicial) {
                mudouDePacote = true
                return 0
            }
        }
        if (campos.isNotEmpty() || escolhasNovas.isNotEmpty()) achouAlgumCampo = true
        val novos = campos.filter { !jaVisto(it) }
        for (c in novos) {
            chavesVistas.add(c.chave)
            Labeler.rotulo(c)?.let { rotulosVistos.add(it.first) }
        }
        // VISUAL top-to-bottom order: it's the pace the person follows on screen, and
        // it's what makes text and choice interleave like in the real form.
        val passos = novos.map { it.caixa.topo to (Passo.Agir(it) as Passo) } +
            escolhasNovas.map { it.caixa.topo to (Passo.Escolher(it) as Passo) }
        fila.addAll(passos.sortedBy { it.first }.map { it.second })
        if (voltasDeRolagem > 0 && assinatura.isNotEmpty() && assinatura == assinaturaAnterior) {
            telaNaoSeMoveu = true
        }
        assinaturaAnterior = assinatura
        return novos.size
    }

    private var assinaturaAnterior: String? = null

    /** true = the last scroll was requested and the tree came back identical: that method doesn't move the screen. */
    var telaNaoSeMoveu = false
        private set

    /**
     * Gives the loop one more chance with ANOTHER scroll method. Measured on 09/12 on
     * Edge: ACTION_SCROLL_FORWARD on a WebView returns success and ⛔ moves nothing (207
     * nodes before, 207 after). Without this escape hatch, "the tree's action doesn't
     * work in this app" and "the form ended" become the same thing, and the loop dies
     * midway through the form.
     */
    fun tentarOutroMeioDeRolagem() {
        telaNaoSeMoveu = false
        assinaturaAnterior = null
    }

    /**
     * The same field can't be filled twice when it reappears after scrolling. 1st
     * defense: the stable key. 2nd defense: the key falls back to the BOUNDING BOX when
     * the field has no viewId/hint/description, and the box changes on every scroll, so
     * a "new" field with an already-seen label AND text inside is almost certainly an
     * already-handled one that came back with a different box. Discarding it loses
     * nothing: with text it would become "I don't overwrite" anyway.
     */
    private fun jaVisto(c: Field): Boolean {
        if (c.chave in chavesVistas) return true
        if (!c.textoAtual.isNullOrBlank()) {
            val r = Labeler.rotulo(c)?.first
            if (r != null && r in rotulosVistos) return true
        }
        return false
    }

    fun proximoPasso(): Passo = when {
        mudouDePacote -> Passo.Fim("the screen changed to another app mid-round")
        fila.isNotEmpty() -> fila.first()
        // an ENTRY screen with nothing answerable isn't a form: stop right away, instead
        // of scrolling 15 times chasing something that doesn't exist. After round 1 the
        // rule flips, because by then it's known to BE a form and an empty stretch can
        // just be a block in the middle.
        !achouAlgumCampo && voltasDeRolagem == 0 -> Passo.Fim("I didn't find an answerable field on this screen")
        telaNaoSeMoveu && !achouAlgumCampo -> Passo.Fim("I scrolled through the whole screen and didn't find an answerable field")
        telaNaoSeMoveu -> Passo.Fim("scrolled all the way: the screen doesn't move anymore")
        semRolagem -> Passo.Fim("the screen doesn't scroll anymore")
        voltasDeRolagem >= tetoDeVoltas -> Passo.Fim("hit the cap of $tetoDeVoltas scrolls: stopping as a safety measure")
        else -> Passo.Rolar
    }

    /**
     * The service confirms it handled the item at the front, whether it filled it,
     * checked it, or decided not to touch it. Applies to both fields and choices:
     * handling means leaving the queue, and "didn't check it" also counts as handled.
     * This indifference is what guarantees nothing freezes the loop.
     */
    fun campoTratado(chave: String) {
        if (chaveDe(fila.firstOrNull() ?: return) == chave) fila.removeFirst()
        else fila.removeAll { chaveDe(it) == chave }
    }

    /** ACTION_SCROLL_FORWARD refused or no scrollable node: scrolling is over for good. */
    fun rolagemFalhou() {
        semRolagem = true
    }
}
