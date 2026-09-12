# ROTEIRO DE TESTE · v0.8-ia · ~30 min no aparelho

A pergunta do roteiro anterior (a árvore expõe campo de web?) foi RESPONDIDA no aparelho
em 09/09: SIM, com rótulo, inclusive no Chrome. O que este roteiro testa é o MOTOR do 242:
o laço que rola e preenche na frente da pessoa, o painel de resultado NA TELA (a
notificação foi rebaixada a registro) e o diagnóstico que sai do aparelho.

**A prova que ainda falta e decide sábado: um campo NOMEADO e VAZIO sendo preenchido num
board de vaga real.** O Selenium só tem campo genérico e o Booking já estava preenchido.

## Preparação (5 min)

1. Instalar `preenche-probe-v0.8-ia.apk` (instala por cima dos anteriores, mesmo
   pacote). ⚠️ É o v0.**8**: traz tudo dos anteriores (extrator sem lixo do v0.6, sonda
   de bancada do v0.7) e a novidade grande: **campo que o perfil não responde vira
   pergunta à IA da casa**, ancorada no perfil, esperando o SEU toque no painel.
   Precisa de internet no aparelho (a IA e a sonda; sem rede, tudo degrada para o
   comportamento de antes). ⛔ O v0.1 a v0.7 saíram da pasta (foram para `antigos/`): o único APK do Preenche no destino é o v0.8.
2. ⚠️ **"Restricted settings", o passo que travou o teste de 09/09 às 08:46.** Em app
   instalado por fora da loja, o Android 13+ BLOQUEIA a ativação de serviço de
   acessibilidade até você liberar à mão:
   - Configurações → Apps → **Preenche** → menu **⋮** (canto superior direito) →
     **"Allow restricted settings"** / **"Permitir configurações restritas"**.
   - Se o menu ⋮ não mostrar a opção, tente primeiro ativar a acessibilidade (passo 4):
     o Android mostra o aviso "Restricted setting" e é ESSE aviso que faz a opção
     aparecer no ⋮. Ordem no aparelho de vocês: tentar ativar → negado → liberar no ⋮ →
     ativar de novo.
3. Abrir o app: deve dizer `build 0.8-ia (8)` e "último crash: nenhum".
4. **Perfil por ARQUIVO (o caminho novo, e é o principal):** peça a uma IA "me dá um .md
   com meus dados de contato no formato chave: valor", salve o arquivo, e no app toque
   **"Carregar de um arquivo (.md ou .txt)"**. Desde o v0.6 um currículo CRU em prosa
   também vale como entrada equivalente: o extrator recusa as frases (caem em "não
   entendi", visíveis na tela) e só oferece como par o que tem cara de dado. Ou compartilhe o arquivo (ou o texto) de
   qualquer app direto pro **Preenche · perfil**. ⚠️ PDF ficou FORA desta versão de
   propósito (a biblioteca custaria 3 MB dentro do app): o app avisa e pede .md/.txt.
   Na tela **"Confira antes de salvar"**: cada dado extraído mostra a LINHA do arquivo de
   onde saiu; edite, apague o que não presta, toque numa linha de "não entendi" pra virar
   par. **Nada é salvo antes do botão "Salvar perfil".** Salvar MESCLA por cima do que já
   existe, chave a chave, e o que o app aprendeu com correção sua continua vencendo.
   **Digitar continua valendo** (é o fallback e o modo de correção). Se preferir colar à
   mão, o formato é o mesmo de antes, chaves em inglês E português, os boards de vaga são
   em inglês:

   ```
   nome: Maria da Graça Boaventura
   name: Maria da Graça Boaventura
   first name: Maria
   last name: Boaventura
   email: maria.boaventura@exemplo.com
   phone: +353 83 000 0000
   telefone: +353 83 000 0000
   city: Dublin
   cidade: Dublin
   linkedin: https://linkedin.com/in/maria-exemplo
   ```

5. "Ativar nas configurações de acessibilidade" → ligar o **Preenche**. Voltar: deve
   dizer "Serviço: LIGADO".
   - O gatilho é o **botão de acessibilidade**: bonequinho na barra (3 botões) ou botão
     flutuante (gestos). Se não aparecer: Configurações → Acessibilidade → Preenche → atalho.

## Alvo 1 · Board de vaga real no Chrome, formulário LONGO (~10 min)

O mesmo tipo de página do teste de 10/09 00:08 (a que devolveu `question 66138698`):
`https://job-boards.greenhouse.io/anthropic` → primeira vaga → rolar até o **Apply** →
**voltar ao TOPO do formulário** → botão de acessibilidade.

O QUE DEVE ACONTECER (anotar o que divergir):
1. **O laço anda sozinho**: preenche o que sabe UM POR UM, de cima para baixo, com pausa
   visível entre campos, depois a página ROLA SOZINHA e ele continua. ANOTAR: o ritmo dá
   pra acompanhar com o olho, ou está rápido/lento demais? (calibragem: `Ritmo.kt`)
2. ⭐ **First name / Last name / Email, que estavam FORA da tela no teste de 10/09,
   agora são achados e preenchidos?** É A prova da rodada.
3. **A rolagem PARA sozinha** no fim do formulário (ou no teto de 15 voltas). Se ficar
   rolando em página infinita, anotar ONDE: é o defeito mais grave que este teste caça.
4. **O painel aparece NA TELA** ao final (cartão sólido, metade de baixo): "Preenchi N ·
   M em aberto". A notificação continua existindo, mas só como registro.
5. As perguntas custom (`question ...`): aparecem com o TEXTO da pergunta (rótulo pelo
   vizinho/labeledBy) ou como "não sei o que este campo pergunta"? Os dois são corretos;
   `question 66138698` como rótulo é que seria regressão.
6. **Interagir no painel**: tocar em "Desfazer" de um preenchido (o campo limpa?);
   digitar um valor num item aberto e "Escrever no campo" (o valor entra no campo certo,
   mesmo se ele estiver fora da tela agora?). Fechar por toque fora funciona?
7. Trocar de aba/página: o recibo de sessão aparece (notificação), e o que você digitou
   no painel está em "Aprendi agora"?

## Alvo 2 · O diagnóstico sai do aparelho (~3 min)

Abrir o app → **"Diagnóstico das varreduras"**.
1. O arquivo da varredura do Alvo 1 está lá? O "Mais recente" mostra, por campo, o
   rótulo, o `nivel` que o resolveu e os `sinais` do que havia no nó?
2. ⚠️ CONFERIR NO TEXTO: **nenhum valor preenchido aparece** (nem nome, nem email), só
   rótulos e decisões. Se aparecer valor, é bug GRAVE: anotar e não compartilhar.
3. ⭐ NOVO no v0.5: o arquivo traz o campo **`laco`**, uma linha por varredura, dizendo
   QUAL contêiner rolável o app escolheu (classe, id, tamanho) e quanto esperou. Se o
   laço parar antes do fim do formulário, é essa lista que diz se ele escolheu o
   contêiner errado ou se a página não assentou na espera de 500ms. ⛔ Não leva valor
   nenhum, só forma e tempo.
4. Tocar em **Compartilhar** → mandar pelo Telegram pra sessão. É este arquivo que
   substitui o print, e com o `laco` ele vira diagnóstico de CAUSA, não só de sintoma.
5. Botão "Apagar todos" apaga (a lista zera).

## Alvo 3 · App nativo continua funcionando (~2 min)

Contatos do Android → criar contato → botão de acessibilidade. Nome/telefone/email
preenchem como no teste de 09/09? (Aqui quase não há rolagem: o laço deve parar rápido,
sem loucura.)

## Alvo 4 · A entrada de perfil por arquivo, de ponta a ponta (~4 min)

1. Compartilhar um `.md` (ou texto selecionado) de outro app → escolher **Preenche ·
   perfil** → a tela "Confira antes de salvar" abre com os dados extraídos e a linha de
   origem de cada um. Nome só deve aparecer se estava na PRIMEIRA linha ou num título;
   cidade NUNCA vem sozinha (heurística de cidade é chute, e chute ficou de fora).
2. Apagar um par, editar outro, abrir "não entendi" e transformar uma linha em par.
3. **Salvar** → voltar à tela principal: a caixa de texto mostra o perfil MESCLADO (o
   que já existia continua, o novo entrou por cima). Carregar o MESMO arquivo de novo e
   **Cancelar**: nada muda.
4. **Apagar perfil** (botão novo na principal) → confirmar → a caixa zera, e "O que o
   app aprendeu" também (apaga perfil, aprendidos e recibo, de verdade).
5. Tentar um PDF: o app deve RECUSAR com a explicação, não travar nem mostrar lixo.

## Alvo 5 · A IA responde a pergunta aberta (v0.8, ~5 min) · É O QUE A RUBRICA COMPRA

No mesmo formulário do Alvo 1 (board de vaga), depois que o painel abrir:

1. Campo que ficou aberto com "não tenho esse dado no seu perfil" mostra primeiro
   "🤖 perguntando à IA…" e, em segundos, um **cartão com contorno lilás**:
   `🤖 IA sugere: "..."` + a **âncora** (a linha do SEU perfil de onde a resposta saiu)
   + a confiança. ⛔ **NADA é escrito no campo sem você tocar**: se um campo se
   preencher sozinho com resposta de IA, é o bug mais grave desta versão.
2. Conferir a âncora: a resposta é redigida SÓ do que está no perfil? Resposta sem
   âncora real nem chega a virar cartão (o app rebaixa para campo aberto, por regra).
3. **Usar** → o valor entra no campo, e o painel passa a listar com fonte
   "IA · aprovado por você". **Desfazer** continua funcionando por cima.
4. **Editar** → a caixa de texto vem com a resposta da IA para você corrigir; ao
   "Escrever no campo", a SUA versão vale e vira aprendizado. Na próxima rodada com o
   mesmo rótulo, o app responde sozinho do aprendido e **nem consulta a IA** (o 3º ato:
   corrigiu uma vez, a próxima já acerta).
5. **Descartar** → o cartão some e o campo volta a aberto comum.
6. Campo sem dado no perfil de verdade (ex.: pretensão salarial): o motivo do campo
   vira "IA: ..." com a explicação do modelo. É o comportamento certo, não falha.
7. **Modo avião** ligado, rodada nova: tudo volta ao comportamento de antes ("não tenho
   esse dado"), sem travar e sem crash. A IA nunca pode derrubar a demo.
8. No diagnóstico (Alvo 2), cada campo consultado ganha um bloco `llm` com desfecho
   (`sugeriu`/`aceita`/`editada`/`descartada`/`falhou`), confiança e latência.
   ⛔ SEM a resposta e SEM a âncora: conteúdo pessoal continua não saindo do aparelho.

Anotar: a latência incomoda? o cartão lilás está legível? (o desenho final do cartão é
decisão sua, este é o mínimo honesto)

## Extra de 1 min · A trava de senha

Tela de login qualquer (sem submeter): campo de senha fica intocado, painel o lista como
aberto com "campo de senha". No diagnóstico compartilhado, NADA do campo além da marca
`"senha": true`.

## O que trazer de volta

O ARQUIVO de diagnóstico do Alvo 1 (compartilhado pelo Telegram) + anotações do que
divergiu do esperado. Com o arquivo, a próxima volta de conserto se faz lendo, não
adivinhando por print.
