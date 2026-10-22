# NEKKORA-DESKTOP-001 — Arquitetura da execução local supervisionada

Pacote `com.nekkora.agent.exec` (domínio, sem Swing) + `ui/ExecutionDialog` e uma linha/botão em `ui/MainWindow`
(só observam o controlador). Nenhuma dependência nova; nenhum código nativo; nada no Studio, Platform, Extension ou
Framework foi alterado.

## 1. Componentes

| Componente | Papel |
|---|---|
| `ExecutionState` | Estados e transições fechadas (§3). Terminal não sai; `SUCCEEDED` só a partir de `RUNNING`. |
| `ExecutionId` | `ex-` + 32 hex (128 bits de `SecureRandom`); formato validado em toda leitura. |
| `ExecutionPackage` | Inspeção de uma pasta escolhida pela pessoa: adaptador `STUDIO_PYTHON` (`manifest.json` 1.4.0+) ou `LAB_GENERIC` (`nekkora-agent-lab.json`, `nekkora-agent/lab-package` 1.0.0). Confere cada arquivo pelo SHA-256 do texto UTF-8 com LF; impressão digital = SHA-256 da lista ordenada de arquivos e hashes. Limites: 500 arquivos, 50 MB, sem ligação simbólica, sem caminho fora da pasta. |
| `ApprovalStore` | `<dados>/execucao/aprovacoes.json`: impressão digital, tipo, rótulo `pacote <resumo>` e data. Nunca nome de pasta ou caminho. Ilegível = nenhuma aprovação. |
| `PythonRuntime` | Sonda com argumentos fixos (script gravado em arquivo, saída em arquivo, prazo 25 s): Python ≥ 3.12 e, para o Studio, `nekkora_rpa` na versão EXATA do manifesto. Ambiente mínimo (`ENV_KEEP`); nada instalado nem baixado. |
| `SessionProbe` | Windows: `%SystemRoot%\System32\tasklist.exe` (caminho absoluto, argumentos fixos, sem shell) para o número da sessão do próprio PID e `LogonUI.exe` nessa sessão (= tela bloqueada). JVM sem desktop ou Session 0 → indisponível. Fora do Windows → `UNSUPPORTED_OS`. |
| `Launch` | Monta o comando a partir do adaptador (lista fechada), cria a CÓPIA conferida do pacote em `<dados>/execucoes/<id>/pacote` e lê o protocolo de resultado. |
| `ProcessOwner` | Posse = PID + instante de início + SHA-256 do caminho do executável (o caminho não é gravado). Encerra descendentes iniciados depois da raiz, depois a raiz. |
| `ExecutionRecord` / `RunStore` | `estado.json` (`nekkora-agent/execution` 1.0.0) e `eventos.jsonl` (≤ 500 eventos, só códigos, tempos e contagens); retenção de 50 execuções; `saida.ref` local (caminho da saída, só para reconciliar; nunca no diagnóstico). |
| `ExecutionController` | Uma execução ativa; conferências no instante de iniciar; drenagem de stdout/stderr só para contagem; monitor (500 ms; sessão a cada 5 s; suspensão por salto de relógio > 20 s); prazo (padrão 60 min); cancelamento; `reconcile()` na abertura; `close()` cancela a ativa. |
| `ExecutionSetup` | Escolhas da janela em memória e lista do que falta para iniciar. |

## 2. Comandos (argumentos separados, `ProcessBuilder`, sem `cmd /c`)

- Studio: `<python do projeto> -E -s -B -X utf8 main.py` + opções DECLARADAS em `cli.options` (só `input`,
  `spreadsheet`, `output`; nomes conferidos por padrão) + `--channel chrome`. Diretório de trabalho = cópia do pacote.
  Planilha da lista em `<saída>/<id>/planilha.xlsx`; saída em `<saída>/<id>`.
- Laboratório: `<python> -E -s -B <entry> --output <saída>/<id>`. O script de laboratório não importa `nekkora_rpa`.
- Ambiente: só as variáveis de `ENV_KEEP` (sem `PYTHONPATH`, chaves ou tokens).

## 3. Ciclo de vida

```
READY ──► STARTING ──► RUNNING ──► SUCCEEDED | FAILED | CANCELLED | TIMED_OUT | OUTCOME_UNKNOWN
  │          │
  │          └──► BLOCKED | FAILED | CANCELLED | OUTCOME_UNKNOWN
  └──► BLOCKED | CANCELLED
```

- `BLOCKED`: nada foi iniciado (não aprovado, pacote mudado, sessão bloqueada/indisponível, Python inválido, saída inválida…).
- `SUCCEEDED` exige protocolo: Studio `execucao/summary.json` com `COMPLETED` (e planilha `COMMITTED` em lista) e
  código 0; laboratório `agent-result.json` (`nekkora-agent/lab-result`) com `SUCCEEDED` e código 0.
- Sem protocolo: código 0 → `OUTCOME_UNKNOWN` (`AGT-EXE-021`); código ≠ 0 → `FAILED` (`AGT-EXE-020`).
- Protocolo com outro status → `FAILED` (`AGT-EXE-022`). Só status e contagens são lidos do resumo.
- Prazo → árvore encerrada, `TIMED_OUT` (`AGT-EXE-023`). Cancelar → `CANCELLED` (`AGT-EXE-024`).
- Suspensão detectada e processo perdido sem protocolo → `OUTCOME_UNKNOWN` (`AGT-EXE-026`).
- Agent reiniciado: processo vivo com a mesma posse → ADOTADO (continua `RUNNING`, cancelável); morto → `OUTCOME_UNKNOWN`
  (`AGT-EXE-025`); registro sem dados para conferir → `BLOCKED`/`OUTCOME_UNKNOWN`. Nada é relançado.

## 4. Invariantes de segurança

1. Nada começa sem ação humana: abrir o Agent, escolher a pasta ou reabrir após queda nunca inicia execução.
2. Autorização = aprovação local da impressão digital; arquivo mudado → outra impressão → `AGT-EXE-007`/`004`.
   O manifesto do Studio descreve a geração; não autoriza.
3. A execução roda de uma cópia conferida; mudança na pasta original depois da conferência não alcança o processo.
4. Comando montado por adaptador, nunca por texto livre; executável validado pela sonda.
5. Sem segredos na V1: pacote com `steps[].credential` → `AGT-EXE-006`.
6. Sessão interativa obrigatória; Session 0, JVM sem desktop ou tela bloqueada → `BLOCKED` antes de iniciar.
7. Encerra só o que possui (PID + início + executável); processo externo do mesmo executável fica intacto.
8. Uma execução ativa por usuário (instância única da AGENT-001 + controlador): concorrente → `AGT-EXE-014`.
9. stdout/stderr descartados (só bytes contados); eventos, logs e diagnóstico só com códigos, tempos e contagens.
10. Desfecho incerto nunca vira sucesso nem nova tentativa.

## 5. Interface

- Janela principal: linha "Execução local" em "Esta máquina" ("Nenhuma nesta sessão" / "Em andamento · N s · PID n") e
  botão "Execução local…". Largura ≤ 720 px mantida.
- `ExecutionDialog`: cartão de execução (estado, tempo, PID, código), Preparação (pacote, aprovação, Python, entrada,
  saída, o que falta) e Iniciar/Cancelar; mnemônicos. Fechar a janela não cancela.
- Sair com execução ativa: "Sair do Nekkora Agent?" com "Voltar" padrão (Alt+V) e "Encerrar execução e sair" (Alt+E).
- "Execução pela Platform: Indisponível nesta versão" continua (o Agent não recebe execuções remotas).

## 6. Fronteira com a próxima missão (STUDIO-AGENT-001)

O ponto de entrada é o mesmo que a janela usa: `ExecutionController.start(pacote, Python, entrada, saída)` sobre um
pacote aprovado. O canal Studio→Agent deverá: autenticar a origem local, entregar só a REFERÊNCIA a um pacote (a
aprovação continua no Agent), observar estado/eventos pelo controlador e pedir cancelamento. Não há nesta missão IPC,
HTTP, pareamento nem gatilho; nenhum desses pontos foi antecipado.
