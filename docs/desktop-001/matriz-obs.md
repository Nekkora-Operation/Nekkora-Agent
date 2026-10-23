# NEKKORA-DESKTOP-001 — Adendo OBS-001: matriz OBS-G1…G8

Data: 23/10/2026. Branch local `feat/nekkora-desktop-001`. Código homologado: `22926cc` (commits OBS-001: `fc981ff`,
`6d8f12c`, `0ad0bc8`, `a649ecc`, `22926cc`). Ambiente: Windows 11 Pro 10.0.26200, sessão "Console" 1, 1920×1080,
Java 21.0.12, Python 3.12 com `nekkora-rpa` 0.3.0 (venv do Framework). Studio FECHADO, sem Platform, sem rede além da
Wikipédia pública usada pelo próprio pacote do Studio.

Tipos: **Auto** (`./mvnw -o clean verify`, com processos Python reais quando indicado) · **Desktop** (`-Pdesktop`,
janela real) · **Real** (roteiro na janela real do Agent, `DesktopHomologation`) · **Pacote** (`.exe` empacotado, PATH
`C:\Windows\System32;C:\Windows`, sem `JAVA_HOME`) · **Pendente físico** (exige a titular presente).

## Suítes

OBS-PERSISTENCE-HARDENING (`5c3fd6e`): Windows **184/0/1**; `-Pdesktop` **205 + escalas, 0 falhas, 1 pulado**; Linux
`--init` **184, 15 falhas (= base), 18 pulados** (16 + 2 só Windows); ensaio do roteiro físico 25/25
(`relatorio.md` §8.2).

Revisão da titular (23/10): Windows `clean verify` no `a59aad3` **175/0/1**; `-Pdesktop clean verify` no `a59aad3`
**195 + escalas 150/200, 0 falhas, 1 pulado**; Linux `--init` no `a59aad3` **175, 15 falhas (= base), 16 pulados**
(`relatorio.md` §8.1). Abaixo, a entrega anterior.

| Suíte | Commit | Executados | Falhas | Pulados |
|---|---|---|---|---|
| Windows `./mvnw -o clean verify` (dentro de `tools/package-windows.ps1`) | `22926cc` | 163 | 0 | 1 |
| Windows `./mvnw -o -Pdesktop clean verify` (+ JVMs de escala 150/200%) | `a282f1f` (código `22926cc` + documentos) | 181 + 1 + 1 | 0 | 1 | capturas `evidencias/OBS-desktop` |
| Linux `eclipse-temurin:21-jdk-noble` com `--init` (2 rodadas) | árvore de `22926cc` | 163 | 15 (= base) | 16 | `relatorio.md` §8 |
| Linux, mesmo contêiner sem `--init` (2 rodadas) | árvore de `22926cc` | 163 | 16 | 16 | +`ProcessOwnerTest.arvorePropria` (zumbi sob o PID 1; ver §8) |

O pulado é `SourceFileTest.symbolicLinksAreRefused`: criar ligação simbólica no Windows exige privilégio que a sessão
não tem (declarado, não contado como aprovado); a junção do Windows (`mklink /J`) — o caso realista — é testada e
recusada (`windowsJunctionInThePathIsRefused`).

## Matriz

| Id | Cenário | Resultado | Tipo | Evidência |
|---|---|---|---|---|
| OBS-G1 | Pacote REAL do Studio (biografias da Wikipédia, lista com 4 artigos públicos), Studio fechado: a aba mostra INIT → PROCESS → END, `search.press_enter`, `profile.read_birth_date`, transações 4/4, e encerra com o estado do supervisor; capturas DURANTE a execução; histórico igual depois de reiniciar o Agent | **Aprovado** 14/14 | Real | `evidencias/OBS-G1-studio` (7 capturas durante, `homologacao-log.txt`, `linha-do-tempo.txt`, `atividade-exportada.jsonl`) |
| OBS-G1 | Mesmo fluxo na janela real com o pacote no formato do Studio que usa o `EventLogger` real (etapas durante, EDT < 250 ms, concluída, filtro) | **Aprovado** | Desktop | `ActivityDesktopTest`, capturas `O01`/`O02` |
| OBS-G1 | Agent EMPACOTADO sem Java no PATH: execução viva publicando eventos do Framework adotada depois de queda; aba aberta pelo teclado (Alt+E, Alt+T); atividade cresce durante (20 → 40 → 58); fim pelo protocolo; histórico intacto ao reabrir | **Aprovado** 15/15 | Pacote | `evidencias/OBS-pacote` (`P-OBS-01…05`, `resultado.txt`, `atividade-execucao.jsonl`) |
| OBS-G2 | Pacote sem Framework: só ciclo de vida (`STARTING`, `RUNNING`, `SUCCEEDED`), "Etapas não disponíveis para este tipo de processo"; 33.040.000 bytes de stdout/stderr drenados, só contados; consulta da atividade durante a drenagem: pior caso 0–3 ms; histórico < 4 KiB | **Aprovado** | Auto (Python real) + Desktop | `ObservabilityProcessTest.genericoVolumoso`, `ActivityDesktopTest` (`O04`) |
| OBS-G3 | Sentinelas sintéticas (CPF, CNPJ, e-mail, token, senha, caminho de cliente, valor de planilha, URL com consulta, traceback) em `detail`/`message`/`value`/`key`/`cause_type`/`url`/objeto aninhado, em `step_id`/`action`/`outcome`/`error_code`, em linhas corrompidas/aninhadas/enormes e em stdout/stderr de um processo REAL: zero sentinela em `atividade.jsonl`, `eventos.jsonl`, `estado.json`, exportação e tela (tabela, detalhe de cada linha, alertas) | **Aprovado** | Auto (Python real) + Desktop | `ObservabilityProcessTest.sentinelasNaoVazam`, `EnvelopeTest.sensitiveAndArbitraryFieldsAreDroppedAndCounted`, `ActivityDesktopTest` (`O03`) |
| OBS-G3 | Controles negativos: o arquivo bruto do Framework TEM os sentinelas; a saída do processo foi drenada; o detector acusa um vazamento introduzido de propósito na exportação e no histórico | **Aprovado** | Auto | `ObservabilityProcessTest.sentinelasNaoVazam` |
| OBS-G3 | Dados reais: nenhum dos 8 valores da lista de entrada (nomes e endereços) aparece na atividade, na tela ou na exportação | **Aprovado** | Real | `evidencias/OBS-G1-studio/homologacao-log.txt` |
| OBS-G4 | Linha parcial (declarada só na leitura final), corrompida, UTF-8 inválido, linha de 20 KB, JSON aninhado, campo fora do formato, evento velho, outra execução do Framework, versão maior desconhecida | **Aprovado** | Auto | `SourceFileTest`, `EnvelopeTest.invalidLinesAreRejectedWithClosedReasons`, `ActivityServiceTest.corrupted…` |
| OBS-G4 | 10 mil eventos: lidos em 13 rodadas (contrapressão), ~200 ms; acima do limite (22 mil): um `EVENT_GAP`, descartados contados, arquivo ≤ limite | **Aprovado** | Auto | `ActivityServiceTest.tenThousand…`, `.aboveTheCap…`, `ActivityLogTest.cap…` |
| OBS-G4 | Truncamento/rotação/troca após abrir → `SOURCE_REPLACED`, nada relido; arquivo ausente ao fim → `SOURCE_UNAVAILABLE` (`AGT-OBS-001`); permissão negada (ACL real `icacls /deny`) → indisponível e recuperação; junção do Windows → `SOURCE_UNSAFE`; ligação simbólica → recusada (pulado sem privilégio) | **Aprovado** (symlink: pulado declarado) | Auto | `SourceFileTest`, `ActivityServiceTest` |
| OBS-G4 | Limite de 500 do supervisor vira `EVENT_GAP/AGT-OBS-006`; cota total purga a atividade das mais antigas (`HISTORY_PURGED`), nunca a mais recente; registro do supervisor fica | **Aprovado** | Auto | `ActivityServiceTest.supervisorEventLimit…`, `.totalQuota…` |
| OBS-G5 | Queda do Agent no meio de uma execução REAL, `reconcile()`/`ADOPTED`, retomada da coleta: sequência contínua, cada linha do Framework exatamente uma vez, `ENGINE_STARTED` uma vez (nada relançado), `COLLECTION_RESUMED`, desfecho pelo protocolo | **Aprovado** | Auto (Python real) + Pacote | `ObservabilityProcessTest.quedaAdocaoERetomada`, `ActivityServiceTest.restart…`, `evidencias/OBS-pacote` |
| OBS-G5 | Reabrir o Agent depois do fim: nada recoletado (arquivo com o mesmo SHA-256), histórico com a mesma contagem | **Aprovado** | Real + Pacote + Desktop | `OBS-G1-studio`, `OBS-pacote`, `ActivityDesktopTest` (`O05`) |
| OBS-G6 | Win+L e suspensão REAL durante a execução, com a aba Atividade: `SESSION_LOCKED/UNLOCKED`, `SUSPENSION_DETECTED`, continuidade, terminal sem sucesso fictício, sem duplicar | **PENDING_PHYSICAL_HOMOLOGATION** | Pendente físico | roteiro único `tools/homologation/fisica-g2-a13.ps1` ([`homologacao-fisica.md`](homologacao-fisica.md)); ENSAIO sem bloqueio/suspensão 25/25 (`evidencias/G2-A13-ensaio`); simulação automática: `ExecutionControllerTest.bloqueioESuspensao` |
| OBS-R1 | Lista de execuções: "Carregando…", "Nenhuma execução registrada" (com explicação) e "Histórico indisponível" (`AGT-OBS-014`, ACL real negada) são distintos; registros ilegíveis contados; nunca seletor em branco | **Aprovado** | Auto + Desktop | `ExecutionDomainTest.historicoDistingueVazioDeFalha`, `ActivityDesktopTest.historicoVazioEFalhaDeLeitura` (`O06`, `O07`) |
| OBS-R2 | Linha do tempo parcial nunca parece completa: `COMPLETE`/`IN_PROGRESS`/`PENDING`/`INCOMPLETE` com motivos e códigos (limites, recusas, lacuna, gravação, retenção, origem do Framework ou do supervisor interrompida); aviso, título, rodapé e prévia da exportação | **Aprovado** | Auto + Desktop | `CompletenessTest` (9), `ActivityTextTest`, `ActivityDesktopTest` (`O03` incompleta, `O02` completa) |
| OBS-R3 | Leitura sem resposta não congela a aba (aviso + nova tentativa) | **Aprovado** | Desktop | `ActivityDesktopTest.leituraPerdidaNaoCongelaAAba` |
| OBS-R4 | OBS-PERSISTENCE-HARDENING: gravação do histórico que falha fica pendente (limitada, em ordem), visível (`AGT-OBS-009`), conferida contra o fim do arquivo e gravada uma vez; desistência/fila cheia/arquivo alterado → perda contada e lacuna mantida após reinício; automação intocada | **Aprovado** (automático; ensaio 25/25) | Auto + Desktop (leitor real do Windows) | `ActivityPersistenceTest` (9), `ActivityDesktopTest.gravacaoPendenteFicaAVista` (`O13`); `relatorio.md` §8.2 |
| OBS-G7 | Mesma execução analisada pela linha do tempo e pelo `events.jsonl` original: 231/231 eventos batem na mesma posição (código, fase, `step_id`, execução do Framework); Agent `ex-…(32)` ↔ Framework `ex-…(20)` distintos e correlacionados; `step_id` → `studioStepId` só pelo manifesto conferido, nenhum passo fora do manifesto recebe correspondência | **Aprovado** | Real + Auto | `OBS-G1-studio/homologacao-log.txt`, `ObservabilityProcessTest.sentinelasNaoVazam` |
| OBS-G8 | Testes de parser/adaptador/retenção/controlador; UI real Windows empacotada sem Java no PATH; regressões | **Aprovado** (exceto físicos) | Auto + Desktop + Pacote | §Suítes, §Regressão final |

## Regressão final

Windows `-Pdesktop clean verify` no `a282f1f` (o commit seguinte só acrescenta as capturas geradas por esta mesma
execução e estas linhas): 181 testes + `ScaleEvidenceTest` em 150% e 200%, 0 falhas, 1 pulado (ligação simbólica).
Windows `clean verify` no `22926cc`: 163, 0 falhas, 1 pulado. Linux: `relatorio.md` §8.

## Pendências que dependem da titular

- **G2/A13/OBS-G6 físicos**: Win+L real e suspensão real (S3) durante uma execução, e A13 com o pacote. O Agent NUNCA
  bloqueia, suspende nem desbloqueia sozinho. Roteiro em `relatorio.md` §6. Até lá: `PENDING_PHYSICAL_HOMOLOGATION`.
- Revisão da titular; merge/publicação continuam bloqueados.
