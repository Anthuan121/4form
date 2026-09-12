package com.mygoll.fourform

/**
 * O ÚNICO lugar dos tempos do laço (critério 10 do brief 242): no dia 12/09 a calibragem
 * é mexer aqui e rebuildar, sem caçar constante.
 */
object Pace {
    // ponytail: os dois números são CHUTE MEU, não medida. O Anthuan pediu ritmo visível
    // ("não importa que demore um pouquinho"): a pausa entre campos é o que deixa a pessoa
    // VER o agente preenchendo um a um, em ordem, em vez de tudo aparecer pronto.
    const val ENTRE_CAMPOS_MS = 350L

    // quanto esperar depois do ACTION_SCROLL_FORWARD para a tela assentar e a árvore
    // refletir o que entrou na viewport; curto demais = varredura vê a tela velha.
    const val POS_ROLAGEM_MS = 500L

    // duração do arrastar quando a ação da árvore não move a tela (WebView). Rápido demais
    // vira "fling" e a página sai voando passando campos; devagar demais o usuário acha que
    // travou. 300ms é arrastar deliberado, que para onde o dedo parou.
    // ponytail: valor único; se algum app precisar de outro, vira tabela por pacote.
    const val GESTO_MS = 300L
}
