# C4 — homologação integrada final (Agent e API reais, laboratório novo)

| Item | Valor |
|---|---|
| Agent | distribuição Windows montada de um **clone novo** (`git clone --no-local` + `tools/package-windows.ps1 -NoZip`, com os testes) no commit `7986e84afc656c1c7e2b791aa4411e55fe0ee961`, árvore `src` `2ab110d1e25e0ba59f3274c0658b28d581f964aa`. `VERSOES.txt` da cópia de laboratório confere o commit. `nekkora-agent.jar` `343a72d7…68b00dc` |
| API | `main` `2e639244c22e0f439860344ac46557896431f51a` (worktree `npa-main-agentlab`), stack E2E, migrations 001–027 em instalação limpa pelo modelo do migrador |
| Laboratório | `agentlab-003`, novo e descartável. Sub-rede `10.251.49.0/24` (`compose.lab-rede-49.yml`), portas 55877/55878, convivendo com os `agentlab-001` (47) e `agentlab-002` (48) preservados e parados. Segredos em `~/.nekkora/agent-lab/agentlab-003` |
| Pacotes | de laboratório no formato do Studio (manifesto 1.5.0, eventos reais do `nekkora_rpa` 0.3.0, dados fictícios). Impressões calculadas pela função do Agent e **idênticas** às dos laboratórios anteriores: `18adddf9…`, `42142d2c…`, `9848837f…`, `2c8c3e2d…` |
| Versão publicada | **fixture declarada** no banco descartável (E-AP-4: a API não tem rota de publicação). Worker, códigos, autorizações, revogações e rotação vêm das rotas reais, pela pessoa com MFA e step-up |
| Rótulos | **[REAL]** processo e API reais; **[INJETADA]** falha controlada e identificada |
| Evidências | `evidencias/c4/` (sem dados do Agent nem segredos: varredura de 8 segredos em 64 arquivos, 0 ocorrências) |

## Matriz PASS/FAIL

| # | Cenário | Rodada | Resultado | Fonte |
|---|---|---|---|---|
| 1 | pareamento com MFA humano (código gerado pela pessoa com step-up) e segredo DPAPI | J2 [REAL] | PASS (vínculo sem segredo, credencial só no arquivo protegido, Agent ACTIVE no Worker) | `jornada-20261029T154554Z` |
| 1 | rotação sem vazamento | C7 [REAL] | PASS (credenciais `1|1` → `2|1|1`, presença continua, 0 recusas) | `complemento-20261029T160046Z` |
| 1 | revogação | J8 [REAL] | PASS (primeira chamada recusada, transporte parado, 1 recusa no gateway) | jornada |
| 2 | versão autorizada, execução sem Studio aberto, SUCCEEDED, eventos ordenados, consulta pela API | J3, J7 [REAL] | PASS (`SUCCEEDED/KNOWN`, histórico `COMPLETE`, 1936/1936 eventos, 0 recusas) | jornada |
| 3 | nginx pausado durante a execução | J4 [INJETADA] | PASS (`AGT-PLT-010`, automação segue, entrega retoma) | jornada |
| 3 | início offline pela autorização guardada | C3 [REAL] | PASS (vínculo sem rede, `RUNNING` sem Platform, registro depois da volta sob `33e2eb7e`/versão `f1f401b9`, conferido no banco) | complemento + banco |
| 3 | Redis parado | C4 [INJETADA] | PASS (503 real, `AGT-PLT-011`, nenhum ACK sem o servidor, entrega retoma) | complemento |
| 4 | ACK perdido: reenvio idempotente, sem segundo processo | J5 [INJETADA] | PASS (`DUPLICATE`, nada duplicado no servidor) | jornada |
| 5 | reinício do Agent com adoção do processo original | J6 [REAL] | PASS (`EXEC_RECONCILED`, PID vivo, uma raiz, sem relançar) | jornada |
| 6 | `LOST_LOCAL`/`declaredMissing` | C5 [INJETADA] | PASS (lacuna `360-659`, `AGT-OBS-009`, declarado = lacuna exata, `stored=359 < settled=3214`, `INCOMPLETE/LOST_LOCAL`) | complemento |
| 6 | `LOST_AT_SOURCE` | C8 [REAL] (retomada) | PASS (limite de 8 MiB, `AGT-OBS-005`, `stored=settled=15043`, nada declarado, `INCOMPLETE/LOST_AT_SOURCE`, 15043 eventos em ordem) | `complemento-retomada-c8-20261029T161559Z` |
| 6 | `OUTCOME_UNKNOWN` | C6 [REAL] | PASS (`AGT-EXE-025`, sem relançar. API `OUTCOME_UNKNOWN/ENDED/UNKNOWN`, transações vistas 24/23) | complemento |
| 7 | credencial revogada para o transporte sem falhar a execução local | J8 [REAL] | PASS (execução local seguinte `SUCCEEDED`, sem registro e sem vínculo de entrega) | jornada |
| 8 | alteração de destino | D1/D2 [REAL] | PASS (outra origem: 0 requisições `/agent/` em 75 s e presença parada; origem de volta: presença retoma pela credencial guardada, sem novo pareamento) | `destino-20261029T163251Z` |
| 8 | conteúdo adulterado | A6 [REAL-API] | PASS (`CONFLICT conteudo_diferente`, o original fica) | `laboratorio/c4-adversario.json` |
| 8 | lote `INVALID` | A7 [REAL-API] | PASS (`INVALID unknown_field`, contado em `invalid_refused=1`) | adversário |
| 8 | reenvio idempotente | A5 [REAL-API] | PASS (`DUPLICATE` ×3) | adversário |
| 8 | contrato ausente ou maior | A9 [REAL-API] | PASS (400 `unsupported_contract`) | adversário |
| 8 | token vencido | A8 [REAL-API] | PASS parcial: `expires_at` é **imutável** no banco (gatilho `prevent_agent_credential_origin_change` recusa até o administrador), então o vencimento não é antecipável no laboratório. A credencial **encerrada** pela pessoa → 401 no heartbeat e no lote. O vencimento em si é conferido no código (`MachineAuth`: `expires_at > clock_timestamp()`) | adversário |
| 8 | troca de relógio | — | **não executado na máquina** (o relógio do Windows da titular não é alterado). Coberto por `AuthorizationClockTest` (correção D-AP-2) e pela regra da API (`authorization_state_at_registration` pela hora do servidor) | C1 |
| 9 | outro Worker | A1, A2 [REAL-API] | PASS (não vê a autorização do outro Worker; registrar sob ela → 403) | adversário |
| 9 | outra organização ou workspace | A3 [REAL-API] + IT | PASS (autorização fora da organização → 403). Cross-tenant e workspace pela pessoa: `AgentAdminIT` (404/401) | adversário + E-AP-3 |
| 9 | sentinelas de segredo e dados pessoais | J9, C9 [REAL] | PASS (6 e 10 valores em evidências, dados, logs de 5 e 6 serviços e envelopes: 0 ocorrências) | jornada, complemento |

## Placar e tentativas

| Rodada | Placar | Causa das falhas |
|---|---|---|
| J1–J9 tentativa 1 | 6/7 | **roteiro**: num laboratório novo a pasta de saída não existia, e o Agent recusou corretamente com `AGT-EXE-011` antes de começar. `jornada.ps1` passou a criá-la (a complementar já criava). Dados preservados em `tentativa-1` |
| J1–J9 rodada 2 | **22/22** | — |
| C1–C9 | 18/22 | C3 e C5: o **roteiro** confere contra a autorização do `preparar`. Neste laboratório reaproveitado o `preparar` foi da J1–J9, e o banco mostra o registro correto (tabela acima). C6 "uma pasta por execução": o **roteiro** contou 4 pastas, mas 2 eram da J1–J9 (`ex-30808…`, `ex-bccef…`). As 2 execuções da complementar têm 1 pasta cada. C8: o botão de aprovação não foi achado pela cor depois de uma execução na mesma sessão (mesma falha da 002) |
| C8 retomada | **5/5** | — |
| destino tentativa 1 | 1/2 | **roteiro**: o contador do nginx usava uma função que descarta a saída (`Dc`). Corrigido |
| destino rodada 2 | **2/2** | — |
| adversário | **10/10** | antes: `UPDATE expires_at` recusado pelo gatilho (prova de imutabilidade) e coluna `conflicts` inexistente (roteiro). Uma consulta da pessoa recebeu `mfa: HTTP 401` (TOTP da ferramenta, como na 002) e passou na repetição |

Nenhuma falha foi do Agent nem da API.

## Limitações declaradas

- **Pacote real do Studio `da7bb94`** (impressão `5664f1a5…`) não foi executado sob autorização da Platform nesta missão.
  Ele automatiza navegador com dados de entrada próprios. A impressão confere pelo vetor V2, e a execução local real dele
  foi provada na COMPAT-001. Os pacotes de laboratório são do formato do Studio, com eventos reais do Framework 0.3.0.
- Versão publicada como **fixture declarada** (E-AP-4, próxima missão).
- Vencimento de credencial e troca de relógio provados por código e teste, não antecipados no laboratório (acima).
- 429 real não foi provocado (exigiria 600 lotes por minuto). Coberto por D11/H1.
