# PLATFORM-AUTOMATION-LIFECYCLE-001 — laboratório do ciclo de vida (lado do Agent)

Este PR só traz ferramentas de laboratório e evidências. `src` não muda.
O relatório final e o parecer estão na API, em `docs/relatorios/2026-10-29-automation-lifecycle-relatorio.md`; o contrato, em `2026-10-29-automation-lifecycle-contrato.md`.

## Ferramentas (`tools/platform-lab/`)

- `compose.lab-ciclo.yml`: sub-rede 10.251.50.0/24 e volume `artefatos`, com `NEKKORA_ARTEFATOS_DIR` só na Platform.
- `subir.sh`: passa a aceitar `LAB_REDE=50`.
- `ciclo.py preparar <id> <zip>`: a pessoa faz tudo pelas rotas reais:
  - workspace, automação e versão DRAFT;
  - envio do ZIP e conferência pela API;
  - publicação com step-up;
  - pareamento, Worker no workspace e autorização.

  Só há duas fixtures, declaradas: a identidade da pessoa e os limites da organização.
- `ciclo.py consultar <id> <exec>`: consulta a execução na Platform.
- `jornada-ciclo.ps1`: o Agent empacotado real faz L1 a L6 com o pacote genuíno do Studio.

## Evidências (`evidencias/`)

| Arquivo | Conteúdo |
|---|---|
| `regressao-api-npa-lc.txt` | regressão completa da API: 716 testes, 0 falhas |
| `studio-lc1-verificacoes.txt` | Studio `da7bb94` pela interface: 105 verificações, 0 falhas |
| `studio-lc1-pacote-sha256.txt` | SHA-256 de cada arquivo do pacote e do ZIP enviado |
| `ciclo-preparacao.json` | identificadores, artefato, publicação, canal `0\|0\|0` antes da autorização e trilha de atos |
| `l6-migrations-sha256.txt` | migrations 001–028 aplicadas em instalação limpa |
| `l6-20261029T204415Z/` | jornada 9/9: `resultado.json`, linha do tempo, consultas da Platform (L4, L5) e capturas |

Antes de entrar no repositório, as evidências foram conferidas: não há caminhos pessoais, e-mail nem segredos. A conferência L6 da jornada também passou.

A captura `l4-fim.png` é tirada no fim da execução, antes de a entrega acabar, por isso mostra "1 execução com envio pendente". Em seguida, a jornada espera `PLATFORM_DELIVERY_COMPLETED`. Só então confere no banco 292 eventos guardados e 292 assentados, sem recusas, e consulta a Platform, que responde com histórico `COMPLETE` (`l4-consulta-platform.json`).
