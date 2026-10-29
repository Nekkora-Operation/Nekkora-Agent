# `estado.json` 1.1.0 e eventos novos do supervisor

`nekkora-agent/execution` **1.1.0** é aditiva sobre a 1.0.0 da DESKTOP-001. O leitor do Agent aceita as duas. Uma
versão maior que 1.1.0, ou um valor fora dos enums abaixo, torna o registro **ilegível**: aparece como "não pôde ser
lido" e nunca ganha um valor inventado.

## Campos novos

| Campo | Formato | Quando aparece | Significado |
|---|---|---|---|
| `origin` | `{"kind": "LOCAL_UI" \| "STUDIO_LOCAL" \| "UNKNOWN"}` | sempre (gravação 1.1.0) | **quem pediu** a execução (D-SA-05). Nesta versão, todo registro criado pelo Agent é `LOCAL_UI`; `STUDIO_LOCAL` está reservado para o canal. Um registro 1.0.0 é lido como `UNKNOWN`. Não diz nada sobre quem gerou o pacote |
| `cancellation` | `{"requested": instante, "source": "LOCAL_UI" \| "AGENT_SHUTDOWN", "outcome": "PENDING" \| "CONFIRMED" \| "NOT_EFFECTIVE"}` | só quando houve pedido de cancelamento | D-SA-04. `PENDING`: gravado antes de encerrar a árvore. `CONFIRMED`: o Agent encerrou, e o estado é `CANCELLED`. `NOT_EFFECTIVE`: o processo terminou antes, e o estado é o do protocolo |
| `recovered` | booleano | sempre (gravação 1.1.0) | A02: o desfecho foi lido do protocolo **conferido** depois de um reinício do Agent. O fim do processo não foi observado, então `exitCode` é `null` |
| `progress` | `{"available": bool, "transactions": {estado: n}, "delivered": n, "problem": código \| null}` | só em pacote do Studio sem resultado final (cancelado, prazo, desconhecido, falha sem protocolo) | A03: progresso **parcial** conferido pela integridade do checkpoint do Framework. Nunca é desfecho. `available: false` traz o motivo (`CHECKPOINT_MISSING`, `CHECKPOINT_INVALID`, `CHECKPOINT_INTEGRITY`, `CHECKPOINT_UNSUPPORTED`, `CHECKPOINT_TOO_LARGE`, `CHECKPOINT_LOCKED` ou `OUTPUT_UNKNOWN`), e isso nunca quer dizer "nada foi feito" |

Os campos 1.0.0 não mudam de significado. `counts` continua vindo só do resultado **final** publicado pelo processo.

## Exemplo real (a2 desta missão: cancelamento pela interface)

```json
  "state": "CANCELLED",
  "code": "AGT-EXE-024",
  "exitCode": 1,
  "counts": {},
  "origin": {"kind": "LOCAL_UI"},
  "cancellation": {"requested": "2026-10-28T14:…Z", "source": "AGENT_SHUTDOWN", "outcome": "CONFIRMED"},
  "progress": {"available": true, "transactions": {"IN_PROGRESS": 1, "SUCCEEDED": 10}, "delivered": 10, "problem": null},
  "recovered": false
```

## Eventos novos no `eventos.jsonl` do supervisor (só nomes, códigos e números)

| Evento | Campos | Quando |
|---|---|---|
| `CANCEL_REQUESTED` | `source` | o supervisor gravou o pedido como `PENDING` (antes de encerrar a árvore) |
| `CANCEL_CONFIRMED` | `source` | a árvore foi encerrada e o estado é `CANCELLED` |
| `CANCEL_NOT_EFFECTIVE` | `source` | o processo terminou por conta própria antes do encerramento |
| `PARTIAL_PROGRESS` | `available`, `transactions` (total), `delivered`, ou `problem` | o progresso parcial foi lido (só com o processo já terminado) |
| `PROTOCOL_REJECTED` | `reason` (`NO_OUTPUT_FOLDER`, `NO_PACKAGE_COPY`, `NO_PROTOCOL`, `PROTOCOL_INVALID`, `NOT_FINAL`, `OLDER_THAN_LAUNCH`, `NO_ENGINE_COMPLETED` ou `ENGINE_MISMATCH`) | depois de um reinício, o protocolo encontrado não valia como desfecho, e o estado continua `OUTCOME_UNKNOWN` |
| `<estado final>` | + `recovered: true` | o desfecho veio do protocolo conferido depois de um reinício |

## Compatibilidade

- O Agent desta versão lê os registros 1.0.0 (testado com três registros reais da compat-001).
- O Agent anterior (ZIP `cd04a7c4…`) lê **só** 1.0.0. Se alguém voltar a ele depois de usar esta versão, os registros
  1.1.0 aparecem como "não pôde ser lido". Nada se perde no disco. **Voltar de versão não é suportado.**
- Ferramentas externas que conferem `version == "1.0.0"`, como o conferidor da compat-001, precisam aceitar 1.1.0. A
  cópia usada nesta missão (`conferir-sal.py`) já aceita.
