# AGENT-FIX-F-AGT-01: matriz de regressão

As listas de casos foram comparadas caso a caso com as da rodada anterior (`1507344`, AGENT-PACKAGE-ORIGIN-001). Em
todos os ambientes, **os casos anteriores continuam idênticos**: mesmos nomes e mesmo resultado, e nenhum foi
removido, pulado a mais ou afrouxado. Só entram casos novos.

| Bateria | Antes (`1507344`) | Depois (`9c81ddc`) | Diferença |
|---|---|---|---|
| Windows `mvnw -o clean verify` | 193 casos: 192 passam, 1 pulado | 203 casos: 202 passam, 1 pulado | +10 novos, todos passam |
| Desktop `mvnw -o -Pdesktop verify` (janela real, Robot) | 216 casos, 1 pulado | 227 casos, 1 pulado | +10 novos e +1 de tela (`registroNaoGravadoAvisado`), todos passam |
| Linux (Docker `eclipse-temurin:21-jdk-noble`, sem Python) | 193 casos: 160 passam, 15 falham, 18 pulados | 203 casos: 161 passam, 15 falham, 27 pulados | **As mesmas 15 falhas herdadas do ambiente Linux.** Dos novos: 9 pulados (exigem Windows ou Python) e `quedaNoMeioDaGravacao` passa. |
| Repetição dos 10 testes novos não visuais | — | 5 rodadas: 5/5 verdes | — |

Para os testes Python do Agent (`ObservabilityProcessTest`), o checkout irmão do Framework estava em `main` (0.3.0),
como nas rodadas anteriores.

## Cenários E2E pelo Agent empacotado

As cinco automações executáveis pelo Agent foram rodadas com o ZIP desta correção, duas rodadas cada, com o oráculo
independente. A AUT-04, que exige segredos, deve continuar recusada na inspeção (AGT-EXE-006). Os resultados estão
em `matriz-e2e.md`, nesta pasta, e no repositório E2E (`evidence/final-round-fix-f-agt-01`).
