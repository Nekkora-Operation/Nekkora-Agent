# PA-03 — registro idempotente da execução local e relatórios do supervisor

## Diff (PR #1)

Revisão: `git diff 932e61c d3e0f8a -- src/main/java/com/nekkora/agent/platform/{SupervisorReports,PackageFingerprint,DeliveryStore}.java src/main/java/com/nekkora/agent/ui/ExecutionDialog.java src/main/java/com/nekkora/agent/ui/AgentApp.java`
e, no `PlatformService`, os métodos de vínculo, registro e relatório.

| Parte | Comportamento |
|---|---|
| Vínculo com a autorização (`ExecutionDialog`, `AgentApp`, `DeliveryStore.intencao`) | a pessoa escolhe "Registrar na Platform" (autorização válida para a **impressão do pacote**) ou "Não registrar" (D-AP-1). A autorização é conferida de novo e a intenção é gravada **antes** do início. Se a autorização não valer mais, nada começa |
| `PackageFingerprint` | a impressão do próprio Agent (`package-fingerprint/1`), sem o prefixo, comparada com `packageFingerprint` e com a regra `packageFingerprintRule` (regra desconhecida = não executar por ela). Nunca o nome do pacote, nunca `artifact.sha256` |
| Registro (§6.3) | `POST /runs` com `agentExecutionId`, `authorizationId`, `packageFingerprint` e o supervisor. Repetir devolve o mesmo `runId`. `409` (outra autorização ou outro pacote) para só aquela entrega (AGT-PLT-016) |
| `SupervisorReports` (§7) | lê o `estado.json` **gravado** pelo supervisor: estado, código, saída, contagens, instantes, adoção, suspensões e travas. Trata `APPLIED`, `UNCHANGED`, `STALE` e `CONFLICT`; `CONFLICT` não volta em laço (AGT-PLT-017) |
| Passos intermediários (D-AP-6) | se o registro está vários passos adiante do último relatório aceito, a Platform recebe antes os passos que o registro **comprova** |
| Estados terminais e `OUTCOME_UNKNOWN` | o relatório leva o estado como o supervisor gravou. `OUTCOME_UNKNOWN` continua desconhecido, sem nova tentativa |
| Resultados parciais | as contagens (`counts`) do registro vão como estão, inclusive o progresso parcial que a reconciliação anota (`withPartialProgress`) |

## O que não acontece

- Nenhuma execução da Platform para pacote sem autorização baixada. Sem autorização, a execução é só local e não é
  registrada (§13 continua proposta).
- O transporte não cria versão, autorização ou Worker. Só usa os identificadores que a Platform devolveu.
- O executor não mudou de semântica. O transporte só **lê** `estado.json` e `atividade.jsonl` (arquitetura em [pa-02](pa-02-transporte-e-credencial.md)).

## Testes

| Teste | Controle |
|---|---|
| D1 | execução registrada sob a autorização, eventos em ordem, desfecho do supervisor e envio concluído |
| D9 | autorização que deixou de ser do Worker é recusada (403) e só esta entrega para; vínculo novo não envia o do anterior |
| D10 | relatório atrasado (`STALE`), resultado desconhecido que continua desconhecido e contradição (`CONFLICT`, sem laço) |

## Evidência real

- AGENT-PLATFORM-001 J3, J7: registro sob a autorização pré-escolhida; relatórios `RUNNING:APPLIED`, `RUNNING:APPLIED`
  (adoção) e `SUCCEEDED:APPLIED`; consulta `SUCCEEDED/ENDED/KNOWN`.
- Esta missão:
  - C3: registro **depois** de um início offline, sob a autorização guardada;
  - C5: correlação completa na consulta (autorização, versão publicada, Worker, Agent, `LOCAL_AUTHORIZED`);
  - C6: `OUTCOME_UNKNOWN` real com resultado parcial, mostrado como `UNKNOWN`;
  - C8: segunda autorização, para outra impressão.

## Riscos

- D-AP-1, execução só local com a máquina pareada, aguarda a confirmação da titular.
- O relógio do Agent vai como **informado**, e a Platform não o usa para ordenar.
