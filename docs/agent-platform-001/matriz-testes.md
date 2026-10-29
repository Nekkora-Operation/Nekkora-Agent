# AGENT-PLATFORM-001 — matriz de testes do candidato

Código do candidato: `e4e5617` (os commits seguintes, até `6df0c01` e depois, só mudam `tools/` e `docs/`; nenhum arquivo de
`src/`).

| Suíte | Commit | Resultado | Base anterior (STUDIO-AGENT-LOCAL-001) |
|---|---|---|---|
| Windows `mvnw clean verify` (no empacotamento) | `e4e5617` | **278** testes, 0 falhas, 0 erros, 3 ignorados | 245 |
| Desktop `mvnw -Pdesktop clean verify` (janelas, bandeja, Robot; escalas 150/200 em JVM própria) | `6df0c01` (src = `e4e5617`) | **302** + 2 escalas, 0 falhas, 0 erros, 3 ignorados — BUILD SUCCESS | 268 |
| Linux Docker `eclipse-temurin:21-jdk-noble` (`git archive`, Maven offline, sem Python) | `6df0c01` (src = `e4e5617`) | 278 testes, **as mesmas 15 falhas herdadas** (ShortcutTest 13, AgentBootstrapTest 1, SensitiveDataLeakTest 1, dependentes do Windows), 0 erros, 38 ignorados (inclui DPAPI e processos) | 245 com as mesmas 15 |
| Vetor real da impressão (`NEKKORA_REAL_PACKAGE` = pacote do Studio `da7bb94`) | `d3e0f8a` | 3/3, 0 ignorados (`5664f1a5…`) | — |
| Prova vermelho/verde | `d3e0f8a` | M1 (repetição com segredo novo), M2 (ACK sem conferência), M3 (lacuna sempre perda), M4 (401 sem parar): 4/4 vermelhos; verde depois de reverter | — |
| Jornada integrada real | `e4e5617` empacotado | 21/22 + retomada 4/4 (ver `relatorio.md`) | — |

Os 3 ignorados no Windows e no desktop:

- `NetworkIsolationTest` ISO-04, porque a imagem empacotada ainda não está montada na hora do teste;
- `PackageFingerprintVectorTest` V2, por falta da variável `NEKKORA_REAL_PACKAGE`;
- um caso de `SourceFileTest` que já era ignorado antes.

## Cobertura dos controles pedidos (missão, sec. 10)

| Controle | Teste |
|---|---|
| Resposta de pareamento perdida e repetição | P1 (+ M1) |
| Repetição depois de rotação ou revogação | P3 |
| Reinício durante a rotação | P4, P5 |
| Rede indisponível com a automação executando | D2; jornada J4 |
| Lote confirmado no servidor e ACK perdido | D3; jornada J5 |
| Reinício antes e depois de persistir o ACK | D4; jornada J6 |
| Mesmo evento reenviado sem duplicação | D3, D4; jornada J5/J7 |
| Conflito de conteúdo e payload inválido | D5 |
| Linha incompleta, gravação pendente e perda definitiva | D6, D13 (+ M3) |
| Cursores ou respostas inválidos | D7 (+ M2) |
| Credencial revogada e isolamento entre organizações | D8, D9 (+ M4); jornada J8 |
| Relatório atrasado, resultado desconhecido e contradição | D10 |
| Limites de armazenamento, lotes e retentativas | D11, R1, Q1, Q2, D12 |
| Ausência de segredos em logs e evidências | P8; jornada J9 |
| TLS normal, sem redirecionamento, limite de resposta | H1–H3 |
| Rede só no transporte (contraprova) | `ArchitectureTest.networkIsRefusedOutsideTheTransport`, ISO-02 |
| Vetores da impressão do pacote | V1, V2 |
| Pareamento pela tela (botão e teclado, foco no código) | `PlatformDesktopTest` |
