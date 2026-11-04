# AGENT-PYTHON-001: monitorar Python externo e executar pela Platform

Missão de 03/11/2026. **Dono principal:** Nekkora-Agent. São dois marcos, cada um com aceite próprio:

- **Marco 1 (M1):** monitorar um Python externo que o agendador do cliente dispara.
- **Marco 2 (M2):** gerar um pacote externo e dispará-lo manualmente pela Platform.

## 0. Levantamento (03/11, antes de qualquer escrita)

| Repositório | `main` | Base desta missão | Branch | PRs abertos | Sobreposição |
|---|---|---|---|---|---|
| Agent | `ce30397` | `main` `ce30397` (o repositório integra direto na `main`: PR #6) | `feat/agent-python-001` | nenhum | — |
| API | `ad2764b` | `dev` `770b31f`, que já tem 025, 026, 027, 028 e 029. A `main` tem 4 commits a mais, só de operação de e-mail (#43), que esta missão não usa | `feat/agent-python-001` | nenhum | nenhuma. Nenhuma branch remota tem migration `03x` (conferido em todas, 03/11) |
| Web | `f9d2646` | `dev` `8c75eb1`. A `main` tem 2 commits a mais (#20, Makefile) | `feat/agent-python-001` | #22 (sessão entre abas) e #23 (MFA após cadastro) | nenhuma. Os dois mexem só em identidade, login e sessão; esta missão mexe em `agents/`, `product/` e `api/` |
| Studio | `14b30f7` | não é alterado | — | nenhum | — |

Trabalho em worktrees exclusivos (`py1-agent`, `py1-api`, `py1-web`). Laboratório novo `agentlab-py1` com sub-rede, portas,
volumes e segredos próprios. Ficam intocados: os checkouts dos outros times, o `frontlab-local`, a Platform pessoal, o
pareamento em uso e as evidências anteriores.

## 1. Matriz: capacidade existente × lacuna × contrato × dono

| # | Capacidade existente (onde) | Lacuna comprovada | Contrato proposto | Arquivos afetados | Dono | Depende de |
|---|---|---|---|---|---|---|
| 1 | Pareamento, credencial `nka1`, heartbeat (Agent `platform/`, gateway 025) | nenhuma | reutilizar. A capacidade nova vai no heartbeat (`EXTERNAL_PYTHON_1_0`, `RUN_REQUEST_1_0`) | gateway `GatewayService.CAPABILITIES`, 030 (CHECK de `agent_presence`), Agent `MachineClient` | API (CHECK/lista) + Agent (envio) | — |
| 2 | Autorização local por Worker/workspace com validade e revogação (025 §6.1) | **exige pacote Studio.** `local_run_authorizations.automation_version_id`, `package_fingerprint` e `package_fingerprint_rule` são NOT NULL, a regra tem `CHECK IN ('nekkora-agent/package-fingerprint/1')` (025:156-158) e o gatilho exige `command_json.agent_package` (025:414-419). O Agent recusa item sem impressão (`AuthorizationCache.parseItem`) | **origem explícita** `sourceKind`: `STUDIO_PACKAGE` (atual, padrão), `EXTERNAL_PACKAGE` (M2) e `EXTERNAL_MONITORED` (M1, **sem versão e sem impressão**: a Platform não conhece código nenhum). Nada de impressão inventada | 030; `AgentAdminService`, `AgentController`, OpenAPI; Agent `AuthorizationCache`, `MachineClient` | API | 030 |
| 3 | Registro idempotente por (`agent_id`, `agent_execution_id`) (025 §6.3) | `packageFingerprint` é obrigatório (`GatewayService.register`) e `agent_runs.package_fingerprint`/`automation_version_id` são NOT NULL | `packageFingerprint` ausente **se e somente se** a autorização for `EXTERNAL_MONITORED`. `runRequestId` opcional (M2) | 030; `GatewayService`, `GatewayController`, OpenAPI do gateway; Agent `MachineClient`, `Delivery` | API + Agent | 030 |
| 4 | Relatório do supervisor, eventos OBS 1.0.0, ACK, retomada (025 §7–8; Agent `PlatformService`, `DeliveryStore`) | nenhuma no canal. No Agent, a entrega nasce só da janela (`prepare` → `link`) | reutilizar. O lançador grava um **pedido de vínculo** na pasta da execução (`plataforma.json`) e o Agent cria a entrega ao encontrá-lo. Só o processo do Agent escreve `plataforma/entregas` | Agent `PlatformService`, `exec/External*`, `app/` | Agent | 2, 3 |
| 5 | Supervisor de processo, `estado.json`, reconciliação, `OUTCOME_UNKNOWN` (Agent `exec/`) | não há um tipo para processo do cliente; a reconciliação do `ExecutionController` adotaria o processo de outro supervisor | tipo `EXTERNAL_MONITORED` em `ExecutionPackage.Kind`, origem `EXTERNAL_SCHEDULER`; o lançador é um processo próprio e supervisiona o filho; o Agent só reconcilia quando o lançador morreu | Agent `exec/ExternalLaunch`, `ExternalBindings`, `ExecutionRecord`, `ExecutionController.reconcile` | Agent | — |
| 6 | Coleta da atividade a partir do `estado.json`/`eventos.jsonl` (Agent `obs/ActivityCollector`) | nenhuma: a coleta já pega toda execução sem fechamento (`catchUp`) e não espera Framework fora de `STUDIO_PYTHON` | reutilizar | — | Agent | 5 |
| 7 | Leituras `agent-runs` com as quatro dimensões (025 §9–10; Web `supervision.tsx`) | não distinguem origem do pacote nem pedido da Platform; supõem versão | `sourceKind`, `runRequestId`, `automationVersionId` nulo em `EXTERNAL_MONITORED`, `origin` `LOCAL_AUTHORIZED` ou `PLATFORM_REQUEST` | `AgentRunReads`, OpenAPI; Web `api/agents.ts`, `supervision.tsx` | API (leitura) + Web (tela) | 030 |
| 8 | Ciclo de vida: versão, `PUT …/artifact`, `PackageInspector`, publicação (028) | só aceita `manifest.json` do Studio (`codegen-manifest` 1.4+); `automation_artifacts.package_kind = 1` e a regra `/1` são fixas por CHECK | tipo de pacote 2 = `EXTERNAL_PYTHON` com descritor `nekkora-external.json` e regra **própria** `nekkora-agent/external-python-fingerprint/1` (§3). A regra `/1` dos pacotes Studio **não muda** | 030; `PackageInspector`, `AutomationLifecycleService`; Agent `ExecutionPackage` | API (inspeção) + Agent (cálculo local) | 030 |
| 9 | Despacho: só desenho (contrato §14, "pausado") | **não existe** canal de comando | **pedido de execução** (`run_requests`) puxado pelo Agent, com decisão durável no Agent (§4) | 030; gateway (rotas novas), Platform (rotas novas), OpenAPI ×2; Agent `platform/RunRequests*`, `app/`; Web tela de execução | API + Agent + Web | 030 |
| 10 | Aprovação local por impressão (`ApprovalStore`) | nenhuma | reutilizar para o pacote externo. A aprovação do pacote continua distinta da autorização | — | Agent | 8 |

**Um dono por mudança:** a migration, o gateway, as rotas e a OpenAPI são da API. Os tipos gerados e as telas são do Web.
Lançador, vínculo local, reconciliação e caixa de pedidos são do Agent. Nenhum PR ativo sobrepõe esses arquivos (§0).

## 2. Marco 1: monitoramento (contrato)

**Na Platform (pessoa):**
- `POST /api/v1/o/{org}/workspaces/{ws}/local-run-authorizations` aceita `{"sourceKind":"EXTERNAL_MONITORED", "workerId", "automationId", "validUntil"}`.
- Exige `automation.execute` no workspace e step-up, como hoje. Automação e workspace precisam estar ativos e o Worker
  autorizado no workspace.
- Sem `sourceKind`, nada muda.

**No canal da máquina:**
- `GET /agent/api/v1/authorizations`: cada item ganha `sourceKind`. No `EXTERNAL_MONITORED` vêm nulos
  `automationVersionId`, `packageFingerprint` e `packageFingerprintRule`. Um Agent anterior recusa esse item, porque ele não
  tem impressão (falha fechada: o item não serve a nada).
- `POST /agent/api/v1/runs`: o `packageFingerprint` vai ausente com autorização monitorada e é obrigatório com as demais.
  Divergência = `403 pacote`.
- Todo o resto é igual: idempotência, relatórios, eventos, ACK e `authorization_state_at_registration`.

**Na máquina (Agent):**
1. **Vínculo local aprovado** (`nekkora-agent vincular`, ato da pessoa no console da máquina): `automationId` → interpretador,
   script e pasta de trabalho, todos caminhos absolutos e conferidos.
   - Fica em `<dados>/externo/vinculos.json`, que nunca é transmitido. Caminho, argumento, ambiente e saída nunca saem da máquina.
   - O processo nunca é adotado pelo nome.
2. **Lançador** (`nekkora-lancar.cmd --vinculo <nome> -- <argumentos do cliente>`): é a única mudança na tarefa agendada.
   - Confere o vínculo: interpretador e script iguais aos aprovados, pasta de trabalho atual igual à aprovada.
   - Confere a autorização guardada `EXTERNAL_MONITORED` daquela automação, no prazo pelo relógio com piso (D-AP-2). **Sem
     autorização válida, não inicia** e sai com o código 120. Não existe autorização offline inventada.
   - Grava `estado.json` (READY → STARTING) e o pedido de vínculo `plataforma.json`, cria o processo por `ProcessBuilder` com
     lista de argumentos (sem shell) e herda a entrada e as saídas. O agendador continua vendo e redirecionando tudo; o Agent
     não guarda a saída.
   - Registra a posse (PID e início) e o estado RUNNING; espera; grava o código de saída e o desfecho.
   - **Devolve ao agendador o mesmo código de saída do script.**
3. **Desfecho:**

| Fato observado | `state` | `code` | Leitura |
|---|---|---|---|
| código 0, sem sinal | `SUCCEEDED` | `AGT-EXT-001` | "terminou sem erro sinalizado (código 0)". **Não** é sucesso de negócio |
| sinal `BUSINESS_SUCCESS` e código 0 | `SUCCEEDED` | nulo | sucesso de negócio sinalizado pelo cliente |
| código ≠ 0 | `FAILED` | `AGT-EXT-002` | terminou com erro (o código vai em `exitCode`) |
| sinal `BUSINESS_FAILURE` | `FAILED` | `AGT-EXT-003` | falha de negócio sinalizada |
| lançador morto sem observar o fim | `OUTCOME_UNKNOWN` | `AGT-EXT-004` | desconhecido, sem nova tentativa |
| processo não pôde ser criado | `FAILED` | `AGT-EXT-005` | nada rodou (o lançador devolve 121) |

   O sinal é opcional e não exige importar nada da Nekkora. O lançador põe em `NEKKORA_SINAL` um caminho, e o script pode
   gravar ali `{"schema":"nekkora-agent/external-signal","version":"1.0.0","outcome":"BUSINESS_SUCCESS"}`.
4. **Entrega:** o Agent (janela/bandeja) encontra o `plataforma.json`, cria a entrega e faz o registro, os relatórios e os eventos
   pelo mesmo transporte. Se o canal cai, o processo local não é tocado. A retomada continua de `settledThrough + 1`, e o
   reenvio nunca cria outra execução (registro idempotente e `DUPLICATE` nos eventos).
5. **Reconciliação:** o Agent só fecha uma execução externa como `OUTCOME_UNKNOWN` quando o lançador dono dela já não existe
   (PID e início não conferem). Com o lançador vivo, ela não é tocada.

**Lacunas declaradas:**
- o Agent precisa ter rodado alguma vez para baixar as autorizações;
- a entrega só acontece com o Agent aberto. O lançador nunca fala com a rede.

## 3. Marco 2: pacote externo (regras de identidade)

| Medida | Regra | Sobre o quê | Quem calcula |
|---|---|---|---|
| `artifact.sha256` | SHA-256 dos bytes do ZIP | o arquivo enviado | API (028, inalterado) |
| impressão de pacote **Studio** | `nekkora-agent/package-fingerprint/1` (contrato §6.4, **inalterada**) | `manifest.json` + `files` | Agent e API |
| impressão de pacote **externo** | `nekkora-agent/external-python-fingerprint/1` | `nekkora-external.json` + `files` | Agent, API e o gerador |

`external-python-fingerprint/1`:
- tem o mesmo algoritmo e a mesma normalização da `/1` (UTF-8 válido: `CRLF`→`LF`; senão, os bytes);
- o texto canônico é a linha `nekkora-agent/external-python-fingerprint/1`, a linha `EXTERNAL_PYTHON`,
  `nekkora-external.json\t<hash>` e as linhas `<caminho>\t<hash>` em ordem crescente;
- uma regra própria evita mudar a `/1`: a regra é a primeira linha do texto, então um pacote não passa por outro tipo.

Descritor `nekkora-external.json` (`schema` `nekkora-agent/external-python-package`, `version` `1.0.0`):
- `name`, `version` (semver do projeto), `entry` (relativo, `.py`, dentro da raiz);
- `python` (`>=3.10,<3.14` ou `==3.12.*`);
- `dependencies` (`["nome==x.y.z"]`, só versões exatas);
- `files` (`{"caminho": "sha256:…"}`) e `generator`.

Recusas:
- arquivo alterado (hash diferente);
- entrada fora da raiz ou fora de `files`;
- caminho absoluto, `..`, `\` ou `:`;
- ligação;
- dependência sem `==`;
- `venv`, `.env`, `__pycache__` ou credenciais na lista;
- descritor com chave repetida.

A API **não executa** nada na inspeção.

**Gerador** (`tools/external-package/nekkora_empacotar.py`):
- só biblioteca padrão, sem dependência Nekkora;
- monta a lista a partir da pasta do projeto e exclui `.venv`, `venv`, `.git`, `__pycache__`, `.env*`, `*.pem`, `*.key` e
  dados (`dados/`, `*.csv` fora de `include`);
- lê as dependências de `requirements.txt` (só `==`);
- grava o descritor e um ZIP determinístico, com data fixa e ordem fixa;
- imprime o sha256 e a impressão.

## 4. Marco 2: pedido de execução (contrato fechado antes da implementação)

**Identificador:** `runRequestId` (UUID da Platform). O destino é a organização (RLS), o workspace, a autorização
(Worker + versão + impressão) e o `agentId` escolhido (associado ao Worker). Validade: `validUntil` = criação + 60 s … 24 h
(padrão 15 min).

**Platform (pessoa):**
- `POST /api/v1/o/{org}/workspaces/{ws}/run-requests` `{authorizationId, agentId, validForSeconds?, requestKey}`:
  - exige `automation.execute` + step-up;
  - a autorização precisa estar ativa e ser de pacote (`STUDIO_PACKAGE` ou `EXTERNAL_PACKAGE`);
  - o Agent precisa estar associado ao Worker;
  - `requestKey` (UUID do navegador) torna a repetição idempotente: a mesma chave devolve o mesmo pedido.
- `GET …/run-requests?workspaceId=&cursor=&limit=` e `GET /api/v1/o/{org}/run-requests/{id}`: estado e execução ligada (`runId`
  e o desfecho do supervisor).
- `POST /api/v1/o/{org}/run-requests/{id}/revoke`: só antes da decisão (`REQUESTED` ou `DELIVERED`). Depois dela: `409`.

**Máquina:**
- `GET /agent/api/v1/run-requests` devolve os pedidos `REQUESTED`/`DELIVERED` deste Agent, no prazo e não revogados. A primeira
  leitura marca `DELIVERED`. Cada item: `{runRequestId, authorizationId, automationVersionId, packageFingerprint,
  packageFingerprintRule, validUntil, requestedAt}`. **Não traz** comando, caminho, argumento nem segredo.
- `POST /agent/api/v1/run-requests/{id}/decision`:
  - `{decision:"ACCEPTED", agentExecutionId}`: aceita só no prazo (`409 expired`) e sem revogação (`409 revoked`);
  - `{decision:"REFUSED", reason}`: os motivos vêm de uma lista fechada (`PACKAGE_NOT_PRESENT`, `PACKAGE_NOT_APPROVED`,
    `AUTHORIZATION_UNUSABLE`, `ENVIRONMENT_INCOMPATIBLE`, `BUSY`, `EXPIRED`);
  - `{decision:"NOT_STARTED", reason}`: o pedido foi aceito e o processo comprovadamente não foi criado;
  - repetir a mesma decisão devolve o mesmo resultado; uma decisão diferente devolve `409 decision_conflict`.
- `POST /agent/api/v1/runs` com `runRequestId`: o pedido precisa estar `ACCEPTED` para este Agent, com a mesma autorização e o
  mesmo `agentExecutionId`. O pedido passa a `RUN_LINKED`.

**Estados do pedido e o que a tela mostra:**

| Estado | Tela | Prova |
|---|---|---|
| `REQUESTED` | Solicitação registrada · aguardando a máquina | linha gravada |
| `DELIVERED` | Entregue à máquina · aguardando decisão | leitura pela máquina (entregar **não** prova início) |
| `ACCEPTED` | Aceita pela máquina · início ainda não confirmado | decisão da máquina |
| `REFUSED` / `NOT_STARTED` | Recusada / não iniciada, com o motivo | decisão da máquina |
| `RUN_LINKED` | Execução registrada; iniciada só se `startedAt` veio do supervisor; desfecho do supervisor | registro + relatórios |
| `EXPIRED` | Expirada sem decisão | relógio do servidor |
| `REVOKED` | Revogada antes da decisão | ato da pessoa |

Máquina desconectada: o pedido continua `REQUESTED` até ela voltar ou o prazo vencer. Nunca vira "em execução" por inferência.

**Durabilidade no Agent** (`<dados>/plataforma/pedidos/<runRequestId>.json`, gravação atômica):
1. `RECEIVED`: gravado antes de qualquer decisão. Um pedido repetido com o mesmo id nunca gera outra decisão.
2. `DECIDED`: a decisão e, se aceita, o `agentExecutionId` reservado. São gravados **antes** do POST e reenviados até a resposta.
3. `CONFIRMED`: o servidor confirmou a aceitação. Só aqui o lançamento pode acontecer.
4. Lançamento pelo `ExecutionController` com o `agentExecutionId` reservado. O `estado.json` STARTING é gravado **antes** da
   criação do processo (comportamento atual).
5. Janela entre STARTING e o registro da posse do processo: depois de uma queda, a reconciliação atual fecha como
   `OUTCOME_UNKNOWN` (não dá para provar se o processo começou). O pedido fica `RUN_LINKED` com o desfecho desconhecido
   visível, e **nada é relançado**.
6. Aceito e confirmado, mas sem nenhum `estado.json`: o processo não foi criado. Isso vira `NOT_STARTED` e nunca é relançado
   em silêncio.

**Garantia declarada:** no máximo um processo por `runRequestId` e por máquina, enquanto a pasta de dados do Agent existir. Não
há promessa de "exatamente uma vez" sob toda falha. Uma queda na fronteira do início vira resultado desconhecido, que fica visível.

**Fora:** distribuição de pacote, instalação de dependências, agendamento pela Platform, cancelamento remoto, Studio, adoção
de processos e Hosting.

## 5. Provas dirigidas de falha (só laboratório) e o ponto exato

**Fora da distribuição normal (commit `81f09ea`).**
- O código distribuído tem apenas os pontos nomeados de `core/FaultPoints`, que não fazem nada sem uma implementação.
- Quem derruba o Agent ou descarta uma resposta é `tools/lab-faults/` (`LabFaults`). Ele vira o JAR separado `nekkora-agent-lab-falhas.jar` (`tools/lab-faults/build.ps1`), que o roteiro do laboratório acrescenta só à **cópia** de teste, com classpath, `-Dnekkora.agent.lab.falha=<falha>` e o arquivo de registro.
- **Como isso é conferido:**
  - `tools/package-windows.ps1` recusa uma distribuição que contenha esse JAR, uma classe `labfaults`, um serviço `FaultPoints$Hook` ou uma opção `nekkora.agent.lab.*`;
  - `LabFaultsAbsentTest` recusa `halt(`, essas propriedades e qualquer implementação em `src/main`;
  - a jornada confere, antes e depois de cada falha, que a cópia normal está limpa.

| Falha | Ponto (`FaultPoints.Point`) | O que já aconteceu | O que ainda não aconteceu |
|---|---|---|---|
| `perderConfirmacaoDecisao` | `DECISION_RESPONSE_RECEIVED` | a Platform gravou a decisão e respondeu com sucesso; o Agent recebeu a resposta | aplicar a resposta (o pedido continua `DECIDED` no Agent e a decisão é reenviada) |
| `quedaAposStarting` | `STARTING_SAVED_BEFORE_PROCESS` | pré-lançamento gravado (`plataforma.json`), `estado.json` = STARTING, evento STARTING | cópia do pacote, pasta de saída, `ProcessBuilder.start` e posse do processo; **o Python não chega a iniciar** |

**Prova repetida no laboratório** (distribuição `81f09ea`, JAR de falhas só na cópia; `evidencias/m2-falhas/jornada-m2-falhas.txt`):
- **S2:** o registro mostra uma resposta descartada em `DECISION_RESPONSE_RECEIVED`. Foram 2 decisões enviadas, 1 execução na API, 1 pasta local e efeito +1.
- **S5:** o registro mostra `halt(86)` em `STARTING_SAVED_BEFORE_PROCESS`.
  - Depois da queda, a pasta local tinha só `estado.json` (STARTING), `eventos.jsonl` e `plataforma.json`: sem cópia do pacote e sem saída.
  - Na volta, o Agent registrou `OUTCOME_UNKNOWN` (`AGT-EXE-025`), com o início não confirmado, 1 execução e efeitos inalterados.

**O que a prova sustenta:** o Agent encontrou um início incerto, registrou "desfecho desconhecido" e não relançou.

**O que ela não prova:** uma queda do Agent **depois** de o Python iniciar. Nesse caso, a reconciliação atual também fecharia como desconhecido e não relançaria, mas o processo filho poderia continuar sozinho. Essa prova não foi feita e fica declarada como lacuna.
