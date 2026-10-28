# `nekkora-agent/local-control` 1.0.0: contrato detalhado (para revisão)

**Estado: PROPOSTA para aprovação. Não implementado.** O canal não existe no código. O teste de isolamento ISO-03
falha se o pacote `com.nekkora.agent.local` aparecer antes da aprovação. Este documento detalha o contrato que a
compat-001 esboçou (`03-proposta-contrato-local.md`), já com as decisões D-SA-01 a D-SA-06 de 28/10/2026.

O formato das mensagens está em [`contrato/nekkora-agent-local-control-1.0.0.schema.json`](contrato/nekkora-agent-local-control-1.0.0.schema.json),
com exemplos válidos e inválidos em `contrato/exemplos/`. O teste `LocalControlContractTest` confere que o schema é
JSON Schema 2020-12 válido, que cada exemplo válido passa e que cada inválido é recusado pelo motivo esperado.

Documentos irmãos:
- [`analise-ameacas.md`](analise-ameacas.md);
- [`matriz-autorizacao.md`](matriz-autorizacao.md);
- [`testes-isolamento.md`](testes-isolamento.md).

## 1. Regras que o contrato não pode quebrar

| # | Regra | Origem |
|---|---|---|
| R1 | **Nada executa só porque o Studio pediu.** Todo pedido espera uma ação humana na janela do Agent, a confirmação daquele pedido, mesmo para um pacote já aprovado. Não existe "sempre permitir" na 1.0.0 | missão de 28/10 |
| R2 | **Pedido não aprova pacote.** Pacote novo ou modificado exige a aprovação humana existente no Agent, vinculada à impressão digital calculada pelo **Agent** | D-SA-03, D-SA-06 |
| R3 | O socket existe só no Agent, **desligado por padrão**, e só como Unix Domain Socket. **Sem TCP**, nem em loopback, e sem fallback | D-SA-01 |
| R4 | Socket local **não é confiança**. Toda conexão se autentica, e a primeira de um Studio exige pareamento confirmado pela pessoa | D-SA-01, D-SA-05 |
| R5 | A **origem autenticada do pedido** (Studio pareado) fica no histórico, sempre separada da **procedência do pacote**, que continua "não verificada" | D-SA-05 |
| R6 | O Agent calcula e confere a impressão digital do conteúdo **efetivamente executado**: a cópia conferida, como hoje | D-SA-06 |
| R7 | Cancelar por identificador é idempotente, e **pedir não é confirmar** | D-SA-04 |
| R8 | Uma única porta de execução: o canal chama o mesmo `ExecutionController.start(Request)` da janela, com as mesmas conferências | compat-001 |
| R9 | Nada do canal muda o ciclo de vida da **execução** (`ExecutionState`). Os estados do **pedido** são outro objeto | compat-001 §6 |
| R10 | Mensagens sem texto livre: enums, códigos `AGT-*`, identificadores validados, caminhos absolutos validados e números | compat-001 §1 |

## 2. Transporte e descoberta

| Item | Especificação |
|---|---|
| Lado servidor | o Agent (processo de longa duração, na bandeja). O Studio é sempre o cliente |
| Ligado | ajuste novo, "Permitir pedidos do Studio nesta máquina", **desligado por padrão**. Ligar e desligar são ações da pessoa no Agent e ficam no log do Agent. Desligado: o socket não existe e o arquivo de descoberta é apagado. Desligar com uma conexão aberta fecha a conexão na hora |
| Pasta | `<dados do Agent>\controle\`, criada com ACL **definida** (só o dono, herdável) e **conferida** na ACL efetiva, como no `BridgeDirectory.verifyPrivate`. Ligação simbólica ou junção na pasta: o canal não sobe (`AGT-LCL-012`) |
| Socket | `<controle>\agent.sock`, AF_UNIX. **Caminho com até 100 caracteres**: medimos o limite do Windows em torno de 108 (o controle positivo do ISO-R falhou com "Unix domain path too long" numa pasta longa). Caminho mais longo: o canal fica indisponível (`AGT-LCL-013`), **sem cair para TCP** |
| Descoberta | `<controle>\agent-control.json`, gravado atomicamente com ACL só do dono. Campos: `protocol`, `contract_versions`, `address` (o caminho do socket), `instance_id` (32 bytes aleatórios por abertura), `pid` e `secret` (32 bytes aleatórios por abertura, base64url). O segredo nunca trafega |
| Instância | a trava de instância única que o Agent já tem: só uma instância publica o canal |
| Quadros | 4 bytes big-endian com o tamanho, seguidos de JSON UTF-8. Máximo de **64 KiB** por quadro (canal de controle; nada grande passa aqui). Quadro maior, truncado, JSON inválido ou fora do schema: erro `AGT-LCL-010` e a conexão é fechada |

## 3. Autenticação, pareamento e repetição

### 3.1 Handshake (cada conexão)

1. O servidor envia `auth.challenge` com `instance_id`, `server_nonce` (32 bytes) e `accepts`.
2. O cliente envia `auth.proof` com `client_nonce` (32 bytes), `proof_secret` e, se já pareado, `pairing_id` e
   `proof_pairing`:
   - `proof_secret = HMAC-SHA256(secret, "nekkora-agent/local-control|client|" + instance_id + "|" + server_nonce + "|" + client_nonce)`;
   - `proof_pairing = HMAC-SHA256(pairing_key, ...mesma mensagem...)`.
3. O servidor confere tudo em tempo constante e responde `auth.ok` com `server_proof` (a mesma construção com
   `|server|`), a versão escolhida, `heartbeat_ms` e os limites.
4. Prazo de 5 s para o handshake inteiro. Qualquer falha: `AGT-LCL-003`, sem dizer qual parte falhou, e a conexão é
   fechada.

**Repetição.** As provas cobrem os dois nonces da conexão, e o `server_nonce` muda a cada conexão. Uma prova capturada
não serve em outra conexão. O `secret` muda a cada abertura do Agent, então nada de uma sessão anterior vale depois de
reabrir.

### 3.2 Pareamento (primeira conexão de um Studio)

1. Sem `pairing_id`, o servidor só aceita `pair.begin`.
2. Os dois lados calculam um código curto de 6 dígitos, `SAS = HMAC(secret, "pair|" + server_nonce + "|" + client_nonce) mod 10^6`.
   O Studio mostra o código, e o Agent abre "Parear com o Studio?" com o mesmo código e os botões **Parear** e
   **Recusar**. Recusar é o padrão. O prazo é de 2 min.
3. Se a pessoa confirmar, o Agent cria `pairing_id` (UUID) e `pairing_key` (32 bytes) e envia os dois **dentro da
   conexão autenticada** (`pair.result`). O Agent guarda o pareamento em `<controle>\pareamentos.json`, com ACL só do
   dono e sem a chave em texto nos logs. O Studio guarda na própria pasta de dados, com a mesma restrição.
4. O pareamento é **revogável** no Agent (Ajustes, Pedidos do Studio, Revogar). Revogar fecha a conexão e recusa
   esse `pairing_id` daí em diante (`AGT-LCL-004`). A 1.0.0 tem no máximo **1** pareamento ativo.

O pareamento identifica **uma instalação do Studio pareada pela pessoa**. Ele não comprova quem gerou o pacote nem
protege contra um processo malicioso do mesmo usuário que leia os arquivos de chave (ver a análise de ameaças, T1).

### 3.3 Dentro da sessão

| Campo | Regra |
|---|---|
| `seq` | inteiro estritamente crescente por direção, começando em 1 depois do handshake. Repetido ou fora de ordem: `AGT-LCL-010` e a conexão é fechada |
| `message_id` | UUID único na sessão. Repetido: recusado |
| `request_id` | UUID; **chave de idempotência do pedido** (§5.1), persistida no Agent. Repetir o mesmo pedido em outra sessão devolve o mesmo resultado e nunca uma segunda execução |
| Batimento | `heartbeat` a cada `heartbeat_ms` (15 s). Três sem resposta: a conexão fecha. Sem nenhuma mensagem por 5 min: fecha |
| MAC por quadro | **não há** na 1.0.0 (decisão P-02 para a titular, §9). O fluxo AF_UNIX é local ao núcleo, entre pontas já autenticadas, e quem pudesse forjar quadros já teria o segredo e abriria a própria sessão |

## 4. Mensagens

Todas têm `schema = "nekkora-agent/local-control"`, `version = "1.0.0"`, `type`, `message_id`, `seq` (depois do
handshake) e `payload`. As respostas têm `reply_to` = o `message_id` do pedido. O schema fecha cada `payload`
(`additionalProperties: false`).

| `type` | Sentido | Payload (resumo) |
|---|---|---|
| `auth.challenge` | A → S | `instance_id`, `server_nonce`, `accepts: ["1.0.0"]` |
| `auth.proof` | S → A | `client_nonce`, `proof_secret`, `pairing_id?`, `proof_pairing?`, `studio_version` |
| `auth.ok` | A → S | `server_proof`, `contract_version`, `heartbeat_ms`, `limits{max_frame_bytes, max_messages_per_second, max_requests_per_hour, max_activity_page}`, `paired` |
| `pair.begin` | S → A | (vazio) |
| `pair.pending` | A → S | `sas` (6 dígitos), `expires_in_s` |
| `pair.result` | A → S | `outcome` (`PAIRED` ou `REFUSED` ou `EXPIRED`), `pairing_id?`, `pairing_key?` |
| `run.request` | S → A | ver §5.1 |
| `run.received` | A → S | `request_id`, `request_state`, `agent_fingerprint?` (§5.2) |
| `run.refused` | A → S | `request_id`, `code` (`AGT-EXE-*` ou `AGT-LCL-*`) |
| `request.status` | S → A | `request_id` |
| `request.snapshot` | A → S | `request_id`, `request_state`, `code?`, `agent_execution_id?`, `agent_fingerprint?` |
| `request.withdraw` | S → A | `request_id`: o Studio desiste **antes** do início (sem efeito depois) |
| `run.status` | S → A | `agent_execution_id` |
| `run.snapshot` | A → S | `state`, `code?`, `created`, `started?`, `ended?`, `exit_code?`, `adopted`, `recovered`, `record_saved`, `counts`, `progress?`, `cancellation?`, `origin` (o objeto 1.1.0 + `pairing_id` e `request_id`) |
| `run.activity` | S → A | `agent_execution_id`, `after_sequence`, `max` (de 1 a 500) |
| `run.activity.page` | A → S | `events` (envelopes `nekkora-agent/observability` 1.0.0 **sem transformação**), `last_sequence`, `more` |
| `run.cancel` | S → A | `agent_execution_id`, `cancel_id` (UUID) |
| `run.cancel.result` | A → S | `cancel_id`, `result` (`REQUESTED`, `ALREADY_REQUESTED`, `ALREADY_ENDED`, `NOT_ACTIVE`, `UNKNOWN_EXECUTION` ou `NOT_REQUESTED_BY_THIS_ORIGIN`) |
| `run.changed` | A → S | aviso: `agent_execution_id` ou `request_id`, `state`, `last_sequence`. A verdade continua sendo o `*.status` |
| `heartbeat` | os dois sentidos | (vazio) |
| `error` | os dois sentidos | `code` (`AGT-LCL-*`) e `fatal` (booleano) |

## 5. Pedido de execução

### 5.1 `run.request`

| Campo | Formato | Regra |
|---|---|---|
| `request_id` | UUID v4 | idempotência (§5.3) |
| `package.path` | caminho absoluto Windows, local | sem UNC (`\\servidor\`), sem prefixo de dispositivo (`\\?\` ou `\\.\`), sem `..`, sem componente que seja ligação ou junção, com até 260 caracteres. Conferido de novo na inspeção. Fora disso: `AGT-LCL-012` |
| `package.expected_fingerprint` | `sha256:` + 64 hex, opcional | se vier e for diferente da impressão calculada pelo Agent: `AGT-EXE-004` |
| `package.manifest_sha256` | 64 hex | só correlação com o Studio. O Agent confere e recusa a divergência (`AGT-EXE-004`) |
| `runtime.python` | caminho absoluto, com as mesmas regras | conferido pelo `PythonRuntime.probe` (versão exata do Framework, como hoje) |
| `input` | caminho absoluto, opcional | exigido conforme o modo do manifesto |
| `output_root` | caminho absoluto | fora do pacote e do Agent (regras atuais) |
| `timeout_seconds` | inteiro de 60 a 86 400 | o padrão do Agent é 3 600 |
| `correlation` | `project_ref`, `automation_ref` e `code_revision_ref`, cada um `[a-z0-9-]{1,64}`, todos opcionais | opacos: o Agent guarda e devolve sem interpretar |

### 5.2 Estados do pedido (separados da execução)

```
RECEIVED ─┬─► AWAITING_APPROVAL ──► AWAITING_CONFIRMATION ──► STARTED (agent_execution_id)
          ├─► AWAITING_CONFIRMATION ─────────────────────┘
          └─► REFUSED (código)        qualquer estado não final ─► REFUSED | EXPIRED | WITHDRAWN
```

- `RECEIVED` → inspeção (os hashes, o tipo, os segredos `AGT-EXE-006` e a impressão digital calculada pelo Agent),
  validação de caminhos e limites. Qualquer falha: `REFUSED` com o código do catálogo, e nenhum registro de execução é
  criado.
- `AWAITING_APPROVAL`: impressão ainda não aprovada nesta máquina. O Agent mostra o fluxo de aprovação **existente**,
  com a impressão, o gerador declarado, "Origem do pacote: não verificada" e "Pedido pelo Studio pareado (pareamento
  …)". Aprovar vincula a aprovação à impressão (R2). Recusar: `REFUSED`/`AGT-LCL-008`.
- `AWAITING_CONFIRMATION`: a janela "Pedido de execução do Studio" mostra o pacote (nome curto e impressão), o Python,
  a entrada e a saída (nomes curtos), o prazo e a origem do pedido, com **Iniciar esta execução** e **Recusar**. Recusar
  é o padrão (R1). Só um pedido espera confirmação por vez; outro pedido é recusado com `AGT-LCL-005`.
- `STARTED`: a pessoa confirmou, e o Agent chamou `start(Request)` com a impressão **que a pessoa viu**. Se o pacote
  mudou entre a confirmação e o lançamento, a conferência já existente dá `BLOCKED`/`AGT-EXE-004`. A execução é então
  um registro normal (`estado.json`), com `origin.kind = STUDIO_LOCAL`.
- `EXPIRED`: sem ação humana em 10 min (`AGT-LCL-007`). `WITHDRAWN`: o Studio desistiu antes de `STARTED`.
- Uma execução já em andamento (a vaga é uma por usuário): `REFUSED`/`AGT-EXE-014`, como na janela.

### 5.3 Idempotência

- O Agent guarda, em `<controle>\pedidos.json` (gravação atômica, ACL só do dono), o `request_id`, o SHA-256 do pedido
  canônico, o estado, o `agent_execution_id` e o instante. São no máximo 1 000 entradas por 30 dias.
- Mesmo `request_id` com o mesmo conteúdo: devolve o mesmo estado e o mesmo `agent_execution_id`. **Nunca** uma segunda
  execução, inclusive depois de reabrir o Agent.
- Mesmo `request_id` com conteúdo diferente: `AGT-LCL-009`.
- Fora da retenção: `UNKNOWN_REQUEST`. Isso nunca significa "não executou".

## 6. Acompanhamento, cancelamento e desfecho

- `run.status` e `run.activity` respondem para qualquer execução do histórico, inclusive as pedidas pela janela.
  `run.cancel` só vale para execuções com `origin.pairing_id` igual ao da sessão. Fora disso, a resposta é
  `NOT_REQUESTED_BY_THIS_ORIGIN` (matriz de autorização).
- `run.cancel` chama `ExecutionController.cancel(id, STUDIO_LOCAL)`. O `CancelSource.STUDIO_LOCAL` só passa a existir
  com o canal. O resultado é o `CancelResult` já implementado na S0. A confirmação chega por `run.snapshot`, em
  `cancellation.outcome`: `CONFIRMED` ou `NOT_EFFECTIVE`. Repetir o `cancel_id` devolve o mesmo resultado.
- **Desfecho:**
  - `OUTCOME_UNKNOWN` é sempre `OUTCOME_UNKNOWN` (nível da execução, A04). Nunca vira sucesso nem dispara nova
    tentativa no Studio;
  - `recovered = true` diz que o desfecho veio do protocolo conferido depois de um reinício (A02);
  - `progress` é parcial e **nunca** desfecho (A03).
- `run.activity.page` entrega os envelopes exatamente como gravados. O schema 1.0.0 agora é JSON válido (A01) e pode
  ser usado pelo Studio para validar o que recebe. O `last_sequence` funciona como confirmação: o Studio repete a
  partir dele.

## 7. Limites (recursos)

| Limite | Valor | Ao passar |
|---|---|---|
| Conexões simultâneas | 1 (mais o handshake em curso) | `AGT-LCL-005` e fecha |
| Handshake | 5 s | fecha |
| Falhas de autenticação | 5 em 60 s | o canal recusa tudo por 5 min (`AGT-LCL-003`), com aviso no Agent |
| Quadro | 64 KiB | `AGT-LCL-010` e fecha |
| Mensagens | 20/s por sessão (balde de fichas) | `AGT-LCL-006` (não fatal), e fecha se persistir por 10 s |
| `run.request` | 10 por hora por pareamento | `AGT-LCL-006` |
| Pedidos aguardando ação humana | 1 | `AGT-LCL-005` |
| Página de atividade | 500 envelopes ou 1 MiB | `more = true` |
| Inatividade | 5 min | fecha |
| `pedidos.json` | 1 000 entradas ou 30 dias | as mais antigas saem |
| Memória do canal | tudo acima limitado. Nenhuma fila ilimitada; o leitor de quadros nunca aloca mais que o limite | — |

## 8. Catálogo novo (`AGT-LCL-*`)

| Código | Significado |
|---|---|
| AGT-LCL-001 | O canal do Studio está desligado nesta máquina |
| AGT-LCL-002 | Versão de contrato não suportada |
| AGT-LCL-003 | Autenticação falhou (ou bloqueio temporário por falhas repetidas) |
| AGT-LCL-004 | Studio não pareado ou pareamento revogado |
| AGT-LCL-005 | Ocupado: outra conexão ou outro pedido aguardando a pessoa |
| AGT-LCL-006 | Limite de taxa |
| AGT-LCL-007 | O pedido expirou sem ação da pessoa |
| AGT-LCL-008 | A pessoa recusou o pedido ou a aprovação |
| AGT-LCL-009 | `request_id` repetido com conteúdo diferente |
| AGT-LCL-010 | Quadro ou mensagem inválida (tamanho, JSON, schema, `seq` ou `message_id`) |
| AGT-LCL-011 | Execução não pedida por este Studio pareado |
| AGT-LCL-012 | Caminho recusado (relativo, rede, dispositivo, ligação ou junção) |
| AGT-LCL-013 | O socket local não pode ser criado aqui (caminho longo ou sem AF_UNIX); o canal fica indisponível, sem TCP |

## 9. Decisões ainda pedidas à titular

| Id | Pergunta | Recomendação |
|---|---|---|
| P-01 | Confirmação humana a cada pedido (R1), sem "sempre permitir" | **sim** na 1.0.0. Uma automação recorrente pelo Studio continua sendo uma pessoa clicando |
| P-02 | MAC por quadro dentro da sessão AF_UNIX | **não** (§3.3). Reavaliar se surgir um transporte que não seja local ao núcleo |
| P-03 | Sem fallback TCP (diferente do Bridge do Studio) | **sim**. O canal fica indisponível se não houver AF_UNIX ou se o caminho for longo |
| P-04 | Um pareamento ativo por vez | **sim** na 1.0.0 |
| P-05 | Prazo de 10 min para a ação humana | 10 min |
| P-06 | D-SA-02: copiar e revisar `BridgeAuth`, `BridgeFrames` e `BridgeDirectory` para `com.nekkora.agent.local` (sem módulo compartilhado) | **cópia revisada**, com os testes do Bridge adaptados. As diferenças estão documentadas: só UDS, quadros de 64 KiB, pareamento e `seq` |
| P-07 | `estado.json` 1.2.0 com `origin.pairing_id` e `origin.request_id` quando o canal existir | **sim**, aditiva. A 1.1.0 desta missão já tem `origin.kind` |

## 10. Fatias de implementação (depois da aprovação)

| Fatia | Conteúdo | Condição de entrada |
|---|---|---|
| S1 | `com.nekkora.agent.local`: diretório, descoberta, socket AF_UNIX, handshake, limites, ajuste desligado por padrão. `ArchitectureTest` e `NetworkIsolationTest` passam a ter uma exceção **restrita** a esse pacote e só a AF_UNIX | aprovação deste contrato, das ameaças e da matriz |
| S2 | pareamento, `run.request` e estados do pedido, janelas de aprovação e confirmação, idempotência persistida | S1 com ISO-S01 a S12 verdes |
| S3 | `run.status`, `run.activity`, `run.cancel` (`CancelSource.STUDIO_LOCAL`), `run.changed`, `estado.json` 1.2.0 | S2 |
| S4 | cliente no Studio ("Executar no Agent"), sem tirar o teste autorizado | S3. **Missão própria no Studio** |
| S5 | homologação de ponta a ponta com o pacote real, a queda do Agent e a do Studio | S4 |
