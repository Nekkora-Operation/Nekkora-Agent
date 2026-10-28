# STUDIO-AGENT-LOCAL-001: integração local controlada Studio → Agent (relatório para aprovação)

28/10/2026. Gate A aprovado pela titular. Esta é a primeira etapa da integração local, **sem a Platform API**.

**Tudo local:**
- sem push, PR, merge ou publicação;
- sem suspensão nem hibernação;
- Platform API, Studio e Framework não foram alterados;
- o Studio continua no Framework 0.3.0.

**O socket local NÃO foi habilitado nem implementado.** Antes dele, esta entrega traz o contrato detalhado, a análise
de ameaças, a matriz de autorização e os testes de isolamento, para revisão.

| Item | Valor |
|---|---|
| Repositório e branch | `Nekkora-Agent`, `feat/agent-studio-local-001` (local), sobre `f77e00e` |
| **Dependência** | a base contém a correção **F-AGT-01** (`9c81ddc`, `f77e00e`), ainda **pendente de revisão independente**. Esta branch herda essa pendência |
| ZIP de laboratório final | `nekkora-agent-0.1.0-windows-x64-lab.zip`, SHA-256 `5fa0354ee4d993bb478133e18038ce9e5b5fa0242c347759cff9b6b6549b6c84`, do commit `f0ecd83`, sem alterações locais |

## 1. Commits (fatias pequenas e reversíveis, nesta ordem)

| Commit | Fatia | Conteúdo |
|---|---|---|
| `b2a22b2` | **A01** | schema de observabilidade volta a ser JSON válido. Teste de contrato sobre o arquivo real |
| `f8a0117` | formato | `estado.json` 1.1.0 aditiva (origem, cancelamento, recuperado, progresso), sem comportamento novo |
| `b7cea25` | **A02** | reabertura lê o protocolo publicado e **conferido** |
| `aecc470` | **A03** | progresso parcial **verificado** no registro e na tela |
| `bf843fb` | **S0** (D-SA-04 e D-SA-05) | cancelamento por identificador, pedido × confirmação e origem do pedido. Achado F-FW-04 |
| `bd633a3` | **ISO** | testes de isolamento antes do canal (D-SA-01) e roteiro ISO-R |
| `645e7c9` | acabamento | tela (achado na evidência real) e atribuição de pipes do ISO-R |
| `f0ecd83` | ajuste | teste de bloqueio do Windows não roda no Linux. **ZIP final gerado daqui** |
| `1348f22` | documentos | contrato, ameaças, matriz, isolamento, `LocalControlContractTest` e evidências |
| `afe92f9` | ISO-R | atribuição dos pipes do Git Bash. É a versão do roteiro usada nas execuções reais finais (fora do ZIP) |
| (este) | regressão | `evidencias/regressao.md` e números de fechamento |

## 2. A01: schema de observabilidade

- **Defeito:** o arquivo publicado tinha `\.` (escape inválido em JSON) no padrão do `step_id`. Nenhum consumidor
  conseguia carregá-lo.
- **Correção:** só o escape. O teste prova que, desfeito o escape, o SHA-256 volta a ser o publicado na DESKTOP-001
  (`9a6c62d1…`). O contrato semântico 1.0.0 está preservado.
- **Teste de contrato** (`ObservabilitySchemaTest`, validador independente networknt 1.5.9 só em escopo de teste, fora
  do ZIP):
  - JSON estrito;
  - schema 2020-12 válido contra o metaschema;
  - superfície e padrões iguais aos do codec;
  - exemplos publicados;
  - **1 382 envelopes reais** da compat-001;
  - tudo que o codec escreve;
  - 13 recusas.
- **Vermelho no schema anterior:** 8/8.
- **Uso real:** o conferidor independente desta missão valida as atividades reais contra o **arquivo do repositório**
  (a1: 292 envelopes válidos). Na compat-001 isso só funcionava com uma cópia corrigida à mão.
- **Observações:**
  - o `$id` publicado é relativo, e os consumidores devem carregar o schema pela URI do arquivo. O `$id` não foi mudado;
  - o leitor do Agent aceita, de propósito, versões 1.x e `sequence` 0, enquanto o schema descreve o que é escrito
    na 1.0.0. A diferença está documentada no teste.

## 3. A02: recuperação depois de queda do Agent

| Antes | Agora |
|---|---|
| processo terminado enquanto o Agent estava fora → sempre `OUTCOME_UNKNOWN`, com `counts` vazio, ignorando o resultado publicado | o protocolo decide **se** for completo e coerente. Senão, continua `OUTCOME_UNKNOWN`, com o motivo |

**Regra** (`ProtocolEvidence`). Todas as condições:
1. `summary.json` do contrato, na pasta de saída que o Agent criou para esta execução;
2. estado **final** do Framework;
3. posterior ao lançamento;
4. diário do Framework terminando em `ENGINE_COMPLETED` da **mesma** execução e com o **mesmo** desfecho;
5. cópia do pacote ainda com a impressão registrada.

O estado sai do protocolo com o mesmo critério de sucesso de sempre, sem o código de saída. O registro fica com
`recovered = true` e `exitCode = null`. **Nada é relançado.** A execução adotada (processo vivo na reabertura) passou a
usar a mesma conferência.

- **Testes:** `ProtocolRecoveryTest` (8), com a saída **real** do a5 da compat-001 e o pacote real do Studio. Cobrem
  15 variações de evidência incompleta ou incoerente, todas `OUTCOME_UNKNOWN`. **Vermelho no anterior: 8/8.**
- **Mudança intencional de asserção existente:** `ExecutionPersistenceTest.falhaPersistenteDepoisDoLancamento` (da
  F-AGT-01) esperava `OUTCOME_UNKNOWN` mesmo com o resultado publicado. Agora exige `SUCCEEDED` recuperado, sem código
  de saída, com a posse preservada e nada relançado.
- **Limite declarado:** pacote de **laboratório** tem uma evidência só (`lab-result` 1.0.0), sem diário para cruzar.
- **Real (ZIP final):**
  - a5, Agent derrubado e automação terminando sozinha: **`FAILED`/AGT-EXE-022** com 11 `SUCCEEDED` + 1
    `FAILED_TECHNICAL`. Na compat-001 tinha ficado `OUTCOME_UNKNOWN`. Conferência independente 12/12;
  - a5b, Agent **e** automação derrubados, sem resumo: `OUTCOME_UNKNOWN`/AGT-EXE-025 com `PROTOCOL_REJECTED
    NO_PROTOCOL` e progresso parcial.
- **Observação:** o `STOPPED` do a5 (aqui e na compat-001) é legítimo e **não** foi causado pela queda. O 12º item da
  lista (Charles Darwin) não tem o campo "nacionalidade" na página (`TARGET_NOT_FOUND`).

## 4. A03: progresso parcial depois do cancelamento

**Fonte.** O checkpoint do Framework (`nekkora-rpa/checkpoint` 1.0.0/1.1.0), conferido pela **integridade que o próprio
Framework grava**: SHA-256 do corpo em JSON canônico idêntico ao `json.dumps(sort_keys, ensure_ascii, separators)` do
Python. Sai só a contagem por estado e "entregues". Nunca identificadores, valores ou detalhes.

**O que o registro guarda.** Fica em `progress`, **separado** do desfecho e das contagens finais:
- o estado continua `CANCELLED` ou `OUTCOME_UNKNOWN`;
- `IN_PROGRESS` aparece como "interrompida em andamento (desfecho desconhecido)";
- a planilha aparece "sem confirmação de consolidação".

**Durante o cancelamento:**
- o registro e a tela mostram "Aguardando confirmação" (pedido gravado como `PENDING` antes de encerrar a árvore);
- a aba Atividade continua com o progresso dos eventos;
- o checkpoint **não** é lido enquanto o processo vive (§7, F-FW-04);
- depois do encerramento, a leitura tenta de novo por até 3 s (bloqueio transitório do Windows, F-FW-03);
- persistindo o bloqueio: `CHECKPOINT_LOCKED`.

**Testes.** `PartialProgressTest` (6):
- checkpoints **reais** do a2 e do a5;
- recusas;
- **oráculo Python**: o `CheckpointStore` real do Framework grava textos difíceis, e o Agent confere. O oráculo pegou um
  erro meu no escape do DEL (0x7f) antes do commit;
- reabertura sem resumo;
- cancelamento **real** no meio;
- bloqueio transitório.

**Vermelho no anterior:** os 2 de comportamento.

**Tela.** O teste de desktop `cancelamentoComProgressoParcial` (D15 a D17) cobre a janela de execução, a janela
principal ("progresso parcial: N de M concluídas") e a aba Atividade (alerta com o texto inteiro).

**Real (ZIP final):**
- a2, cancelamento pela interface: `CANCELLED`, cancelamento confirmado, progresso 10 `SUCCEEDED` + 1 `IN_PROGRESS`;
- a5b: 8 `SUCCEEDED` + 1 `IN_PROGRESS`;
- nos dois, o progresso é **igual** ao checkpoint carregado pelo `CheckpointStore.load()` do Framework (conferidor
  independente), sem `summary.json` e com `counts` vazio.

## 5. S0: cancelamento por identificador (D-SA-04) e origem (D-SA-05)

- **Cancelamento.** `cancel(ExecutionId, source)` devolve um destes resultados:
  - `REQUESTED`;
  - `ALREADY_REQUESTED` (idempotente: vale o primeiro pedido);
  - `ALREADY_ENDED`;
  - `NOT_ACTIVE`;
  - `UNKNOWN_EXECUTION`.

  Recusa nunca toca a execução ativa. O pedido é gravado como `PENDING`. A confirmação vem do supervisor depois de
  encerrar a árvore (`CONFIRMED`) ou não vem (`NOT_EFFECTIVE`, quando o processo terminou antes e vale o protocolo).
  Eventos: `CANCEL_REQUESTED` → `TERMINATED` → `CANCEL_*`.
- **Origem.** Todo registro criado pelo Agent (inclusive recusa) tem `origin.kind = LOCAL_UI`. `STUDIO_LOCAL` está
  só reservado. É a origem do **pedido**; a procedência do pacote continua "não verificada".
- **Testes.** `CancellationTest` (6, processos reais, 5 rodadas seguidas verdes):
  - idempotência e confirmação;
  - recusas distintas;
  - não efetivado;
  - **8 pedidos concorrentes → 1 `REQUESTED`**;
  - encerramento do Agent;
  - origem.

## 6. Antes do socket: contrato, ameaças, autorização e isolamento

| Documento | Conteúdo |
|---|---|
| [`contrato-local-control-1.0.0.md`](contrato-local-control-1.0.0.md) | regras R1 a R10 (**nenhuma execução sem confirmação humana por pedido**; pedido não aprova pacote; só AF_UNIX, sem TCP; pareamento; origem × procedência; impressão calculada pelo Agent). Inclui transporte, handshake, repetição, 21 mensagens, estados do **pedido** separados da execução, idempotência persistida, limites, catálogo `AGT-LCL-001..013`, decisões P-01 a P-07 e fatias S1 a S5 |
| [`contrato/…schema.json`](contrato/nekkora-agent-local-control-1.0.0.schema.json) + `exemplos/` | 26 mensagens válidas e 33 inválidas. `LocalControlContractTest` (5): schema válido, cada tipo exemplificado, cada inválido recusado e **enums iguais aos do código** (estado, origem, cancelamento, catálogo) |
| [`analise-ameacas.md`](analise-ameacas.md) | ativos, atores e fronteiras; T1 a T15 com mitigação e residual. T1 (processo malicioso do mesmo usuário) aceito e declarado: o canal não lhe dá execução **sem a pessoa** |
| [`matriz-autorizacao.md`](matriz-autorizacao.md) | quem pode o quê (pessoa, Studio pareado, sem pareamento, outros), condições C1 a C15 de cada pedido e o que fica registrado |
| [`testes-isolamento.md`](testes-isolamento.md) | **implementados:** ISO-01 a 05, ISO-R real e controles positivos. **Especificados** para o canal: ISO-S01 a S19 |

**Isolamento comprovado agora:**
- `NetworkIsolationTest` (5). O guarda antigo deixava passar **8** formas de canal (ISO-02);
- **ISO-R no Agent empacotado real**: 0 TCP, 0 UDP, 0 AF_UNIX e 0 pipes do Agent, ocioso e durante as 4 execuções
  reais;
- controles positivos detectam TCP em loopback, UDP, AF_UNIX e pipe.

## 7. Achados novos

| Id | Onde | Achado | Situação |
|---|---|---|---|
| **F-FW-04** | **Framework** | no Windows, um leitor concorrente do `checkpoint.json` faz o `os.replace` do `atomic_write` falhar, e o engine para. Medido em 3 rodadas: **74% a 77%** das gravações em laço falham com leitor, 0% sem. Qualquer antivírus, backup ou indexador pode provocar | **não corrigido** (fora do escopo). O Agent foi desenhado para nunca ler com o processo vivo. Recomendada missão no Framework: nova tentativa curta no `atomic_write`, a mesma família do F-FW-03 |
| SAL-01 | instrumento | a 1ª versão do ISO-R atribuía ao Agent pipes de outros programas. Corrigido: atribuição pelo PID no nome, sem abrir o pipe, com controles positivos | corrigido. Tentativas descartadas guardadas |
| SAL-02 | contrato | o AF_UNIX recusa caminhos longos ("Unix domain path too long") | entrou no contrato (100 caracteres, sem fallback TCP) |
| SAL-03 | catálogo | AGT-EXE-024 diz "cancelada pela pessoa operadora" também quando o cancelamento vem do encerramento do Agent | texto do catálogo **mantido**. A linha "Cancelamento" agora diz a fonte |
| SAL-04 | compatibilidade | o Agent anterior (`cd04a7c4…`) lê só `estado.json` 1.0.0 | voltar de versão não é suportado. Nada se perde no disco |

## 8. Regressão

| Ambiente | Resultado |
|---|---|
| Windows `mvnw -o verify` (`afe92f9`, fechamento) | **245** casos, 0 falhas, 1 pulado (o de sempre). Base `f77e00e`: 203 |
| Desktop `mvnw -o -Pdesktop verify` (`afe92f9`, janela real) | **268** casos, 0 falhas, 1 pulado. Base: 227 |
| Linux Docker `eclipse-temurin:21-jdk-noble` (`afe92f9`, sem Python) | 245 casos: **as mesmas 15 falhas herdadas** (ShortcutTest 13, AgentBootstrapTest 1, SensitiveDataLeakTest 1, dependentes do Windows), 0 erros, 36 pulados |
| Empacotamento (`clean verify` dentro do `package-windows.ps1`, `f0ecd83`) | verde. Gera o ZIP `5fa0354e…` |
| Histórico por fatia | `evidencias/regressao.md` |

**Ambiente:** os testes com processo exigem um Python com `nekkora_rpa` **0.3.0**. A `.venv` do repositório do
Framework está em 0.4.0, na branch F-FW. Usei `NEKKORA_TEST_PYTHON` apontando para a venv 0.3.0 que o Studio instalou
na compat-001.

## 9. Evidência de execução real (`evidencias/real/`, ZIP `5fa0354e…`)

| Cenário | Roteiro | Agent | Conferência independente | ISO-R |
|---|---|---|---|---|
| a1, sucesso (5 artigos) | 10/10 | `SUCCEEDED` | **23/23** (`conferir-sal.py`, schema real, planilha = teste autorizado do Studio, `origin` `LOCAL_UI`) | ocioso e em execução: isolado |
| a2, cancelamento pela interface | 12/12 | `CANCELLED`, cancelamento `AGENT_SHUTDOWN` confirmado, progresso 10 + 1 | progresso = checkpoint carregado pelo Framework | isolado |
| a5, Agent derrubado | 14/14 | **`FAILED`/022 recuperado** (antes: `OUTCOME_UNKNOWN`) | **12/12** (`conferir_recuperacao.py`) | isolado |
| a5b, Agent e automação derrubados | 15/15 | `OUTCOME_UNKNOWN`/025, `PROTOCOL_REJECTED NO_PROTOCOL`, progresso 8 + 1 | progresso = checkpoint do Framework | isolado |

**Tentativas descartadas** (registradas no LEIAME do pacote de revisão):
- 1ª rodada com o ZIP `78ac0fa1…`, antes do acabamento de tela;
- 1ª medição ISO-R com atribuição errada de pipes;
- duas rodadas em que um erro de escape no meu roteiro de chamada fez os cenários dividirem a mesma pasta. Por isso o
  a2 encontrou o pacote já aprovado e parou.

Nenhuma foi usada como evidência.

## 10. Compatibilidade preservada

- **Pacotes existentes:** o mesmo pacote real do Studio (impressão `5664f1a5…`, manifesto 1.5.0, Framework 0.3.0) roda
  sem mudança. O que o Agent aceita (manifesto 1.4 a 1.9, versão exata do Framework e recusas) não mudou.
- **Envelope de observabilidade 1.0.0:** inalterado.
- **Estados da execução:** inalterados. Os estados do pedido do canal ficam em outro objeto.
- **Studio:** não alterado. Não foi adaptado ao Framework 0.4.0 (missão própria).
- **Platform API:** não tocada.

## 11. O que a titular decide agora

1. Aprovar ou ajustar o contrato, a análise de ameaças, a matriz e os testes de isolamento. Só então a S1 cria o
   pacote `local`.
2. Responder P-01 a P-07 (contrato §9). Recomendações: confirmação por pedido, sem MAC por quadro, sem TCP, um
   pareamento, 10 min, cópia revisada do Bridge e `estado.json` 1.2.0.
3. Autorizar, ou não, uma missão no Framework para o **F-FW-04** (com o F-FW-03).
4. Lembrete: a revisão independente da **F-AGT-01** continua pendente e é base desta branch.
