# NEKKORA-DESKTOP-001 — Adendo OBS-001: observabilidade local da execução

Estado: **implementado e homologado localmente; G2/A13/OBS-G6 físicos `PENDING_PHYSICAL_HOMOLOGATION`** (exigem a
titular presente para bloquear a tela e suspender o Windows). Branch local `feat/nekkora-desktop-001`; nada publicado.
Matriz: [`matriz-obs.md`](matriz-obs.md). Contrato: [`observabilidade/`](observabilidade/).

## 1. Auditoria (antes do desenho)

| Pergunta | Resposta verificada |
|---|---|
| Onde o Framework grava os eventos num run real? | O `main.py` gerado pelo Studio passa `run_dir = <--output>/execucao` ao `Engine`; o `JsonlEventSink` grava `<--output>/execucao/events.jsonl` (append, `flush` por evento, UTF-8, LF). O Agent cria `<saída escolhida>/<ExecutionId>` e passa como `--output` — o caminho é **derivado de uma pasta criada pelo Agent**, nunca de um evento. |
| Formato | `nekkora-rpa/events` **1.2.0** (Framework 0.3.0; schema em `contracts/events-1.2.0.schema.json`, `additionalProperties: false`). Campos de texto validados pelo próprio Framework como token (`[A-Za-z0-9][A-Za-z0-9_.:-]{0,79}`). |
| Campos perigosos | `detail` (token livre escolhido pelo código), `cause_type` (nome de exceção), `key` (chave de seletor), `selector_registry_revision`, `strategy_index`, `delay_ms`, `framework_version`. Um produtor com defeito pode escrever qualquer coisa no arquivo — o Agent não confia no conteúdo. |
| Identidades | `execution_id` do Framework (`ex-` + 20 hex) ≠ `ExecutionId` do Agent (`ex-` + 32 hex). `transaction_id` é pseudônimo HMAC do Framework. Nunca presumidos iguais. |
| `step_id` ↔ Studio | `manifest.json` (1.4+): `steps[].traceId` = `step_id` emitido; `steps[].stepId` = identificador do passo no Studio. Há `step_id` gerados em tempo de execução que **não** estão no manifesto (ex.: `process.open_start_page`). |
| Base do Agent | `eventos.jsonl` do supervisor (≤ 500 linhas, perda silenciosa além disso — corrigido, §4.4), `estado.json` (fonte do estado), stdout/stderr drenados e só contados. |

Fixture real usado nos testes: os 121 eventos do run G1 da DESKTOP-001 (`src/test/resources/.../g1-framework-events.jsonl`).

## 2. Desenho

```
Processo (Framework ou genérico) ──► <saída>/<id>/execucao/events.jsonl ─┐
Supervisor do Agent (ExecutionController) ──► execucoes/<id>/eventos.jsonl ─┤
                                                                          ▼
              ActivityService (1 linha de fundo, prioridade reduzida, 500 ms)
                 └─ ActivityCollector por execução: SourceFile (leitura segura, incremental)
                    → AgentEvents / FrameworkEvents (lista de permissão) → ActivityLog
                                                                          ▼
                     execucoes/<id>/atividade.jsonl  (envelope nekkora-agent/observability 1.0.0)
                                                                          ▼
          ActivityView (domínio) ──► ActivityPanel (aba "Atividade", Swing; só o envelope)
                                 └─► [futuro] transportador autenticado → Platform (não implementado)
```

- Pacote novo `com.nekkora.agent.obs` (domínio, sem Swing). `ExecutionController` **não foi alterado** e não depende
  de `obs` (regra no `ArchitectureTest`). A coleta é alimentada pelos arquivos já gravados (funciona igual depois de
  reinício) e acordada pelo ouvinte do supervisor.
- Duas fontes, uma visualização: a interface conhece só o envelope do Agent; o formato do Framework fica no adaptador
  (`FrameworkEvents`). Processo genérico (`LAB_GENERIC`): só a fonte do supervisor; a tela mostra **"Etapas não
  disponíveis para este tipo de processo"**. Um eventual protocolo opt-in para processos genéricos não foi criado
  (não foi necessário; se vier, será outro adaptador com o mesmo envelope).
- O estado e o desfecho mostrados vêm SEMPRE do registro do supervisor; o último evento nunca encerra nada.

## 3. Contrato `nekkora-agent/observability` 1.0.0

JSON Schema: [`observabilidade/nekkora-agent-observability-1.0.0.schema.json`](observabilidade/nekkora-agent-observability-1.0.0.schema.json).
Exemplos válidos (conferidos por teste): [`observabilidade/exemplos/`](observabilidade/exemplos/).

| Campo | Tipo / formato | Semântica |
|---|---|---|
| `schema`, `version` | `"nekkora-agent/observability"`, `"1.0.0"` | contrato |
| `agent_execution_id` | `ex-[0-9a-f]{32}` | identidade PRIMÁRIA (execução local do Agent) |
| `sequence` | inteiro ≥ 1 | ordem de OBSERVAÇÃO/gravação; contínua e estável por execução (deduplicação: `agent_execution_id` + `sequence`) |
| `timestamp_utc` | ISO-8601 UTC (opcional) | instante de OCORRÊNCIA informado pela origem (pode chegar atrasado) |
| `observed_utc` | ISO-8601 UTC | instante em que o Agent leu o evento |
| `source` | `AGENT` \| `FRAMEWORK` \| `COLLECTOR` | supervisor, Framework do pacote, ou o próprio coletor (telemetria) |
| `event_code` | `[A-Z][A-Z0-9_]{1,63}` | código; Framework 1.x mantido; supervisor: nomes do `eventos.jsonl`; coletor: §3.2 |
| `severity` | `INFO` \| `WARNING` \| `ERROR` | nível |
| `category` | `LIFECYCLE` \| `ACTIVITY` \| `BUSINESS` \| `FAILURE` \| `TELEMETRY` | separa ciclo de vida, etapa, **rejeição de negócio**, **falha de execução** e **falha de telemetria** |
| `phase` | `INIT` \| `TRANSFER_DATA_IN` \| `PROCESS` \| `TRANSFER_DATA_OUT` \| `END` | fase do Framework |
| `transaction_ref` | `t-[0-9a-f]{16}` | token opaco = SHA-256(execução local + `transaction_id` do Framework); estável na execução, não liga execuções |
| `step_id` | `[a-z][a-z0-9_]{0,63}(\.[a-z0-9_]{1,63}){0,7}`, ≤ 120, sem 4+ dígitos seguidos | etapa como o código a informou |
| `studio_step_id` | UUID | passo do Studio, só com manifesto confiável (§3.3) |
| `action` | `[a-z][a-z_]{0,39}` | ação do Framework (`fill`, `press`, `read_items`…) |
| `attempt`, `elapsed_ms` | inteiros ≥ 0 | tentativa e duração da ação |
| `status` | `[A-Z][A-Z0-9_]{0,39}` | resultado (`outcome` do Framework / estado do supervisor); em eventos do COLETOR, a origem afetada (`AGENT_SOURCE`, `FRAMEWORK_SOURCE`, `ACTIVITY_STORE`) |
| `error_code` | `[A-Z][A-Z0-9_]{1,63}` ou `AGT-XXX-nnn` | código do Framework, do catálogo do Agent ou motivo do coletor |
| `counts` | objeto ≤ 16 chaves `[a-z][a-z0-9_]{0,39}` → inteiro | contagens validadas (`items`, `exit_code`, `output_bytes`, `gap_ms`, `dropped`…) |
| `correlation.framework_execution_id` | `ex-[0-9a-f]{8,64}` | correlação SECUNDÁRIA com a execução do Framework |
| `correlation.source_position` | inteiro ≥ 0 | byte logo depois da linha na origem: identidade estável origem+posição (retomada e deduplicação) |

Opcionais ausentes são omitidos. Não existe campo livre (`message`, `detail`, `value`…).

### 3.1 Compatibilidade de versão
- Leitura do histórico: `1.0.x` exige exatamente os campos conhecidos; `1.y` (y > 0) é aceita ignorando campos novos;
  outra versão maior é recusada (linha ilegível, sinalizada).
- Framework: `nekkora-rpa/events` com versão maior 1 é aceito; código de evento fora do catálogo 1.2.0 é MANTIDO e
  contado (`unknown_codes`); campo desconhecido é descartado e contado (o NOME não é guardado); versão maior ≠ 1 →
  `EVENT_INVALID/SCHEMA_UNSUPPORTED`.

### 3.2 Códigos do coletor (telemetria; nunca mudam o desfecho)
`COLLECTION_STARTED`, `COLLECTION_RESUMED` (reinício), `COLLECTION_COMPLETED` (com `counts`: `events`,
`framework_events`, `dropped`, `invalid`, `excluded_fields`, `unknown_fields`, `unknown_codes`, `restarts`),
`EVENT_GAP` (`AGT-OBS-005` limite do histórico; `AGT-OBS-006` limite de 500 do supervisor; `AGT-OBS-009` eventos
que não puderam ser gravados e foram descartados, com `counts.lost` — ver §4.1), `EVENT_INVALID`
(`MALFORMED_JSON`, `LINE_TOO_LONG`, `ENCODING_INVALID`, `PARTIAL_LINE`, `SCHEMA_MISMATCH`, `SCHEMA_UNSUPPORTED`,
`FIELD_INVALID`, `NESTING_TOO_DEEP`, `STALE_EVENT`, `CORRELATION_INVALID`, `CORRELATION_CONFLICT`),
`SOURCE_UNAVAILABLE` (`AGT-OBS-001`), `SOURCE_AVAILABLE`, `SOURCE_UNSAFE` (`AGT-OBS-003`), `SOURCE_REPLACED`
(`AGT-OBS-004`), `HISTORY_PURGED` (`AGT-OBS-010`). Catálogo `AGT-OBS-001…014` em `core/ErrorCode` (`AGT-OBS-014`:
o histórico de execuções não pôde ser lido — só na tela, nunca no envelope).

### 3.3 Mapeamento
- **Framework** (lista de permissão): `timestamp_utc`→`timestamp_utc`; `execution_id`→`correlation`; `event_code`,
  `phase`, `step_id`, `action`, `attempt`, `elapsed_ms`, `error_code` mantidos se válidos; `outcome`→`status`;
  `transaction_id`→`transaction_ref` (re-pseudonimizado); `count`→`counts.items`. Descartados e contados:
  `detail`, `cause_type`, `key`, `strategy_index`, `delay_ms`, `selector_registry_revision`, `framework_version`.
  Campo mantido fora do formato → descartado e contado. Categoria: `*_FAILED`/`SECURITY_BLOCKED` → `FAILURE/ERROR`;
  `OUTCOME_UNKNOWN`/`SINK_UNCERTAIN` → `FAILURE/WARNING`; `BUSINESS_REJECTED`/`BUSINESS_RULE_REJECTED` →
  `BUSINESS/WARNING`; novas tentativas, pulos, saída retida, cancelamento → `ACTIVITY/WARNING`.
- **Supervisor**: nomes fechados (`STARTING`, `RUNNING`, terminais, `SUSPENSION_DETECTED`, `SESSION_LOCKED/UNLOCKED`,
  `TERMINATED`, `RECONCILE`, `ADOPTED`); `code`→`error_code`; `exit`/`output_bytes`/`gap_ms`/`processes`/`protocol`
  → `counts`. PID, sessão, tipo e modo ficam de fora (o registro já os tem).
- **`studio_step_id`**: manifesto da CÓPIA CONFERIDA (`execucoes/<id>/pacote/manifest.json`), só se a impressão
  digital recalculada for a do registro; `traceId` repetido → sem correspondência. Sem manifesto/versão anterior →
  `step_id` simples, execução não afetada.

### 3.4 Ordenação, deduplicação e eventos tardios
`sequence` é atribuída ao gravar, na ordem de observação; cada evento leva a posição da sua origem. Na reabertura, o
`atividade.jsonl` é relido, a última linha incompleta (queda no meio da escrita) é cortada, e a próxima sequência e a
posição já consumida de cada origem saem **do próprio arquivo** — não há cursor separado que possa divergir. A tela
oferece "Ordem de observação" (padrão) e "Ordem de ocorrência"; um evento cujo `timestamp_utc` é anterior em mais de
1 s a um já mostrado é marcado (↺ "chegou depois de eventos posteriores").

## 4. Armazenamento, limites e retenção

| Item | Valor | Comportamento no limite |
|---|---|---|
| Atividade por execução (`atividade.jsonl`) | 20.000 eventos **ou** 8 MiB | um `EVENT_GAP` (`AGT-OBS-005`); eventos seguintes descartados e contados (`counts.dropped` no `COLLECTION_COMPLETED`); reserva de 64 linhas só para avisos do coletor |
| Linha lida do Framework / supervisor / histórico | 8 KiB / 2 KiB / 4 KiB | `EVENT_INVALID/LINE_TOO_LONG` (contada uma vez) |
| Aninhamento JSON no evento do Framework | 2 | `EVENT_INVALID/NESTING_TOO_DEEP` |
| Leitura por rodada e origem | 256 KiB | contrapressão: o volume entra em várias rodadas |
| Recusas gravadas uma a uma | 20 por execução | as demais só contadas (resumo e tela) |
| Cota total de atividade | 96 MiB | a atividade detalhada das execuções TERMINADAS mais antigas vira um único `HISTORY_PURGED`; a mais recente nunca é purgada; `estado.json`/`eventos.jsonl` ficam |
| Execuções guardadas | 50 terminadas (retenção existente do `RunStore`) | a pasta inteira (incluindo `atividade.jsonl`) é apagada |
| `eventos.jsonl` do supervisor | 500 linhas (inalterado) | a 500ª linha passa a ser o marcador `EVENTS_LIMIT_REACHED` → `EVENT_GAP/AGT-OBS-006` (a perda deixa de ser silenciosa) |

### 4.1 Gravação que falha (OBS-PERSISTENCE-HARDENING, 23/10)

Motivo: num ensaio intermediário do roteiro físico, uma leitura com compartilhamento restritivo (`ReadAllLines`)
fez o acréscimo do `atividade.jsonl` falhar e 4 linhas faltaram no disco (a tela mostrou a lacuna corretamente).

| Item | Valor | Comportamento |
|---|---|---|
| Linhas aceitas e ainda não gravadas | fila em ordem, com a `sequence` já atribuída; até 4.096 linhas **e** 2 MiB (tolerância explícita: + UMA linha, a última aceita antes de a fila encher); a fila guarda na memória só o que ainda NÃO está no arquivo | a tela mostra os eventos (memória) e o aviso "Gravação do histórico pendente" (`AGT-OBS-009`); a linha do tempo fica INCOMPLETA (`STORE_PENDING`) até a prova no disco |
| Novas tentativas | até 12, com intervalo crescente 200 ms → 5 s (teto), só na linha da coleta | nunca na interface, nunca em laço; uma última tentativa ao fechar o Agent |
| Conferência antes de regravar | o que está no arquivo depois da última gravação SINCRONIZADA tem de ser o COMEÇO exato das pendentes | o que já está lá não é regravado; linha gravada pela metade é completada; fim que não confere (outro programa escreveu) → nada é colado |
| Durabilidade | uma pendente só é dada como gravada depois de uma sincronização (`fsync`) confirmada | bytes visíveis no arquivo não bastam: se só a sincronização falhou, a próxima tentativa sincroniza sem regravar; até lá a pendência continua na tela |
| Desistência (tentativas esgotadas, fila cheia ou fim que não confere) | só o que NÃO está no arquivo é descartado e contado; linhas inteiras já no arquivo continuam só à espera da sincronização | UM `EVENT_GAP` com `AGT-OBS-009` e `counts.lost` = perda ACUMULADA ainda não registrada no disco; num novo ciclo de falha esse aviso não gravado é SUBSTITUÍDO (a contagem nunca encolhe; no máximo um aviso não gravado na memória). Depois de GRAVADO, a lacuna de `sequence` e o aviso ficam no disco e a linha do tempo continua INCOMPLETA (`STORE_FAILED`, `SEQUENCE_GAP`) depois de um reinício |
| Fila cheia com a sincronização falhando (B-OBS-03) | uma gravação é tentada na hora (no máximo uma vez por fila cheia, fora do intervalo); se falhar, as linhas que já estão INTEIRAS no arquivo, na posição esperada, saem da memória e ficam só contadas, aguardando a sincronização | não são perdidas nem regravadas e continuam na contagem de pendentes (tela INCOMPLETA, `STORE_PENDING`); só o que não chegou ao arquivo é descartado (com o aviso acima). A fila nunca passa do limite, mesmo com `fsync` falhando sempre. Se outro programa CORTAR o arquivo, o que sobrou é relido: só as linhas aguardando sincronização que sumiram são perdidas; bytes alheios acrescentados depois delas não as tornam perdidas |
| Agent termina antes de gravar o aviso | — | o aviso só existia na memória; como a fila é ordenada, nada posterior à perda está no disco: na reabertura os eventos das ORIGENS são relidos das posições gravadas (enquanto os arquivos do supervisor e do Framework existirem). Os avisos do próprio coletor desse intervalo não são recuperáveis. Não há garantia incondicional além disso |
| Queda do Agent com pendentes | — | na reabertura, a próxima `sequence` e as posições das origens saem do DISCO: o que estava só na memória é relido das origens, sem lacuna e sem repetição |
| Log do Agent | `ACTIVITY_STORE_DELAYED` (`AGT-OBS-009`, `pending`), `ACTIVITY_STORE_RECOVERED`, `ACTIVITY_STORE_LOST` (`AGT-OBS-009`, `lost`) | só transições e contagens (com o identificador da execução); nunca caminho, conteúdo de evento ou exceção com texto; uma falha do log não volta para a coleta |

A automação nunca é tocada: o registro do supervisor (`estado.json`, `eventos.jsonl`) e o desfecho continuam os do
`ExecutionController`, que não foi alterado; nenhuma nova tentativa vale para eventos de negócio, processos ou
execuções. O envelope 1.0.0 não mudou (`EVENT_GAP` e `AGT-OBS-009` já existiam; `counts` é aberto).

**Entrega futura**: com armazenamento limitado, NÃO há garantia de entrega sem perdas. Um intervalo descartado
(`EVENT_GAP`) ou purgado (`HISTORY_PURGED`) não poderá ser reenviado; o transportador futuro precisa de ACK por
`sequence` e de retenção que considere o que ainda não foi confirmado (§8).

## 5. Segurança e privacidade (OBS-04)

- **Nada livre**: o envelope não tem campo de texto livre; todo texto é enum, código, identificador validado ou
  número. `step_id`/`status`/`error_code` com 4+ dígitos seguidos são recusados (CPF, CNPJ, telefone, pedido).
  Recusa por campo não ecoa o valor (a exceção traz só o nome do campo).
- **stdout/stderr**: continuam drenados e só contados (`counts.output_bytes`); nenhum byte é lido como texto.
- **Leitura de arquivo**: caminho FIXO derivado da pasta criada pelo Agent (`saida.ref` validado: absoluto, normalizado,
  último nome = `ExecutionId`) + nomes constantes (`execucao/events.jsonl`); nunca um caminho vindo de evento.
  Pasta-base, intermediárias e arquivo sem ligação simbólica nem ponto de nova análise (junção) — atributos lidos SEM
  seguir ligações; dono do arquivo = dono da pasta-base; abertura com `NOFOLLOW_LINKS`; identidade + primeira linha
  conferidas a cada leitura (truncado/rotacionado/trocado depois de aberto → `SOURCE_REPLACED`, a origem para e nada é
  relido). UTF-8 estrito; JSONL parcial só é declarado na leitura final.
- **Correlação**: eventos de outra execução do Framework (`CORRELATION_CONFLICT`) ou anteriores ao início da execução
  local − 2 min (`STALE_EVENT`) são recusados: nenhuma falsa correspondência.
- **ACL** (medida em 23/10, `evidencias/OBS-pacote`): `atividade.jsonl` e `eventos.jsonl` herdam a ACL do perfil do
  usuário (`%LOCALAPPDATA%\Nekkora\…`): `SISTEMA (F)`, `Administradores (F)`, `<MÁQUINA>\<USUÁRIO> (F)`, dono = o
  usuário. Nenhuma ACL é alterada pelo Agent. O `events.jsonl` do Framework fica na pasta de saída escolhida pela
  pessoa e herda a ACL dela (documentado como responsabilidade de quem escolhe o destino).
- **Sem rede/IPC**: nenhum HTTP, socket ou pipe foi aberto (`ArchitectureTest`).
- **Exportação**: manual (botão "Exportar atividade…", Alt+O), com prévia da lista fechada de campos antes do destino;
  grava os envelopes recodificados a partir do modelo validado (nunca cópia de arquivo bruto). Log do Agent registra
  só `ACTIVITY_EXPORTED events=N`.

## 6. Tempo quase real, recuperação e desempenho (OBS-05)

- Coleta: uma linha `nekkora-agent-atividade` (prioridade normal − 1) a cada 500 ms, só nas execuções abertas.
- Tela: `javax.swing.Timer` de 400 ms (coalescente) pede só eventos com `sequence` maior que a última vista, numa linha
  própria (`nekkora-agent-atividade-tela`); a EDT só acrescenta linhas em lote. Medido: pior latência da EDT durante a
  coleta < 250 ms (teste de desktop); consulta da atividade durante drenagem de 33 MB: pior caso 2–3 ms.
- Memória: lista por execução limitada ao teto do histórico (≤ 20.064 eventos).
- Reinício/adoção: `reconcile()` adota o processo vivo; a coleta retoma das posições gravadas (`COLLECTION_RESUMED`),
  sem retransmitir eventos e sem relançar o processo (OBS-G5). Execuções anteriores à OBS-001 (sem `atividade.jsonl`)
  são coletadas uma vez a partir das mesmas origens.
- Fechar a janela não interrompe execução nem coleta.
- Gravação que falha: fila limitada e conferida, novas tentativas espaçadas na linha da coleta (§4.1).

## 7. Interface (aba "Atividade")

Na janela "Execução local", abas **Execução** (Alt+X, inalterada) e **Atividade** (Alt+T):
seletor de execução (histórico do supervisor; "Atualizar lista" Alt+L), cartão "Acompanhamento da execução"
(estado do supervisor, execução local, início, tempo, motivo técnico — ou "Observação" quando o estado é sucesso —,
execução do Framework, fases observadas, fase/etapa atual com o tipo de ação, transações iniciadas/concluídas/com
falha/rejeitadas/puladas, última falha observada com etapa/código/resultado/tentativa/duração, situação da coleta),
alertas que não escondem nada (etapas indisponíveis, arquivo recusado/trocado, histórico limitado, eventos recusados,
lacuna, retenção, conteúdo desconhecido, coleta retomada), linha do tempo (filtros nível/origem/fase, ordem, busca só
em metadados; colunas #, hora, nível, evento, etapa, ação, resultado, tentativa, duração, fase, transação `T1…`,
origem), detalhe do evento selecionado e exportação. Textos em português; códigos e schema em inglês.
Escalas 100/150/200% conferidas (`ScaleEvidenceTest`, `O12-atividade-*`).

**Lista de execuções (revisão 23/10).** `RunStore.list()` devolve os registros legíveis, quantos registros existem mas
não puderam ser lidos e se a própria pasta falhou; só "não existe" CONFIRMADO (`Files.notExists`) é histórico vazio —
acesso negado nunca vira "nenhuma execução". A tela nunca mostra o seletor em branco: "Carregando histórico…",
"Nenhuma execução registrada" (com a explicação no cartão) ou "Histórico indisponível" com o aviso de erro
`AGT-OBS-014` e "Atualizar lista"; registros ilegíveis são contados num aviso próprio; falha ao atualizar uma lista já
mostrada mantém a lista e avisa que pode estar incompleta. Uma leitura da atividade que falha (exceção) chega à tela
como "Falha ao ler a atividade" e é repetida em 5 s (antes a aba ficava esperando sem resposta).

**Completude da linha do tempo (revisão 23/10).** `ActivityView.completeness(saúde, eventos)` — sem Swing — devolve
`COMPLETE`, `IN_PROGRESS`, `PENDING` ou `INCOMPLETE` com os motivos (cada um com código): limite de eventos
(`AGT-OBS-005`), limite de 500 do supervisor (`AGT-OBS-006`), linhas recusadas (`AGT-OBS-002`), lacuna de sequência ou
linha ilegível (`AGT-OBS-013`), histórico não gravado e descartado (`AGT-OBS-009`), gravação ainda pendente
(`AGT-OBS-009`, `STORE_PENDING`), atividade removida (`AGT-OBS-010`), etapas do
Framework interrompidas (`AGT-OBS-004`) ou indisponíveis (`AGT-OBS-001`), registro do supervisor deixou de ser lido
(`AGT-OBS-001`). "Completa" só com a coleta encerrada e nenhum motivo; coleta não encerrada é `PENDING`, nunca completa.
Na tela: aviso "Linha do tempo incompleta" (primeiro aviso, com todos os motivos), linha "Linha do tempo" no resumo,
título do quadro "Linha do tempo (incompleta)" e rodapé "… · linha do tempo INCOMPLETA"; a prévia da exportação diz
"ATENÇÃO: linha do tempo INCOMPLETA — …" com os motivos (os marcadores de lacuna vão no arquivo). Indisponibilidade
temporária do arquivo do Framework que se recupera (`SOURCE_AVAILABLE`) não é lacuna: a leitura continua da mesma
posição (correção: o histórico relido do disco também volta a `READING`). Campos desconhecidos descartados não tiram
eventos da linha do tempo e não a tornam incompleta (continuam no aviso próprio).

## 8. Fronteira com a Platform e com a STUDIO-AGENT-001 (desenho, nada conectado)

```
Processo/Framework → adaptador seguro do Agent → atividade.jsonl + aba do Agent → [futuro] transportador autenticado → Platform
```

- A fonte da verdade das execuções locais é o Agent (`agent_execution_id` + `sequence`). O transportador futuro deve:
  autenticar o Agent/tenant; enviar envelopes `nekkora-agent/observability` sem transformação semântica; receber ACK
  por `(agent_execution_id, sequence)` e reenviar de forma idempotente a partir do último ACK; respeitar `EVENT_GAP` e
  `HISTORY_PURGED` como lacunas declaradas (não fabricar o intervalo); aplicar filtragem por tenant/autorização e
  limites próprios; ter retenção que não apague o que ainda não foi confirmado. Nada disso existe hoje: não há
  endpoint, cliente, credencial nem mock de conectividade.
- STUDIO-AGENT-001: o Studio deverá CONSUMIR estado e atividade do Agent (o mesmo envelope, pelo canal local que essa
  missão definir, com permissão), sem ler `events.jsonl`/`atividade.jsonl` diretamente e sem segunda implementação de
  leitura. API candidata (já usada pela aba): `ActivityService.history()` e `ActivityService.view(id, afterSequence)`
  → `ActivityView` (registro do supervisor + eventos + saúde). Não é API pública ainda.

## 9. Limitações declaradas

1. O pacote do Studio usado no OBS-G1 é o pacote REAL gerado pelo Studio na SPEC-OPTIONAL-COLUMNS-001
   (`Nekkora-Studio/docs/spec-optional-columns-001/human-review/rodada-1/final`, idêntico ao do G1 da DESKTOP-001, hashes
   conferidos pelo Agent), **não regerado nesta rodada**: regerar exige rodar a homologação de interface do Studio, que
   grava evidências no repositório do Studio (outro produto, fora da autorização).
2. Ligação simbólica real só é testada onde o SO permite criá-la (no Windows sem privilégio o teste é PULADO e
   declarado; a junção — o caso do Windows — é testada e recusada).
3. Contagens de recusas anteriores a uma queda do Agent podem ser parciais (o resumo informa `restarts`).
4. Identidade do arquivo do Framework entre reinícios do Agent: a primeira linha é re-memorizada ao retomar; uma troca
   feita com o Agent fechado não é detectável por ela (a validação de correlação/horário continua valendo).
5. Desconexão de RDP continua sem sinal confiável (limitação herdada da DESKTOP-001).
