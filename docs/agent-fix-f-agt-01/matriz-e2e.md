# AGENT-FIX-F-AGT-01: cenários E2E pelo Agent empacotado

**Configuração da rodada:**

- **Agent:** ZIP `cd04a7c47f772ff26e2f38635dbc2879c2844db51fd70dcea77991f860f7dbdb`, do commit `9c81ddc`, gerado por
  `tools/package-windows.ps1` (que roda de novo a bateria completa).
- **Framework:** `nekkora-rpa` 0.4.0, roda `161baa2d…`.
- **Roteiro:** `Nekkora-E2E-005/tools/agent/final-round.ps1`, que opera a interface real do Agent e roda o oráculo
  independente sobre cada saída.
- **Evidência:** `Nekkora-E2E-005`, branch `test/e2e-regressao-f-agt-01`, `evidence/final-round-fix-f-agt-01/`.

| Automação | Rodada 1 | Rodada 2 |
|---|---|---|
| AUT-01 catálogo (books.toscrape.com) | SUCCEEDED · oráculo PASS · 17,5 s | SUCCEEDED · PASS · 15,8 s |
| AUT-02 hóquei (scrapethissite.com) | SUCCEEDED · PASS · 24,9 s | SUCCEEDED · PASS · 24,8 s |
| AUT-03 fornecedores (portal local) | SUCCEEDED · PASS · 5,8 s | SUCCEEDED · PASS · 4,8 s |
| AUT-05 documentos (portal com falhas injetadas) | SUCCEEDED · PASS · 24,8 s | SUCCEEDED · PASS · 9,6 s |
| AUT-06 conciliação (sem rede) | SUCCEEDED · PASS · 0,9 s | SUCCEEDED · PASS · 0,9 s |

**10/10 SUCCEEDED, com oráculo PASS e 0 verificações falhas.**

A **AUT-04** (segredos) continua recusada na inspeção com **AGT-EXE-006**, sem aprovação nem execução registradas. A
decisão 1 da titular foi preservada.

**Sobre a diferença de tempo na AUT-05 entre as rodadas** (24,8 s contra 9,6 s): o portal da AUT-05 é iniciado uma
vez por rodada final, e a falha `lost_once` só acontece na primeira vez. Nas rodadas anteriores o padrão foi o mesmo:
25,2 s e 9,3 s em `final-round-aut06`.
