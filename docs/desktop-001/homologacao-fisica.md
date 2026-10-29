# NEKKORA-DESKTOP-001 — Homologação física G2 / A13 / OBS-G6

Estado: **PENDING_PHYSICAL_HOMOLOGATION**. O roteiro está pronto e ensaiado (sem bloqueio/suspensão); a rodada real
exige a titular presente. O roteiro **nunca** bloqueia, suspende ou desbloqueia a máquina — só a pessoa faz isso,
quando ele pedir.

## 1. O que é verificado, ao mesmo tempo, no MESMO bloqueio e na MESMA suspensão

| Id | Quem | Automação sintética (dados fictícios, sem rede) | Esperado |
|---|---|---|---|
| P | Agent **empacotado** (`.exe`, sem Java no PATH) — o **único** com ícone na bandeja | processo no formato do Studio publicando eventos REAIS do `nekkora_rpa` (`obs_studio_main.py`, 120 transações, ~6 min acordada), ADOTADO pelo Agent | `SUCCEEDED` pelo protocolo (`summary.json`); `ENGINE_STARTED` uma vez; sequência contínua; Framework = linhas originais; `SESSION_LOCKED/UNLOCKED`, `SUSPENSION_DETECTED` na aba |
| S1 | janela real do Agent (processo Java próprio, **sem** ícone) | `lab_sessao_sucesso.py`: 1 efeito não idempotente, ~6 min, publica `SUCCEEDED` | `SUCCEEDED` (o protocolo prova), efeito 1× |
| S2 | idem | `lab_sessao_sem_protocolo.py`: 1 efeito, ~6 min, sai 0 **sem** resultado | `OUTCOME_UNKNOWN` com `AGT-EXE-026` (suspensão sem prova: nada presumido), efeito 1× |
| S3 | idem | `lab_sessao_falha.py`: 1 efeito, ~6 min, publica `FAILED`, sai 1 | `FAILED`, efeito 1× |

S1–S3 são lançadas pelo **botão Iniciar** (o mesmo supervisor do produto; só os seletores de arquivo são substituídos
pelas escolhas diretas, como em toda a homologação de janela). P é adotada porque o executável não tem como receber as
escolhas sem os seletores de arquivo; a supervisão (bloqueio, suspensão, desfecho pelo protocolo) é a mesma.

Para cada uma: tela bloqueada e desbloqueada registradas; suspensão detectada; **nenhuma execução duplicada** (efeito 1×,
uma única execução no registro, `ENGINE_STARTED` 1×, nenhum processo restante); classificação correta; um único
`LIFE_START` (o Agent continuou o mesmo processo); linha do tempo com sequência contínua, desfecho do supervisor e
**completa** (OBS-G6, sem `EVENT_GAP`/`EVENT_INVALID`/origem interrompida).

A13 no empacotado: **mesmos PIDs** (lançador + JVM) antes e depois, **um único ícone** na bandeja depois da retomada,
nenhum `LIFE_START` novo e uma **segunda abertura** depois da retomada mostra "O Nekkora Agent já está aberto" e sai —
continuam os mesmos 2 processos e 1 ícone.

## 2. Instantâneos antes / durante / depois

- `S-<cenário>-estados.jsonl` (janela real): `antes`, `durante-bloqueio` (gravado com a tela bloqueada; a captura
  `S-<cenário>-2-durante-bloqueio.png` é pintada pela própria janela, sem câmera de tela), `depois-do-desbloqueio`,
  `depois-da-retomada`, `fim` — estado, código, bloqueios, suspensões, processo vivo, sessão bloqueada, efeitos,
  eventos de atividade, completude, execuções no Agent.
- `P-estados.jsonl` (empacotado): `antes`, `execucoes-iniciadas`, `durante-bloqueio`, `depois-do-desbloqueio`,
  `logo-apos-retomar`, `depois`, `depois-da-segunda-abertura` — estado, PIDs do Agent, ícones na bandeja (não medidos
  com a tela bloqueada), `LIFE_START`, eventos (total e do Framework), processos da execução. Capturas `P-1-antes`,
  `P-2-depois`, `P-3-segunda-abertura-recusada` (com a tela desbloqueada).

Só estado, códigos e contagens. Antes de terminar, o roteiro substitui por `<local>`/`<caminho>` qualquer usuário,
nome de máquina ou caminho local nos textos e reprova se sobrar algum. O material bruto (dados do Agent, saídas) fica
em `<Out>\bruto`, fora do repositório.

## 3. Como rodar (titular presente)

Antes: ler [`auditoria-roteiro-fisico.md`](auditoria-roteiro-fisico.md), que lista cada efeito do roteiro na máquina e traz o
SHA-256 dos arquivos executados.

Preparação (uma vez, na raiz do repositório):

```powershell
.\tools\package-windows.ps1                     # clean verify + ZIP do Agent (target\dist)
.\mvnw -o -q test-compile dependency:build-classpath "-Dmdep.outputFile=target\cp.txt"
$cp = "target\test-classes;target\classes;" + (Get-Content target\cp.txt)
$py = "..\Nekkora-RPA-Framework\.venv\Scripts\python.exe"
```

Com a máquina livre e "Não incomodar" ligado:

```powershell
.\tools\homologation\fisica-g2-a13.ps1 -Python $py -TestClasspath $cp
```

1. O roteiro confere as pré-condições, abre o Agent empacotado (adota P) e pede **Enter**.
2. Depois do Enter, S1–S3 começam e o roteiro pede, nesta ordem: **Windows+L**, ~10 s, **desbloquear**; **Iniciar →
   Ligar/Desligar → Suspender**, **≥ 1 min**, **retomar e desbloquear**. Tudo isso dentro de ~5 min de máquina acordada
   (as automações esperam ~6 min acordadas; a suspensão não conta).
3. Não usar o computador até o fim (~8–10 min no total): o roteiro usa teclas e o foco das janelas.
4. Resultado: `<Out>\evidencias\resultado.txt` + estados e capturas. Copiar `evidencias` para
   `docs/desktop-001/evidencias/G2-A13-fisica` para a revisão.

Ensaio sem a pessoa (confere a automação; bloqueio/suspensão ficam "NÃO EXERCIDOS"): o mesmo comando com `-Ensaio`.

## 4. Resultados

### Ensaio sem a pessoa (24/10, Agent do commit `139a002` — OBS-PERSISTENCE-HARDENING com as correções B-OBS-01/02/03 —, ZIP SHA-256 `53f5318b6914dac286fe724923434104c54eeb2eabdbaf7447e695897ce6e5f2`) — `evidencias/G2-A13-ensaio`

Refeito depois da [auditoria do roteiro](auditoria-roteiro-fisico.md), com o comando exato da §3 (`$py` relativo).
Refeito no build com a correção da gravação do histórico (`5c3fd6e`) e de novo no build final depois da revisão de
fontes (`05f7a48`) e de novo depois da correção B-OBS-03 (`139a002`, 24/10): 25/25 nas três vezes, nenhum aviso de
gravação no `agent.log`; capturas `P-*` revisadas (só janelas do Agent). Os dois ensaios intermediários da auditoria reprovaram por defeitos do roteiro (defeitos 3 e 4 da auditoria) e
não são evidência de aceite.

**25/25 verificações, 0 falhas; bloqueio e suspensão NÃO EXERCIDOS** (não é a homologação física; só prova que o
roteiro funciona de ponta a ponta).

| | Classificação | Efeito | Linha do tempo | Outros |
|---|---|---|---|---|
| P (empacotado, adotada) | `SUCCEEDED` pelo protocolo (`AGT-EXE-025` como observação de adoção) | — | 975 eventos, 968 do Framework = 968 linhas originais, completa | `ENGINE_STARTED` 1×, nenhum processo novo da execução |
| S1 sucesso | `SUCCEEDED` | 1× | completa, sequência 1..5 | 1 execução, 1 `LIFE_START`, janela aberta do início ao fim |
| S2 sem_protocolo | `OUTCOME_UNKNOWN` (`AGT-EXE-021` sem suspensão; com suspensão real o esperado é `AGT-EXE-026`) | 1× | completa | idem |
| S3 falha | `FAILED` (`AGT-EXE-022`) | 1× | completa | idem |

A13 no empacotado (sem suspensão no ensaio): mesmos PIDs antes/depois, 1 ícone, 1 `LIFE_START`; a segunda abertura
mostrou "O Nekkora Agent já está aberto" e saiu com código 3 (`P-3-segunda-abertura-recusada.png`). Sanitização: nenhum
usuário, máquina ou caminho nos textos. No instantâneo `antes` dos cenários, `eventos_atividade` é 0 porque é tirado
antes da primeira atualização da aba (400 ms).

Defeitos do roteiro encontrados pelos ensaios e corrigidos: ver `relatorio.md` §8.1 e `auditoria-roteiro-fisico.md` §4.

### Rodada física com a titular

Pendente — `PENDING_PHYSICAL_HOMOLOGATION`.
