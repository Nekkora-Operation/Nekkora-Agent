# NEKKORA-DESKTOP-001 — Preflight (G0)

Data: 22/10/2026. Nada foi editado antes deste levantamento.

## 1. Repositório

| Item | Estado |
|---|---|
| Repositório | `Nekkora-Operation/Nekkora-Agent`, local `<pasta-do-repositorio>` |
| Remoto | **vazio** (`git ls-remote` não devolve nada): nenhum commit do Agent está no GitHub |
| `main` local | `afd1bb5` (commit inicial mínimo) |
| AGENT-001 | branch local `feat/nekkora-agent-001`, `d465296` (docs finais), código homologado em `2815a18`; **não mergeada, não publicada** |
| Esta missão | branch local `feat/nekkora-desktop-001`, criada de `d465296` |
| Working tree | limpo (nada a preservar além do histórico) |
| Build | Java 21 (`openjdk 21.0.12`), Maven Wrapper, design system do Studio construído em `.design-system/m2` (`.mvn/maven.config`) |
| Linha de base | `./mvnw -o clean verify`: **108 testes, 0 falhas, 0 pulados** (grupo `desktop` excluído); perfil `-Pdesktop` = testes de janela real + escalas 150/200 (rodado na homologação) |

## 2. O que a AGENT-001 já entrega

| Peça | Estado | Evidência |
|---|---|---|
| App Java 21/Swing com design system Nekkora, janela compacta 540 px | implementado | `ui/MainWindow`, A02 |
| Bandeja (restaurar, menu, sair), janela oculta | implementado | `os/TrayController`, A04–A06 |
| Instância única por usuário (trava em arquivo + ativação da 1ª) | implementado | `storage/InstanceLock`, A03 |
| Configuração estrita versionada, gravação atômica | implementado | `storage/ConfigStore`, `AtomicWriter`, `StrictJson` |
| Logs com rotação e sem dados sensíveis; diagnóstico exportável por lista fechada | implementado | `diagnostics/AgentLog`, `DiagnosticReport`, A11 |
| Atalhos Studio/Platform validados (sem shell) | implementado | `os/ShortcutPolicy`, `ShortcutLauncher`, A10 |
| Escalas 100/150/200%, teclado | implementado | A12 |
| Pacote Windows (jpackage) isolado | implementado | `tools/package-windows.ps1`, A14 |
| Estados de integração fixos ("Execução indisponível nesta versão") | implementado | `core/IntegrationStatus`, A15 |
| **A13 — suspensão e retorno do Windows** | **pendente de prova** | ocioso/oculto medido; suspensão nunca exercitada; roteiro `tools/homologation/suspend-check.ps1` |
| Execução de automações | **ausente** (por desenho na AGENT-001) | — |

## 3. Como o Studio lança hoje um projeto gerado (sem mudar o Studio)

Fonte: `Nekkora-Studio/Nekkora-AI-Engine/.../pycheck/SupervisedRun.java` (teste autorizado) e os manifestos gerados.

- Projeto = pasta com `manifest.json` (`nekkora-studio/codegen-manifest`, 1.0.0 … 1.5.0) que lista **cada arquivo com
  SHA-256** (calculado sobre o texto com LF); o Studio confere todos antes de lançar (`CODE_CHANGED_SINCE_GENERATION`).
- Comando: `<python do ambiente> -E -s -B -X utf8 main.py <opções> --channel chrome [--headless]`, argumentos em lista,
  diretório de trabalho = pasta do projeto, ambiente MÍNIMO (lista fechada de variáveis; nada de chaves, segredos ou
  `PYTHONPATH`).
- Opções pelo manifesto (`cli.options`: `--input`/`--spreadsheet`/`--output`; antes `--entrada`/`--planilha`/`--saida`);
  `execution`: `single` (entrada JSON), `list` (lista CSV/XLSX + planilha), `tabular` (só `--output`).
- Ambiente Python: um por automação e versão do framework, preparado pelo Studio
  (`<dados do Studio>/ambientes/<hash>/0.3.0-excel/venv/Scripts/python.exe`, `nekkora-rpa[excel]==0.3.0` + Playwright).
- Resultado estruturado do framework: `<output>/execucao/summary.json` (`status` COMPLETED, COMPLETED_WITH_FAILURES,
  STOPPED, INIT_FAILED, CANCELLED; `counts`; `output`), eventos `execucao/events.jsonl` sem valores; códigos de saída do
  `main.py`: 0 COMPLETED, 1 COMPLETED_WITH_FAILURES, 2 STOPPED, 3 INIT_FAILED, 4 CANCELLED.
- Cancelamento: o Studio encerra a árvore de processos.
- **O manifesto descreve a geração e a compatibilidade; não é autorização para executar** (a autorização do Studio é o
  teste autorizado, pela pessoa, por revisão). No Agent, a autorização será um ato próprio (§5).

## 4. Condições reais do Windows nesta máquina

| Condição | Como se observa sem código nativo novo | Medido |
|---|---|---|
| Sessão do processo | `C:\Windows\System32\tasklist.exe /FI "PID eq <pid>" /FO CSV /NH` → nome e número da sessão | `"Console","1"` |
| Session 0 (serviços) | número de sessão 0 → recusar | — |
| Tela bloqueada | `LogonUI.exe` presente na MESMA sessão (`tasklist /FI "IMAGENAME eq LogonUI.exe"`) | ausente com a tela livre |
| Desktop gráfico | `GraphicsEnvironment.isHeadless()` falso e bandeja/janela disponíveis | sim |
| Suspensão | estados `S3`, hibernação (`powercfg /a`); dentro do Agent: salto do relógio de parede entre verificações | S3 disponível |
| Desconexão RDP | não há sinal confiável sem API nativa (`WTSQuerySessionInformation`); declarado como limitação | — |

`tasklist` é um executável do sistema, chamado por caminho absoluto com argumentos fixos (sem `cmd /c`), só para ler a
própria sessão. Nenhuma dependência nova (JNA etc.).

## 5. Contrato mínimo proposto (local; sem IPC Studio↔Agent, sem Platform)

- **Pacote** = pasta escolhida pela pessoa. Dois adaptadores:
  - `STUDIO_PYTHON`: `manifest.json` do Studio (layout `automation/` + `main.py`); `execution` `single`/`list`/`tabular`;
    sem segredos na V1 (pacote que declara credenciais é BLOQUEADO: `SECRETS_NOT_SUPPORTED`).
  - `LAB_GENERIC`: `nekkora-agent-lab.json` (`nekkora-agent/lab-package` 1.0.0) com o script de entrada e os hashes;
    processo genérico que NÃO importa `nekkora_rpa`.
- **Autorização local**: a pessoa APROVA o pacote no Agent (nome, tipo, origens, entradas e a impressão digital =
  SHA-256 sobre a lista de arquivos e hashes). A aprovação vale só para aquela impressão digital; arquivo mudado →
  impressão diferente → não aprovado. Abrir o Agent ou a pasta nunca inicia nada.
- **Ambiente Python**: escolhido pela pessoa (o do projeto); conferido por sonda com argumentos fixos (versão do Python
  ≥ 3.12; para `STUDIO_PYTHON`, `nekkora_rpa` na versão exigida pelo manifesto). Nada é instalado ou baixado.
- **Pedido** `LocalExecutionRequest(packageDir, fingerprint, python, input?, timeout)` — tipado; o Agent monta a linha de
  comando a partir do adaptador (lista fechada de opções), nunca de texto livre.
- **Execução**: `ExecutionId` (`ex-` + 128 bits aleatórios, imutável), pasta própria `<dados>/execucoes/<id>/` com uma
  CÓPIA conferida do pacote (o processo roda da cópia: mudança na pasta original depois da conferência não alcança a
  execução), `estado.json` e `eventos.jsonl` (só códigos, tempos e contagens).
- **Estados**: `READY → STARTING → RUNNING → {SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, OUTCOME_UNKNOWN}`; `BLOCKED`
  (nada iniciado). Transições inválidas são recusadas pelo domínio. `SUCCEEDED` só com protocolo estruturado que confirme
  (código de saída 0 sozinho → `OUTCOME_UNKNOWN`).
- **Posse de processos**: só o processo lançado e seus descendentes, conferidos por PID **e instante de início** (PID
  reutilizado não é posse); nunca processo externo por nome ou PID.
- **Uma execução ativa por usuário** (instância única + controlador): pedido concorrente recusado (`ALREADY_RUNNING`).
- **Reinício do Agent**: execuções não terminais são reconciliadas pela identidade do processo; nada é relançado; sem
  prova do fim → `OUTCOME_UNKNOWN`.
- **Privacidade**: stdout/stderr drenados e descartados (só contagem de bytes/linhas); nenhum valor, caminho de entrada
  ou HTML em eventos/diagnóstico.
- **Fora**: Studio↔Agent, Agent↔Platform, serviço Windows, Session 0, elevação, download de pacotes, segredos.
