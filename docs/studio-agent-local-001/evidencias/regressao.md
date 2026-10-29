# Regressão da STUDIO-AGENT-LOCAL-001

Os testes com processo usam `NEKKORA_TEST_PYTHON`, apontando para uma venv com `nekkora_rpa` **0.3.0**: a instalada
pelo Studio na compat-001. A `.venv` do repositório do Framework está em 0.4.0 (branch F-FW) e faria o
`ObservabilityProcessTest` recusar o Python (AGT-EXE-009). Isso é ambiente, não defeito. A primeira bateria da missão
mostrou esse efeito e foi repetida com a venv certa.

| Commit | Ambiente | Casos | Falhas | Erros | Pulados | Observação |
|---|---|---|---|---|---|---|
| `f77e00e` (base) | Windows / desktop / Linux | 203 / 227 / 203 | 0 / 0 / 15 | 0 | 1 / 1 / 27 | relatório da F-AGT-01 |
| `b2a22b2` A01 | Windows | 211 | 0 | 0 | 1 | |
| `f8a0117` formato | Windows | 215 | 0 | 0 | 1 | |
| `b7cea25` A02 | Windows | 223 | 0 | 0 | 1 | uma asserção da F-AGT-01 mudou de propósito (relatório §3) |
| `aecc470` A03 | Windows | 228 | 0 | 0 | 1 | |
| `bf843fb` S0 | Windows | 235 | 0 | 0 | 1 | antes, 1 falha real (`cancelamentoReal`), que revelou o F-FW-04 e mudou o desenho |
| `645e7c9` | Windows / desktop | 240 / 263 | 0 / 0 | 0 | 1 / 1 | |
| `645e7c9` | Linux Docker | 240 | 15 | **1** | 35 | o erro era o teste de bloqueio do Windows rodando no Linux; corrigido no `f0ecd83` |
| `f0ecd83` | Linux Docker / empacotamento | 240 / verde | 15 / 0 | 0 | 36 / — | ZIP `5fa0354e…` |
| **`afe92f9` (fechamento)** | **Windows** | **245** | **0** | **0** | **1** | `SourceFileTest.symbolicLinksAreRefused`, o pulado de sempre. O ISO-04 conferiu a imagem final (8 módulos) |
| **`afe92f9`** | **Desktop** (janela real, Robot) | **268** | **0** | **0** | **1** | |
| **`afe92f9`** | **Linux** (`eclipse-temurin:21-jdk-noble`, `git archive`, sem Python) | **245** | **15** | **0** | **36** | **as mesmas 15 herdadas**: `ShortcutTest` 13, `AgentBootstrapTest` 1 e `SensitiveDataLeakTest` 1, todas dependentes do Windows. Nenhuma nova |

**Repetições.**
- `CancellationTest`: 5 rodadas seguidas, 6/6 cada.
- `CancellationTest` com `PartialProgressTest`: 3 rodadas seguidas, todas verdes, depois da correção do F-FW-04.
