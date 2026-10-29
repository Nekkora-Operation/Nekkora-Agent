# NEKKORA-DESKTOP-001 — Matriz de homologação G0–G4

Data: 22/10/2026. Branch local `feat/nekkora-desktop-001` (de `d465296`, AGENT-001). Código homologado: `19647b4`.
Ambiente: Windows 11 Pro 10.0.26200, sessão interativa "Console" 1, tela 1920×1080; Java 21.0.12.

**Tipos:** **Auto** = `./mvnw -o clean verify` (com Python real quando indicado); **Desktop** = `-Pdesktop` (janela
real, `Robot`); **Real** = roteiro na janela real do Agent (`DesktopHomologation`); **Pacote** = `.exe` empacotado com
PATH sem Java; **Simulado** = dublê; **Pendente** = não exercitado.

## Suítes

| Suíte | Commit | Executados | Falhas | Pulados |
|---|---|---|---|---|
| Windows `./mvnw -o clean verify` | `19647b4` | 129 | 0 | 0 |
| Windows `-Pdesktop clean verify` (+ JVMs de escala 150/200%) | `8c57e74`…`34a8fb5` | 145 + escalas | 0 | 0 |
| Windows `ExecutionDesktopTest` (após a confirmação de saída) | `19647b4` | 2 | 0 | 0 |
| Linux, contêiner `eclipse-temurin:21-jdk-noble` | `19647b4` | 129 | 15 | 11 |
| Linux, linha de base AGENT-001 | `d465296` | 108 | 15 | 2 |

As 15 falhas Linux são idênticas (mesmos testes e mensagens) na base e no fim: `ShortcutTest`, `AgentBootstrapTest`,
`SensitiveDataLeakTest` supõem caminhos do Windows. Nenhuma regressão. No Linux pulam corretamente os testes que exigem
Windows (`ExecutionControllerTest` 8, `LaunchTest` 1). **O Linux não substitui a prova na sessão Windows.**

## G0 — Linha de base e contrato

| Cenário | Resultado | Tipo | Evidência |
|---|---|---|---|
| Auditoria AGENT-001, A13, lançamento do Studio, condições do Windows | **Aprovado** | Manual | `preflight.md` |
| Transições válidas/inválidas; terminal não sai; sem `SUCCEEDED` artificial | **Aprovado** | Auto | `ExecutionDomainTest.transicoes` |
| `ExecutionId` imutável, 20 mil sem colisão, formato inválido recusado | **Aprovado** | Auto | `ExecutionDomainTest.identificadores` |
| Leitor JSON (chave repetida, profundidade, tamanho) | **Aprovado** | Auto | `ExecutionDomainTest.json` |

## G1 — Execução básica

| Cenário | Resultado | Tipo | Evidência |
|---|---|---|---|
| Pacote real do Studio (Wikipédia, lista, 2 artigos), sem Studio aberto e sem Platform | **Aprovado** 9/9 | Real | `evidencias/G1-studio` (`SUCCEEDED` por `summary.json` COMPLETED + planilha COMMITTED) |
| Processo genérico sem Framework, mesmo supervisor | **Aprovado** | Auto (Python real) + Pacote | `ExecutionControllerTest.sucesso`, `.falhaESemProtocolo`, `.concorrenciaECancelamento`, `evidencias/D-P1-pacote` |
| Sucesso / falha com código / falha informada / sem protocolo | **Aprovado** | Auto | `ExecutionControllerTest.falhaESemProtocolo`, `LaunchTest.classificacao` |
| Falha antes de iniciar: não aprovado, pacote mudado, Python inexistente, saída inválida | **Aprovado** | Auto | `ExecutionControllerTest.bloqueios` |
| Prazo esgotado → `TIMED_OUT`, árvore encerrada | **Aprovado** | Auto | `ExecutionControllerTest.prazo` |
| Cancelamento (janela real) | **Aprovado** | Desktop | `ExecutionDesktopTest.janelaDeExecucao` |
| Integridade alterada; caminho que sobe de pasta; segredos; versão antiga; opção desconhecida | **Aprovado** | Auto | `ExecutionDomainTest.pacote*` |
| Comando: só opções declaradas, argumentos separados, sem shell | **Aprovado** | Auto | `LaunchTest.linhaDeComandoStudio`, `.resumo`, `.sonda` |

## G2 — Windows desktop real

| Cenário | Resultado | Tipo | Evidência |
|---|---|---|---|
| Sessão interativa conferida; Session 0/sem desktop/tela bloqueada → `BLOCKED` com motivo | **Aprovado** (bloqueio real: simulado) | Auto + Simulado | `ExecutionControllerTest.bloqueios` (sonda substituída); sonda real lida na sessão 1 |
| Bloqueio/desbloqueio durante execução (eventos `SESSION_LOCKED/UNLOCKED`) | **Simulado — real pendente** | Auto + Simulado | `ExecutionControllerTest.bloqueioESuspensao` (sonda e relógio substituídos); roteiro real `DesktopHomologation sessao` |
| Suspensão/retomada (A13) durante execução e com o Agent empacotado | **Pendente** | — | Exige a titular presente: `DesktopHomologation sessao` + `tools/homologation/suspend-check.ps1` |
| Desconexão RDP | **Não implementado** | — | Sem sinal confiável sem API nativa (limitação declarada) |
| Ocultar/restaurar, janela fechada não cancela | **Aprovado** | Desktop | `ExecutionDesktopTest.janelaDeExecucao` |
| Escalas 100/150/200% com a janela de execução | **Aprovado** | Desktop | `ScaleEvidenceTest` |
| Segunda instância | **Aprovado** (regressão) | Pacote | `evidencias/regressao-pacote-agent001` (A05) |

## G3 — Supervisão e segurança

| Cenário | Resultado | Tipo | Evidência |
|---|---|---|---|
| Uma execução por usuário; concorrente recusado (`AGT-EXE-014`) | **Aprovado** | Auto | `ExecutionControllerTest.concorrenciaECancelamento` |
| Cancelar encerra a árvore possuída (filho incluído) | **Aprovado** | Auto + Pacote | `ExecutionControllerTest.concorrenciaECancelamento`, `D-P1-pacote` |
| PID reutilizado / outro executável não é posse; externo do mesmo executável intacto | **Aprovado** | Auto | `ProcessOwnerTest` |
| Queda do Agent: processo vivo adotado e cancelável; morto → `OUTCOME_UNKNOWN`; sem relançamento | **Aprovado** | Auto + Pacote | `ExecutionControllerTest.reinicio`, `D-P1-pacote` 13/13 |
| Sair com execução ativa: confirmação, Voltar padrão, Encerrar e sair | **Aprovado** | Desktop + Pacote | `ExecutionDesktopTest.sairComExecucao`, `D-P1-pacote` |
| Saída volumosa (~33 MB) sem travar; só contagem | **Aprovado** | Auto | `ExecutionControllerTest.volume` |
| Sentinelas ausentes de logs, eventos, aprovações e diagnóstico | **Aprovado** | Auto + Desktop | `app/ExecutionLeakTest`, `ExecutionControllerTest.sucesso`, `ExecutionDomainTest.aprovacao` |
| Efeito não idempotente com desfecho incerto: sem nova tentativa | **Aprovado** (queda do Agent; suspensão real pendente) | Auto | `ExecutionControllerTest.reinicio` (lab_efeito morto com o Agent: `OUTCOME_UNKNOWN`, uma única linha em `efeitos.txt`, nada relançado) |
| Achado G1: nome da pasta do pacote ia para `aprovacoes.json` | **Corrigido** com teste antes | Auto | `34a8fb5`, `ExecutionDomainTest.aprovacao` |

## G4 — Regressão

| Cenário | Resultado | Tipo | Evidência |
|---|---|---|---|
| AGENT-001: bandeja, configuração, instância única, diagnóstico, distribuição | **Aprovado** | Auto + Desktop + Pacote | Suítes acima; `regressao-pacote-agent001/resultados.md` (A02–A10, A14; A13 medido) |
| Instalação isolada sem checkout do Studio nem Java no PATH | **Aprovado** | Pacote | `D-P1-pacote` (ZIP com SHA-256 conferido) |
| Pré-requisito real: Python do projeto (o do ambiente do Studio) e Chrome para `--channel chrome` | Registrado | — | `relatorio.md` |
