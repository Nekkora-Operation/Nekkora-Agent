# NEKKORA-DESKTOP-001 — Relatório: lacunas, decisões, riscos e procedimento

Data: 22/10/2026; adendo OBS-001 (observabilidade local) em 23/10/2026. Estado: **implementada e homologada em
Windows real, exceto G2/A13/OBS-G6 com a titular presente** (bloqueio real da tela e suspensão real do Windows) —
`PENDING_PHYSICAL_HOMOLOGATION`. Tudo local: nenhum push, PR, merge, tag ou release. Observabilidade:
[`observabilidade.md`](observabilidade.md) e [`matriz-obs.md`](matriz-obs.md).

## 1. Commits locais (`feat/nekkora-desktop-001`, base `d465296`)

| Commit | Conteúdo |
|---|---|
| `7d44327` | Motor de execução local supervisionada (domínio, sem interface) |
| `e60b8fa` | Janela "Execução local", estado na janela principal e diagnóstico |
| `8c57e74` | Janela principal compacta (linha + botão; ≤ 720 px) e roteiro de homologação na janela real |
| `34a8fb5` | Aprovação sem nome de pasta (achado G1, teste antes da correção) e evidência do pacote real do Studio |
| `19647b4` | Confirmação de saída com atalhos e homologação do pacote (queda, adoção, saída) |
| `de2170f` | Documentação final: arquitetura, matriz, relatório |
| `fc981ff` | **OBS-001**: domínio `obs` (envelope `nekkora-agent/observability` 1.0.0, leitor seguro, allowlist, histórico limitado, serviço), aba "Atividade", marcador de limite no `RunStore`, testes |
| `6d8f12c` | OBS-001: colunas por relevância, mnemônico sem conflito, roteiros `obs-studio`/`semear-obs`/`obs-001-pacote.ps1` |
| `0ad0bc8` | OBS-001: sucesso com código = "Observação"; roteiro físico `sessao` confere a aba (OBS-G6) |
| `a649ecc` | OBS-001: lote de cada rodada na ordem de ocorrência; exemplos documentados conferidos pelo codec |
| `22926cc` | OBS-001: fases observadas na ordem canônica (código homologado) |
| (este)   | OBS-001: documentação, matriz OBS-G1…G8, evidências |

Ver diff: `git diff d465296..feat/nekkora-desktop-001 --stat`.

## 2. Comportamento NÃO implementado (explícito)

- Canal Studio↔Agent (IPC, HTTP, pareamento, gatilho): é a STUDIO-AGENT-001.
- Agent↔Platform, telemetria remota, licença, download de pacotes. A OBS-001 só DESENHA a fronteira (envelope, ACK por
  sequência, lacunas declaradas): nenhum endpoint, cliente, credencial ou mock de conectividade.
- Protocolo opt-in de atividade para processos genéricos (não foi necessário; seria outro adaptador do mesmo envelope).
- Captura de tela, inspeção de DOM, vídeo, depurador interativo, visualização remota.
- Segredos: pacote que declara credencial é recusado (`AGT-EXE-006`).
- Detecção de desconexão RDP (sem sinal confiável sem API nativa; o bloqueio da tela é detectado).
- Serviço Windows, Session 0, elevação, execução como outro usuário, agendamento, concorrência (> 1 execução).
- Retomada/nova tentativa: nenhum desfecho incerto é repetido, por desenho.

## 3. Lacunas de prova

| Lacuna | Situação | Como fechar |
|---|---|---|
| Bloqueio/desbloqueio REAL da tela durante a execução | Lógica provada com sonda simulada; a sonda real foi lida na sessão 1 (sem bloqueio) | §6, passo 1 |
| Suspensão/retomada REAL (A13 da AGENT-001 e da execução) | Detecção por salto de relógio provada com relógio simulado | §6, passos 1–2 |
| Bloqueio por tela bloqueada no INÍCIO, em tela real | Simulado (com a tela bloqueada não há quem clique em Iniciar; o caso real é a execução agendada, fora do escopo) | — |
| `-Pdesktop` completo no HEAD final | **Feito** (§8) | — |
| Linux | 15 falhas pré-existentes idênticas à base (caminhos do Windows nos testes da AGENT-001) + 1 artefato do contêiner sem `init` (§8) | Fora do escopo; não mascaradas |
| OBS-G6: Win+L e suspensão reais com a aba Atividade | `PENDING_PHYSICAL_HOMOLOGATION`; lógica provada com sonda/relógio simulados | §6, passo 1 (o roteiro já confere a aba) |
| Regerar o pacote do Studio | Não feito: o OBS-G1 usou o pacote REAL gerado pelo Studio na SPEC-OC (idêntico ao do G1) — regerar roda a homologação de interface do Studio, que grava no repositório do Studio | Rodar no Studio com autorização |
| Ligação simbólica no Windows | Teste PULADO sem privilégio; junção testada e recusada; no Linux a ligação simbólica é testada e recusada | Rodar como administrador/modo desenvolvedor se desejado |
| Leitor de tela | Não testado (como na AGENT-001) | — |

## 4. Decisões

1. **Autorização = aprovação local da impressão digital** (não o manifesto). Mudou um byte → outra impressão → bloqueado.
2. **Cópia conferida do pacote por execução**: fecha a corrida "conferiu e depois trocou o arquivo".
3. **`SUCCEEDED` só por protocolo** (Studio `summary.json`; laboratório `agent-result.json`); código 0 sozinho =
   `OUTCOME_UNKNOWN`.
4. **Posse por PID + início + executável**; descendentes só se iniciados depois da raiz.
5. **Sessão por `tasklist.exe`** (sistema, caminho absoluto, argumentos fixos) em vez de JNA: nenhuma dependência nova.
6. **Suspensão por salto de relógio** (> 20 s entre ticks de 500 ms): sem API de energia nativa.
7. **stdout/stderr descartados**, só contagem: o framework publica os próprios eventos sem valores.
8. **Reconciliação adota, nunca relança**; o tempo mostrado de uma execução adotada conta a partir da adoção.
9. **Python escolhido pela pessoa** (o do ambiente do projeto) e conferido; o Agent não instala nada.
10. **Saída com execução ativa pede confirmação**, "Voltar" como padrão; fechar a janela de execução não cancela.
11. **OBS-001 lê arquivos já gravados** (supervisor e Framework) em vez de mudar o `ExecutionController`: mesma coleta ao
    vivo e depois de reinício; o supervisor não depende de `obs`.
12. **Sequência e posição gravadas no próprio evento** (sem cursor separado): retomada sem duplicar mesmo com queda no
    meio da escrita (última linha incompleta cortada).
13. **Lista de permissão, não lista de bloqueio**: `detail`/`key`/`cause_type`/campos desconhecidos nunca entram, mesmo
    válidos; identificadores com 4+ dígitos seguidos são recusados.
14. **Falha do leitor = telemetria**: nunca cancela, atrasa ou muda o desfecho da automação.

## 5. Riscos

| Risco | Mitigação / estado |
|---|---|
| Processo da automação abre filhos que se desligam da árvore (ex.: Chrome reaproveitado) | `--channel chrome` com perfil do Playwright: o navegador nasce como descendente do Python (driver do Playwright) e entra na árvore possuída; processo que saia da árvore NÃO é encerrado (preferimos deixá-lo vivo a encerrar algo que não é nosso) |
| `tasklist` lento ou ausente | Sonda com prazo; falha = sessão indisponível → `BLOCKED` (nunca presume disponível) |
| Suspensão curta (< 20 s) não detectada | Aceito: abaixo do limiar, o processo segue e o resultado vem do protocolo |
| Relógio ajustado manualmente | Pode registrar uma suspensão falsa; efeito só no registro (desfecho continua pelo protocolo) |
| Pacotes antigos do Studio (manifesto < 1.4.0) | Recusados (`AGT-EXE-003`); gerar de novo no Studio |
| Chrome ausente na máquina | Falha do próprio framework (`INIT_FAILED` → `FAILED`); pré-requisito declarado |
| Execução muito longa (> 20 mil eventos ou 8 MiB) | Histórico com `EVENT_GAP` e contagem do que foi descartado; nada é prometido como entregável |
| Pasta de saída com ACL aberta escolhida pela pessoa | O `events.jsonl` do Framework herda a ACL do destino; o Agent confere dono/ligações e só lê; o histórico do Agent fica no perfil do usuário |
| Futuro Framework 1.x com campo novo útil | Descartado e contado até a lista de permissão ser ampliada (decisão consciente) |

## 6. Procedimento de homologação pendente (titular presente, máquina livre)

Pré-requisitos: Agent compilado (`./mvnw -o -DskipTests package` + classpath de teste) e o Python do framework
(`..\Nekkora-RPA-Framework\.venv\Scripts\python.exe`). Sem jogos/chamadas abertos; não mexer no mouse durante o
`Robot`.

1. **Sessão durante a execução (G2 + OBS-G6)** — `java -cp <classpath de teste> com.nekkora.agent.ui.DesktopHomologation sessao
   <evidências> <pacote lab_efeito> <python> <saída>`. Quando o roteiro pedir: Win+L, ~10 s, desbloquear; depois
   Iniciar → Suspender, ≥ 1 min, retomar. Esperado: `SESSION_LOCKED`/`SESSION_UNLOCKED`, `SUSPENSION_DETECTED`, estado
   terminal sem relançamento, `efeitos.txt` com UMA linha, um único `LIFE_START`; na aba Atividade (aberta pelo
   roteiro): os mesmos fatos com horário de ocorrência e de observação, sequência contínua, desfecho do supervisor,
   capturas `G6-atividade-*` e `atividade-exportada.jsonl`.
2. **A13 com o pacote** — abrir o `.exe` do ZIP; `tools/homologation/suspend-check.ps1 -Fase antes`; suspender ≥ 1 min;
   retomar; `-Fase depois`. Esperado: mesmos PIDs, um ícone, sem `LIFE_START` novo.
3. **Regressão desktop no HEAD** — `./mvnw -o -Pdesktop clean verify`.

Homologações já feitas (reproduzíveis): OBS-G1 `DesktopHomologation obs-studio <evidências> <pacote do Studio> <python>
<lista> <saída>` (`evidencias/OBS-G1-studio`), OBS no pacote `tools/homologation/obs-001-pacote.ps1 -Python …
-TestClasspath …` (`evidencias/OBS-pacote`), G1 `DesktopHomologation studio …` (`evidencias/G1-studio`), pacote
`tools/homologation/desktop-001-pacote.ps1 -Python … -TestClasspath …` (`evidencias/D-P1-pacote`), regressão
`homologate-package.ps1` (`evidencias/regressao-pacote-agent001`).

## 7. Pré-requisitos reais de uma máquina

Windows 10/11 com sessão interativa; o pacote do Agent (runtime próprio, sem Java no PATH); para pacotes do Studio, o
Python do ambiente do projeto com `nekkora-rpa` na versão do manifesto (o Agent não usa Python global por conta própria)
e o Google Chrome instalado. Pacotes de laboratório: qualquer Python ≥ 3.12.

## 8. Regressão no código final (OBS-001)

| Suíte | Commit | Executados | Falhas | Pulados | Observação |
|---|---|---|---|---|---|
| Windows `./mvnw -o clean verify` | `22926cc` | 163 | 0 | 1 | pulado: ligação simbólica sem privilégio (junção testada) |
| Windows `./mvnw -o -Pdesktop clean verify` + escalas 150/200% | `a282f1f` | 181 + 1 + 1 | 0 | 1 | o mesmo pulado; capturas em `evidencias/OBS-desktop` |
| Linux `eclipse-temurin:21-jdk-noble` (cópia da árvore de `22926cc` + documentos) | — | 163 | 16 | 16 | 15 = falhas da base + `ProcessOwnerTest.arvorePropria` (zumbi sob o PID 1 do contêiner) |
| Linux, mesmo contêiner com `--init` (2 rodadas) | — | 163 | **15** | 16 | exatamente as 15 da base |
| Linux base `de2170f` (antes da OBS-001) | `de2170f` | 129 | 15 | 11 | referência |

As 15 falhas Linux são as mesmas da base, nos mesmos testes (`ShortcutTest` 13, `AgentBootstrapTest` 1,
`SensitiveDataLeakTest` 1 — caminhos do Windows nos testes da AGENT-001). `ProcessOwnerTest.arvorePropria` (código e
teste inalterados desde a base) falha no contêiner SEM `init`: o neto encerrado vira zumbi sob o PID 1 (a JVM do
Maven não recolhe filhos) e `isAlive()` continua verdadeiro, dependendo de quanto tempo o pai viveu depois do neto; com
`--init` passa nas duas rodadas, e passa isolado. Pulados no Linux (16): `ExecutionControllerTest` 8 e
`ObservabilityProcessTest` 3 (sem Python com `nekkora_rpa` no contêiner), `LaunchTest` 1, `SourceFileTest` 2 (junção é
do Windows; permissão negada não se aplica ao root), `ConfigStoreTest` 1, `InstanceLockTest` 1 (como na base). A
ligação simbólica, pulada no Windows, é testada e recusada no Linux. Resumo: `evidencias/OBS-linux/resumo.txt`.
