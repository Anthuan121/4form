package com.mygoll.fourform.scan

/** Rectangle in screen pixels, without android.graphics.Rect so the core runs in a JVM test. */
data class Box(val esq: Int, val topo: Int, val dir: Int, val baixo: Int)

data class RotuloVizinho(val texto: String, val distanciaPx: Int)

/**
 * A CHOICE field (checkbox, radio, select, date). The app is blind to them today: the
 * scan only collected isEditable, meaning only text boxes. Half of a job form is choice,
 * so "barely filled anything" can mean "barely SAW anything".
 *
 * ⛔ This phase only COUNTS, it doesn't act: measure how much of the form was invisible
 * first, then decide whether implementing the click is worth it. Measuring before
 * building costs one number and avoids building the wrong thing.
 *
 * ⛔ The STATE (checked/unchecked) doesn't go in here on purpose: "I'm an EU citizen: yes"
 * is personal data, and this object feeds the diagnostic that LEAVES the device. Label
 * and type are enough for the measurement; the 242 rule of zero personal content still stands.
 */
data class Choice(
    val tipo: String,
    val rotulo: String? = null,
    val viewId: String? = null,
    val caixa: Box = Box(0, 0, 0, 0),
    val dentroDeWebView: Boolean = false,
    /** true = the tree accepts ACTION_CLICK on this node; false = visible, but can't be operated. */
    val clicavel: Boolean = false,
    /** Current state: starts from the tree's isChecked and turns true when the app checks it. */
    val marcada: Boolean = false,
    /**
     * The QUESTION this option answers ("Do you reside in a European country?"), when it
     * can be found in the text above the group. Without it, "Yes" is a loose word: the
     * app used to compare the OPTION's label to the profile and never matched anything,
     * because no profile contains "Yes". His finding on 09/12, filling out a real job form.
     */
    val pergunta: String? = null,
) {
    /** Identity across scans: the same radio reappears after scrolling with a different box. */
    val chave: String get() = "$tipo|${viewId ?: ""}|${rotulo ?: caixa.topo}"
}

/**
 * A snapshot of one editable node in the accessibility tree.
 * A password Field is born with textoAtual = null: password text is never read (a hard
 * lock, applied right at the scan, before any decision).
 */
data class Field(
    val chave: String,
    val inputType: Int = 0,
    val hint: String? = null,
    val descricao: String? = null,
    val viewId: String? = null,
    val senha: Boolean = false,
    val caixa: Box = Box(0, 0, 0, 0),
    val textoAtual: String? = null,
    val dentroDeWebView: Boolean = false,
    // labeledBy is the tree's EXPLICIT label relationship (Chrome fills it from
    // <label for>, aria-label, aria-labelledby): it's the label the page's AUTHOR
    // declared, not a geometric guess. That's why it's the top of the ladder (brief 242).
    val labeledBy: String? = null,
    // the node HAD the labeledBy relationship, even if the target carried no useful
    // text. Instrumentation so the diagnostic can say "there was a labeledBy, but empty".
    val labeledByPresente: Boolean = false,
    // text from a preceding sibling in the same parent, collected only in WebView:
    // STRUCTURAL label (the <label> is usually a sibling of the <input>), more reliable
    // than pixel distance.
    val rotuloIrmao: String? = null,
    /**
     * true = another field on this SAME screen has the same hint. A hint that serves two
     * fields doesn't name either of them, so the label ladder skips that level and falls
     * to the neighbor. ⛔ Doesn't go into the stable key: the key still uses the raw hint,
     * otherwise the same field would change identity between scans.
     */
    val hintRepetidoNaTela: Boolean = false,
    // since 242, NO radius cap: whatever is closest, however far. The radius is applied
    // at USE time (Labeler.rotulo); keeping the far neighbor lets the diagnostic say
    // "there was text at 380px" instead of pretending there was nothing.
    val rotuloVizinho: RotuloVizinho? = null,
)

/** A fact the app learned by observing the user. origem: "corrigiu" (corrected) | "preencheu" (filled). */
data class Learned(
    val rotulo: String,
    val valor: String,
    val quandoMs: Long,
    val origem: String,
)
