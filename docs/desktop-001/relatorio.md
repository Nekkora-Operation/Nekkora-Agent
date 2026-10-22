# NEKKORA-DESKTOP-001 — Relatório: lacunas, decisões, riscos e procedimento

Data: 22/10/2026. Estado: **implementada e homologada em Windows real, exceto G2/A13 com a titular presente**
(bloqueio real da tela e suspensão real do Windows). Tudo local: nenhum push, PR, merge, tag ou release.

## 1. Commits locais (`feat/nekkora-desktop-001`, base `d465296`)

| Commit | Conteúdo |
|---|---|
| `7d44327` | Motor de execução local supervisionada (domínio, sem interface) |
| `e60b8fa` | Janela "Execução local", estado na janela principal e diagnóstico |
| `8c57e74` | Janela principal compacta (linha + botão; ≤ 720 px) e roteiro de homologação na janela real |
| `34a8fb5` | Aprovação sem nome de pasta (achado G1, teste antes da correção) e evidência do pacote real do Studio |
| `19647b4` | Confirmação de saída com atalhos e homologação do pacote (queda, adoção, saída) |
| (este)   | Documentação final: arquitetura, matriz, relatório |

Ver diff: `git diff d465296..feat/nekkora-desktop-001 --stat`.

## 2. Comportamento NÃO implementado (explícito)

- Canal Studio↔Agent (IPC, HTTP, pareamento, gatilho): é a STUDIO-AGENT-001.
- Agent↔Platform, telemetria remota, licença, download de pacotes.
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
| `-Pdesktop` completo no HEAD final | Rodado até `34a8fb5`; `ExecutionDesktopTest` 2/2 no `19647b4` | Rodar com a máquina livre (`Robot`), junto do §6 |
| Linux | 15 falhas pré-existentes idênticas à base (caminhos do Windows nos testes da AGENT-001) | Fora do escopo; não mascaradas |
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

## 5. Riscos

| Risco | Mitigação / estado |
|---|---|
| Processo da automação abre filhos que se desligam da árvore (ex.: Chrome reaproveitado) | `--channel chrome` com perfil do Playwright: o navegador nasce como descendente do Python (driver do Playwright) e entra na árvore possuída; processo que saia da árvore NÃO é encerrado (preferimos deixá-lo vivo a encerrar algo que não é nosso) |
| `tasklist` lento ou ausente | Sonda com prazo; falha = sessão indisponível → `BLOCKED` (nunca presume disponível) |
| Suspensão curta (< 20 s) não detectada | Aceito: abaixo do limiar, o processo segue e o resultado vem do protocolo |
| Relógio ajustado manualmente | Pode registrar uma suspensão falsa; efeito só no registro (desfecho continua pelo protocolo) |
| Pacotes antigos do Studio (manifesto < 1.4.0) | Recusados (`AGT-EXE-003`); gerar de novo no Studio |
| Chrome ausente na máquina | Falha do próprio framework (`INIT_FAILED` → `FAILED`); pré-requisito declarado |

## 6. Procedimento de homologação pendente (titular presente, máquina livre)

Pré-requisitos: Agent compilado (`./mvnw -o -DskipTests package` + classpath de teste) e o Python do framework
(`..\Nekkora-RPA-Framework\.venv\Scripts\python.exe`). Sem jogos/chamadas abertos; não mexer no mouse durante o
`Robot`.

1. **Sessão durante a execução (G2)** — `java -cp <classpath de teste> com.nekkora.agent.ui.DesktopHomologation sessao
   <evidências> <pacote lab_efeito> <python> <saída>`. Quando o roteiro pedir: Win+L, ~10 s, desbloquear; depois
   Iniciar → Suspender, ≥ 1 min, retomar. Esperado: `SESSION_LOCKED`/`SESSION_UNLOCKED`, `SUSPENSION_DETECTED`, estado
   terminal sem relançamento, `efeitos.txt` com UMA linha, um único `LIFE_START`.
2. **A13 com o pacote** — abrir o `.exe` do ZIP; `tools/homologation/suspend-check.ps1 -Fase antes`; suspender ≥ 1 min;
   retomar; `-Fase depois`. Esperado: mesmos PIDs, um ícone, sem `LIFE_START` novo.
3. **Regressão desktop no HEAD** — `./mvnw -o -Pdesktop clean verify`.

Homologações já feitas (reproduzíveis): G1 `DesktopHomologation studio …` (`evidencias/G1-studio`), pacote
`tools/homologation/desktop-001-pacote.ps1 -Python … -TestClasspath …` (`evidencias/D-P1-pacote`), regressão
`homologate-package.ps1` (`evidencias/regressao-pacote-agent001`).

## 7. Pré-requisitos reais de uma máquina

Windows 10/11 com sessão interativa; o pacote do Agent (runtime próprio, sem Java no PATH); para pacotes do Studio, o
Python do ambiente do projeto com `nekkora-rpa` na versão do manifesto (o Agent não usa Python global por conta própria)
e o Google Chrome instalado. Pacotes de laboratório: qualquer Python ≥ 3.12.
