# PA-04 — transporte durável dos eventos OBS 1.0.0

## Diff (PR #1)

Revisão: `git diff 932e61c d3e0f8a -- src/main/java/com/nekkora/agent/platform/{EventReader,DeliveryStore,AckCheck,DeliveryView}.java src/main/java/com/nekkora/agent/obs src/main/java/com/nekkora/agent/exec/RunStore.java`
e, no `PlatformService`, a entrega.

| Parte | Comportamento |
|---|---|
| `EventReader` | lê o **próprio** `atividade.jsonl`, sem segundo histórico, só até o que está sincronizado (`durableThrough`, D-AP-5). Linha final incompleta, gravação pendente e arquivo ainda inexistente **não** são perda |
| Lotes | até 500 eventos e 1,5 MB, abaixo dos 2 MiB do servidor |
| `AckCheck` | confere o ACK antes de qualquer avanço: execução certa, `storedThrough` e `settledThrough` separados, sem recuo, sem ir além do enviado, `results[]` e `missing[]` coerentes. Resposta incoerente não avança nem libera nada (AGT-PLT-014) |
| `DeliveryStore` | progresso por execução (`entregas/<id>.json`): `storedThrough`, `settledThrough`, intervalos declarados, posição lida e último relatório aceito. Gravação atômica (temporário e troca), **só depois** do ACK |
| Retomada | depois de reiniciar, continua de `settledThrough + 1`. O reenvio devolve `DUPLICATE` |
| `declaredMissing` (§8.2, D-AP-3) | só para perda definitiva **registrada pelo Agent**: lacuna seguida do aviso `EVENT_GAP`/`AGT-OBS-009`, ou expurgo local (`HISTORY_PURGED` com `purged_until_sequence`). A faixa exata é gravada **antes** do envio e repetida igual no reenvio. Qualquer outra lacuna **para** a entrega (AGT-PLT-020) |
| `INVALID` (D-AP-4) | para naquele número, mantém a cópia e não reenvia em laço. Só a desistência explícita da pessoa vira perda declarada `[n, n]` |
| `CONFLICT` (`conteudo_diferente`) | o original do servidor prevalece e conta; o Agent registra a divergência (AGT-PLT-019) |
| `429` e `503` | sem ACK, nada avança. A espera respeita `Retry-After` (AGT-PLT-012 e AGT-PLT-011) |
| Resposta perdida | sem resposta = nada confirmado. O mesmo lote é reenviado e nunca a automação |
| Retenção atrelada ao ACK (`RunStore.prune`, `ActivityService.enforceQuota`, D-AP-7) | a limpeza poupa as pendentes (até +200); a cota de 96 MiB reduz primeiro as liquidadas. Se precisar apagar uma pendente, a faixa é declarada |

## Testes (transporte simulado; falhas injetadas e identificadas)

| Teste | Controle |
|---|---|
| D2 | rede indisponível durante a execução: fatos locais intactos, a entrega retoma sem duplicar |
| D3 | lote gravado e ACK perdido: `DUPLICATE`, sem avançar antes do ACK |
| D4 | reinício antes e depois de persistir o ACK |
| D5 | `CONFLICT` de conteúdo e `INVALID` (para, sem laço) |
| D6 | linha incompleta e gravação não sincronizada não são perda; perda registrada vira faixa exata gravada antes do envio |
| D7 | ACK malformado, de outra execução, que recua ou vai além do enviado: recusado |
| D8 | credencial revogada: o transporte inteiro para |
| D11 | limites de lote; espera crescente; `503` com `Retry-After` 7 s e `429` com 30 s respeitados |
| D12 | progresso que não pode ser gravado não conta |
| D13 | cota apagou eventos sem ACK: a faixa exata é declarada |
| R1, Q1, Q2 | retenção e cota poupam o que não teve ACK |

## Evidência real

- AGENT-PLATFORM-001:
  - J4: queda real (nginx pausado);
  - J5: ACK perdido (falha injetada, `DUPLICATE`);
  - J6: Agent encerrado à força, retoma de `settled + 1`;
  - J7: 1936/1936, `COMPLETE`.
- Esta missão:
  - C4: **503 real** com o Redis parado; o limitador falha fechado, sem ACK;
  - C5: **perda local real** com `declaredMissing` aceito e `storedThrough < settledThrough` na API;
  - C8: limite do histórico, `LOST_AT_SOURCE` sem nada declarado.
- `429` real não foi provocado: exigiria 600 lotes por minuto. Ele fica coberto por H1 (cabeçalho lido do servidor HTTPS
  real local) e D11.

## Riscos

- Uma lacuna sem explicação para a entrega até a pessoa decidir. Isso é intencional: nada é declarado por suposição.
- `INVALID` só aparece com divergência de versão, porque o Agent valida com as mesmas regras do servidor. Sem um gateway
  divergente, ele não foi provocado na API real.
