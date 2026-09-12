# Preenche (probe)

App Android mínimo cujo trabalho inteiro é: **ler os campos de texto da tela e escrever
neles o que você já contou sobre você**. Quando não sabe, não inventa: informa o motivo,
deixa o campo aberto, e **aprende observando você preencher ou corrigir**. Tudo local,
nada sai do aparelho.

## Isto é uma prova de conceito

**Este repositório NÃO é o projeto do hackathon.** O projeto submetido ao AI Tinkerers
Dublin será construído **no dia 12/09/2026, em repositório novo, com o primeiro commit
dentro do período oficial do evento**, como a regra de elegibilidade exige. Este probe
existe para testar a viabilidade antes (a árvore de acessibilidade expõe os campos de
formulário?) e para deixar o caminho aprendido. O que passa para o dia é conhecimento e
andaime autorizado (starter code); a funcionalidade central será reconstruída lá.
Roteiro do teste de viabilidade: `ROTEIRO-DE-TESTE.md`. Plano do dia: `PLANO-DO-DIA.md`.

## Como funciona

1. Você cola seu perfil no app (uma linha `chave: valor` por dado). Fica em `filesDir/`.
2. Ativa o serviço de acessibilidade e usa o **botão de acessibilidade** na tela do
   formulário. O app só age quando chamado.
3. Ele varre a janela ativa, dá nome a cada campo editável (hint → descrição → viewId →
   texto vizinho por distância em pixels, a mesma escada de níveis do form-agent-core) e
   escreve o que o perfil souber, via `ACTION_SET_TEXT`.
4. O que ele não soube fica **aberto**, com o motivo numa notificação passiva. Você
   preenche do seu jeito (digitando ou ditando pelo teclado). O serviço observa
   `TYPE_VIEW_TEXT_CHANGED` **só durante a sessão** e guarda o que você forneceu.
5. A tela mudou (`TYPE_WINDOW_STATE_CHANGED`) = fim da sessão: sai o **recibo** do que
   foi preenchido, do que ficou aberto e do que ele aprendeu, com **desfazer** por item.
   Sessão que não preencheu nem aprendeu não gera recibo.
6. Na rodada seguinte, o aprendido vence o perfil base.

## Travas que não são configuráveis

- Campo com `isPassword` **nunca é lido nem escrito**, e nunca vira aprendizado.
- Campo que já tem texto não é sobrescrito.
- A observação **para** quando a sessão fecha. Não existe escuta permanente.
- Sem rede: o app não tem permissão de internet. Nada sai do aparelho.

## Build e testes

```
./gradlew testDebugUnitTest   # 32 testes JVM, sem aparelho
./gradlew assembleDebug       # APK em app/build/outputs/apk/debug/
```

Sem dependência de runtime (Activity pura, tema de plataforma). O tema vive num arquivo
só (`res/values/themes.xml`) de propósito: o sistema visual chega por outro trabalho e a
troca custa um arquivo.

## O que este probe deliberadamente NÃO tem

Inteligência de casamento (a comparação rótulo × chave é por texto simples, burra de
propósito: essa é a construção do dia do evento), reconhecimento de voz (o ditado do
teclado já resolve), detecção de submit (mudança de tela cobre mais casos com menos
código), lista de apps observados (funciona em qualquer tela) e sistema visual.
