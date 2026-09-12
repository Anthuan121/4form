# PLANO DO DIA · sábado 12/09 · 4h15 de build · submissão 17:00 IST

O projeto do hackathon nasce NO DIA, em repo novo (`preenche`, primeiro commit no evento).
Este repo (`preenche-probe`) é o protótipo descartável que provou a viabilidade e NÃO
é submetido. O que passa: o conhecimento (o que a árvore expõe, o que quebra) e o
andaime que o handbook autoriza como starter code (esqueleto de projeto, gravador de
crash, serviço registrado, tema).

## Antes do dia (depende do teste de 20 min)

O `ROTEIRO-DE-TESTE.md` responde a pergunta que muda tudo: **campos de web aparecem na
árvore?**

- ✅ Web funciona → o demo é formulário de vaga real no Chrome (o cenário mais forte).
- ⛔ Web não funciona → o demo pivota para app nativo (Contatos ou formulário próprio
  de demonstração) e o vídeo conta a limitação com honestidade. Decidir isso na
  sexta, nunca às 13h de sábado.

## Ordem de ataque

| hora | o quê | por quê nessa ordem |
|---|---|---|
| 0:00 a 0:20 | Repo novo. Esqueleto: manifest com `.App` + gravador de crash + serviço registrado + botão de acessibilidade respondendo com um toast. | O crash cego custou 4 builds. O gravador entra ANTES de qualquer funcionalidade, de novo. |
| 0:20 a 1:20 | Reconstruir o laço central: varrer a árvore, decidir, escrever. Saber o caminho torna isso rápido; reconstruir (não colar) mantém a submissão limpa. | Sem ler e escrever não existe projeto. É o chão. |
| 1:20 a 2:20 | Aprender com correção + recibo com desfazer. | É o TERCEIRO ATO do vídeo e o critério 4 da rubrica (agente claro e controlável). |
| 2:20 a 3:00 | A inteligência: níveis de identificação com confiança (o conhecimento do form-agent-core, reconstruído). É aqui que vira agente e não automação. | O critério de inovação pune "generic automation"; o nível-que-resolveu-vira-confiança é o argumento. |
| 3:00 a 3:45 | Vídeo de 2 min: preenche → informa o que não soube → usuário corrige/completa → recibo mostra o aprendido → próxima tela ele acerta sozinho. | Com recibo, a prova do aprendizado acontece no MESMO formulário. Vídeo curto, história inteira. |
| 3:45 a 4:15 | README com a linha de método ("viabilidade testada antes num protótipo descartável; este projeto foi construído hoje") + submissão + folga. | Declarar é a saída que o handbook pede, e soa método, não desculpa. |

## Corte de escopo, na ordem em que cai

1. Tela "o que o app aprendeu" com editar/apagar (o recibo com desfazer já demonstra
   controle no vídeo).
2. Relatório JSON em arquivo.
3. Rótulo por vizinho de pixel (se hint/viewId bastarem nos alvos do demo).
4. Cobertura dupla web + nativo: fica só o cenário que o teste de 20 min provou.

## ⭐ O que NUNCA se sacrifica

**Preencher um campo de verdade e o aprender-com-correção.** Sem os dois não há
terceiro ato no vídeo; sem terceiro ato o júri lê o projeto como automação, que é a
nota 1 do critério de inovação. Tudo o mais é negociável.

## Regras que continuam valendo no dia

- Campo de senha: nunca lido, nunca escrito. Não configurável.
- Aviso passivo, campo fica aberto. Nada de diálogo roubando foco.
- Gatilho explícito. O app não age sozinho.
- Nada sai do aparelho.
- Fim de sessão = mudança de tela. Não detectar submit.
