# AGENT-LEAN-001: inventário e linha de base

Medido em 24/10/2026. Branch da missão `feat/agent-lean-001`, criada de `feat/nekkora-desktop-001` @ `a3e5598`
(código = `139a002`, B-OBS-03 aceito; só documentação depois). Windows 11, JDK 21.0.12, 31 GB de RAM.

## 1. Situação preservada da DESKTOP-001

- Aceite funcional com ressalvas físicas: **G2 e OBS-G6 pendentes; A13 (suspensão) ADIADO por decisão da titular**,
  depois de a rodada física de 24/10 ser interrompida (o PC não voltou da suspensão). Nenhuma suspensão, hibernação
  ou bloqueio físico foi executado nesta missão. `tools/homologation/fisica-g2-a13.ps1` não rodou em modo real.
- Roteiro auditado, critérios de G2/A13/OBS-G6, evidências dos B-OBS-01/02/03 e o contrato `nekkora-agent/observability`
  1.0.0: **intocados**. `ExecutionController` não foi modificado.
- Nenhum commit da DESKTOP-001 foi reescrito.

## 2. Linha de base de testes (Framework irmão em `main`/0.3.0, como na DESKTOP-001)

| Bateria | Resultado | Duração |
|---|---|---|
| Windows `./mvnw -o -B clean verify` | **192 testes, 0 falhas, 1 pulado** (`SourceFileTest.symbolicLinksAreRefused`, sem privilégio de ligação simbólica) | 38 s |
| Windows `-Pdesktop clean verify` (Robot, escalas 100/150/200%) | **215 casos: 214 + 1 pulado (o mesmo), 0 falhas** | 1 min 46 s |
| Linux (Docker `eclipse-temurin:21-jdk-noble --init`, sobre `git archive a3e5598`) | **192: 159 + 15 falhas + 18 pulados = a base conhecida** (`ShortcutTest` 13, `AgentBootstrapTest` 1, `SensitiveDataLeakTest` 1; só Windows) | |

As listas nominais de casos (Windows, desktop e Linux) estão guardadas para a comparação em `matriz-regressao.md`. As
15 falhas Linux são da base: não são tratadas como novas nem como sucesso.

## 3. Tamanhos e desempenho

| O quê | Medida |
|---|---|
| clone Git | 6,8 MB rastreados em 265 arquivos; `.git` 12 MB |
| distribuição ZIP | **35 716 578 bytes** (`d649ad90…`), 164 arquivos, 52,2 MB descomprimidos |
| abertura do executável empacotado (sem Java no PATH, dados descartáveis) | janela em **1 128 ms** (mediana de 5; 973–1 554) |
| memória ociosa (20 s, janela aberta) | conjunto de trabalho **149,1 MB**; **memória privada 350 MB** |
| memória em execução sintética (rodada E2E-005, aba Atividade aberta) | pico do conjunto de trabalho 237–243 MB |
| histórico/rotação | atividade por execução 150–270 KB (E2E-005); limites do `ActivityLog` (fila 4096 linhas / 2 MiB) inalterados |

### O que pesa no ZIP (comprimido)

| Parte | Comprimido | Observação |
|---|---|---|
| runtime Java (jlink: `java.base`, `java.desktop`, `java.prefs`, `java.logging`, `jdk.unsupported`, `jdk.accessibility`) | ~31 MB | módulos conferidos com `jdeps`; já `--strip-debug --compress=zip-6` |
| `nekkora-swing-ui` (fontes Inter, JetBrains Mono, Sora) | 1,40 MB | design system compartilhado com o Studio |
| FlatLaf | 1,02 MB | |
| `nekkora-agent.jar` | 0,37 MB | |
| documentação dentro do ZIP | `LEIA-ME`, licenças, `VERSOES.txt` | obrigatórios |

## 4. Candidatos

| Candidato | Tamanho / efeito | Consumidores | Risco | Proposta | Evidência |
|---|---|---|---|---|---|
| heap inicial padrão = 1/64 da RAM (limitado ao `-Xmx256m`), comprometido inteiro na abertura | privada 350 MB → **108 MB** com `-Xms16m`; conjunto de trabalho 149 → **123 MB**; abertura igual | todo o Agent | baixo: teto e GC iguais; o heap cresce sob demanda | **aplicar** (`tools/package-windows.ps1`) e validar sob carga | experimento numa cópia do pacote (`.cfg`) |
| `runtime/lib/jvm.lib` (biblioteca de ligação, sem uso em execução) | 1,19 MB em disco, 194 KB no ZIP (0,5%) | nenhum em execução | baixo | **não aplicar** (ganho desprezível; mais uma regra de empacotamento) | listagem do ZIP |
| DLLs opcionais do `java.desktop` (splash, JPEG, som) | < 250 KB comprimidos | possíveis (ImageIO, beep) | médio | **manter** | |
| `jdk.accessibility` (Java Access Bridge) | ~100 KB | leitores de tela | alto (acessibilidade) | **manter** | `package-windows.ps1` |
| CDS/AppCDS para abrir mais rápido | +15–20 MB no pacote | | | **não fazer**: contraria a redução de peso, e a abertura já fica em ~1,1 s | |
| evidências da AGENT-001/DESKTOP-001 no Git (5,5 MB) | não vão para o ZIP | revisões, pareceres, auditoria | alto | **manter** | |
| documentação obsoleta | | | | **nenhuma**: os documentos vigentes da DESKTOP-001 são a referência das pendências físicas | |
