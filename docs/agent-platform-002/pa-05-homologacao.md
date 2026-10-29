# PA-05 — homologação integrada com Agent e API reais

Duas jornadas, ambas com o **Agent empacotado** (processo real, sem Java no PATH) contra a **API real** `2e639244…` num
laboratório descartável (stack E2E da própria API, migrations 001–027 em instalação limpa, migrador `0|0|0`). Rótulos:
**[REAL]** processo e API reais; **[INJETADA]** falha controlada e identificada. Nenhum passo usa transporte simulado.

| Jornada | Laboratório | Build | O que provou |
|---|---|---|---|
| AGENT-PLATFORM-001 J1–J9 (PR #2) | `agentlab-001` (parado, preservado) | `e4e5617` | pareamento, execução autorizada, queda de rede, ACK perdido, reinício com adoção, `COMPLETE` 1936/1936, revogação, segredos |
| **AGENT-PLATFORM-002 C1–C9 (PR #3)** | `agentlab-002` (sub-rede própria, parado e preservado) | `2045910` (código de produção igual ao `e4e5617`) | o que a 001 não provou com processos reais (abaixo) |

## Jornada complementar (`tools/platform-lab/jornada-complemento.ps1`)

Evidências: `evidencias/jornada-complemento` (rodada 2), `evidencias/retomada-c8`, `evidencias/tentativa-1` e `evidencias/laboratorio`.

| Passo | Resultado |
|---|---|
| C1 [REAL] | pareado pela janela (código no campo de senha, arquivo apagado); Agent `ACTIVE` no Worker criado pela pessoa; 2 autorizações guardadas |
| C2 [REAL] | heartbeat no banco: `agent_presence.reported_at` 05:04:01.7 → 05:05:02.3 |
| C3 [REAL] | nginx pausado **antes** do início: `AGT-PLT-010`. A execução `ex-6b088b48…` começou pela **autorização guardada** e rodou sem Platform, com 0 registros nesse intervalo. Com a volta: registro sob a autorização `b494a360…` e a versão publicada `19515aea…` |
| C4 [INJETADA] | Redis parado: o limitador do canal falhou fechado (gateway `redis_indisponivel` 0 → 1, **503** com `Retry-After`). Agent: `AGT-PLT-011`. ACKs aceitos durante a falha: 2 → 2. Automação seguiu `RUNNING`; com o Redis de volta, a entrega retomou |
| C5 [INJETADA] | outro programa abriu o `atividade.jsonl` sem compartilhar a escrita por 65 s. A coleta esgotou as tentativas e registrou a lacuna `358-653` com `EVENT_GAP AGT-OBS-009`. O transporte declarou exatamente `358-653` (`PLATFORM_LOSS_DECLARED`) |
| C5 resultado | banco: `stored=357`, `settled=3214` = último local, 2918 eventos + 296 ausentes = 3214, 0 recusas. Consulta da pessoa: `SUCCEEDED/KNOWN`, histórico **`INCOMPLETE` (`LOST_LOCAL`)**, `storedThrough < settledThrough`. Correlação: autorização, versão, Worker, Agent, origem `LOCAL_AUTHORIZED` |
| C6 [REAL] | Agent encerrado à força e, em seguida, a automação `ex-1e2c7149…` encerrada com o Agent fora. Reaberto, ele reconciliou para **`OUTCOME_UNKNOWN`** (`AGT-EXE-025`), sem relançar (0 processos, 1 registro, 1 pasta de saída por execução). Platform: `OUTCOME_UNKNOWN/ENDED/UNKNOWN`, 197 eventos confirmados, resultado parcial pelas transações vistas (`TRANSACTION_STARTED` 23, `TRANSACTION_COMPLETED` 22). As contagens do supervisor ficaram vazias: o Agent mostra "Progresso parcial indisponível: o checkpoint do Framework não existe" e não inventa número (o pacote de laboratório roda sem checkpoint) |
| C7 [REAL] | **rotação real** pela janela (Alt+N) com o código gerado pela pessoa. Credenciais `1|1` → `2|1|1` (total, ativas, revogadas por `ROTATION`), segredo local trocado, resgate apagado. A presença continuou com a credencial nova (05:16:32 → 05:17:32) e houve 0 recusas no canal |
| C8 [REAL] (retomada) | execução `ex-84628598…` sob a **segunda** autorização (`2ac0a30f…`, outra impressão). A coleta atingiu o limite de **bytes** (8 MiB) em 15.047 eventos e gravou `AGT-OBS-005`, sem lacuna de sequência. Banco: `stored=settled=15047`, nada declarado ausente, 0 recusas. Platform: `SUCCEEDED/KNOWN`, histórico **`INCOMPLETE` (`LOST_AT_SOURCE`)** sem `LOST_LOCAL`, 15.047 eventos em ordem e sem repetição |
| C9 | nenhum segredo em evidências, dados, logs dos 6 serviços e envelopes (10 valores na rodada 2: códigos de pareamento e rotação, credenciais anterior e atual, senha e fator da pessoa) |

`LOST_LOCAL`, com `declaredMissing` e `stored < settled`, e `LOST_AT_SOURCE`, com `stored = settled` e nada declarado,
apareceram separados na API real, cada um com a origem certa.

## Placar e tentativas

| Rodada | Placar | Causa da falha |
|---|---|---|
| Tentativa 1 | 6/7 | **roteiro**: a pasta de saída não existia no laboratório novo. O Agent bloqueou o início corretamente (`AGT-EXE-011`), e nada foi registrado na Platform (`agent_runs` vazio). O roteiro passou a criar a pasta. Para a rodada 2, o Agent foi revogado pela Platform e recebeu código novo (`pessoa.py novo-codigo`) |
| Rodada 2 | **21/22** | C1–C7 completos. O C8 parou no **roteiro**: com o painel da execução anterior aberto, a busca do botão "Aprovar este pacote…" (sem atalho de teclado; o roteiro procura pela cor) não achou o botão |
| Retomada C8 (`-SomenteLimite`, Agent reaberto sobre os mesmos dados, vínculo ativo e credencial rotacionada) | 4/5 + consulta repetida | aprovação, execução, entrega e banco OK. A consulta da pessoa falhou no login do laboratório (`mfa: HTTP 401`, TOTP da ferramenta `pessoa.py`). A mesma consulta, só leitura, repetida em seguida, passou: `c8-consulta-platform-repetida.json` |

Nenhuma falha foi do Agent nem da API. As três foram de roteiro ou de ferramenta do laboratório e estão registradas acima
com a correção. A causa exata do 401 do TOTP não foi isolada.

## O que continua sem prova real

- `429`: exigiria 600 lotes por minuto. Fica coberto por H1 (servidor HTTPS real local) e D11.
- `INVALID` e `CONFLICT` de conteúdo: o Agent valida com as mesmas regras do servidor, então só um gateway divergente os
  produz. Ficam cobertos por D5.
- Publicação de versão: a API não tem rota, e a versão publicada é uma fixture declarada (E-AP-4).

## Regressão

| Suíte | Commit | Resultado |
|---|---|---|
| Windows `mvnw clean verify` (no empacotamento do build da jornada) | `2045910` | BUILD SUCCESS |
| Windows `mvnw clean verify` | `3431ba7` | **279** testes, 0 falhas, 0 erros, 2 ignorados (ISO-04 antes da imagem montada; `SourceFileTest`, já existente). O V2 deixou de ser ignorado. BUILD SUCCESS |
| Desktop `-Pdesktop clean verify` | `3431ba7` | **303** + 2 escalas, 0 falhas, 0 erros, 2 ignorados. BUILD SUCCESS |
| Linux Docker `eclipse-temurin:21-jdk-noble` (`git archive`, Maven offline com o repositório do projeto, sem Python) | `3431ba7` | 279 testes, **as mesmas 15 falhas herdadas** (ShortcutTest 13, AgentBootstrapTest 1, SensitiveDataLeakTest 1, dependentes do Windows), 0 erros, 37 ignorados |
| Prova vermelho/verde | `3431ba7` (regra) | M5 (executor citando o transporte por nome completo): vermelho; verde ao reverter (`evidencias/prova-vermelho-verde.txt`) |

## Estado final

`agentlab-002` parado (`compose stop`), com containers, volumes e evidências preservados. `agentlab-001` intocado.
