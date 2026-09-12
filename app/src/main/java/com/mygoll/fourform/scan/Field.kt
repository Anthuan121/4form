package com.mygoll.fourform.scan

/** Retângulo em pixels de tela, sem android.graphics.Rect para o núcleo rodar em teste JVM. */
data class Box(val esq: Int, val topo: Int, val dir: Int, val baixo: Int)

data class RotuloVizinho(val texto: String, val distanciaPx: Int)

/**
 * Um campo de ESCOLHA (checkbox, radio, select, data). O app hoje é cego para eles: a
 * varredura só coletava isEditable, ou seja, só caixa de texto. Metade de um formulário de
 * vaga é escolha, então "não preencheu quase nada" pode ser "não VIU quase nada".
 *
 * ⛔ Esta fase só CONTA, não age: primeiro medir quanto do formulário estava invisível,
 * depois decidir se vale implementar o clique. Medir antes de construir custa um número e
 * evita construir a coisa errada.
 *
 * ⛔ O ESTADO (marcado/desmarcado) não entra aqui de propósito: "sou cidadão da UE: sim" é
 * dado pessoal, e este objeto alimenta o diagnóstico que SAI do aparelho. Rótulo e tipo
 * bastam para a medição; a régua de zero conteúdo pessoal do 242 continua de pé.
 */
data class Choice(
    val tipo: String,
    val rotulo: String? = null,
    val viewId: String? = null,
    val caixa: Box = Box(0, 0, 0, 0),
    val dentroDeWebView: Boolean = false,
    /** true = a árvore aceita ACTION_CLICK neste nó; false = dá pra ver, não dá pra operar. */
    val clicavel: Boolean = false,
    /** Estado atual: nasce do isChecked da árvore e vira true quando o app marca. */
    val marcada: Boolean = false,
    /**
     * A PERGUNTA a que esta opção responde ("Você reside num país europeu?"), quando dá pra
     * achar no texto acima do grupo. Sem ela, "Yes" é uma palavra solta: o app comparava o
     * rótulo da OPÇÃO com o perfil e nunca casava nada, porque nenhum perfil contém "Yes".
     * Found dele em 12/09, preenchendo uma vaga de verdade.
     */
    val pergunta: String? = null,
    /**
     * Where the click decision came from, filled AFTER decidirEscolha runs (null while the
     * choice is still just census): "perfil" when a profile line (or something learned)
     * settled it, "ia" when Camada 2 matched a literal option from the model, "nenhum" when
     * neither did. Diagnostics needs this because zero clicks has three different causes
     * (no label, label but no profile match, IA discarded) and, before this field, all three
     * looked identical from outside: that ambiguity is what stalled this bug for a full day.
     */
    val origemDecisao: String? = null,
) {
    /** Identidade entre varreduras: o mesmo radio reaparece depois da rolagem com outra caixa. */
    val chave: String get() = "$tipo|${viewId ?: ""}|${rotulo ?: caixa.topo}"
}

/**
 * Fotografia de um nó editável da árvore de acessibilidade.
 * Field de senha nasce com textoAtual = null: o texto de senha nunca é lido (trava dura,
 * aplicada já na varredura, antes de qualquer decisão).
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
    // labeledBy é a relação EXPLÍCITA de rótulo da árvore (o Chrome a preenche a partir de
    // <label for>, aria-label, aria-labelledby): é o rótulo que o AUTOR da página declarou,
    // não um palpite geométrico. Por isso é o topo da escada (brief 242).
    val labeledBy: String? = null,
    // o nó TINHA a relação labeledBy, mesmo que o alvo não carregasse texto útil —
    // instrumentação para o diagnóstico dizer "havia labeledBy, mas vazio".
    val labeledByPresente: Boolean = false,
    // texto de um irmão anterior no mesmo pai, coletado só em WebView: rótulo ESTRUTURAL
    // (o <label> costuma ser irmão do <input>), mais confiável que distância em pixels.
    val rotuloIrmao: String? = null,
    /**
     * true = outro campo desta MESMA tela tem o mesmo hint. Um hint que serve a dois campos
     * não nomeia nenhum dos dois, então a escada de rótulo pula o nível e cai no vizinho.
     * ⛔ Não entra na chave estável: a chave continua usando o hint bruto, senão o mesmo
     * campo mudaria de identidade entre varreduras.
     */
    val hintRepetidoNaTela: Boolean = false,
    // desde o 242 SEM teto de raio: o mais próximo que existir. O raio é aplicado na hora
    // de USAR (Labeler.rotulo); guardar o vizinho longe deixa o diagnóstico dizer
    // "havia texto a 380px" em vez de fingir que não havia nada.
    val rotuloVizinho: RotuloVizinho? = null,
)

/** Um dado que o app aprendeu observando o usuário. origem: "corrigiu" | "preencheu". */
data class Learned(
    val rotulo: String,
    val valor: String,
    val quandoMs: Long,
    val origem: String,
)
