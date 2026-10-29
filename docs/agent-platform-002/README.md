# AGENT-PLATFORM-002 — canal autenticado Agent → Platform, revisão por fase (PA-01 a PA-05)

Esta missão pede, em cinco fases, o canal que a AGENT-PLATFORM-001 já implementou e homologou (PRs #1 e #2 do Agent, sem
merge). Ela não refaz esse trabalho. Ela confere cada fase contra o contrato, fecha as lacunas encontradas e entrega a
revisão separada por fase: diff, testes, evidências, riscos e regressão.

## Versões de autoridade (conferidas em 29/10/2026)

| Item | Valor |
|---|---|
| Contrato | `Nekkora-Platform-API` `7d30c2437927e33cd56ba7c66eee600a9d46f598`, `docs/relatorios/2026-10-28-platform-agent-contrato.md`, sha256 `c7f4e7f98122cba90e3f5666d71c4387786a02a50e885ec1d2955944f63410f7` |
| Exemplos | mesmo commit, `docs/relatorios/2026-10-28-platform-agent-exemplos/exemplos.md`, sha256 `3bd333783796935bfd5f3c6bf605477433545399da552f720479f5ba219a70aa` |
| `main` da API | `2e639244c22e0f439860344ac46557896431f51a` (merges #28–#31). Contrato e exemplos **byte a byte iguais** à referência. Do `7d30c24` à `main` só mudaram a migration 027, o runner e o relatório; nada no gateway |
| API usada nos laboratórios | `main` @ `2e639244…`, migrations 001–027 em instalação limpa |
| Agent | base `932e61c` (`base/agent-studio-local-001`) → PR #1 `d3e0f8a` → PR #2 `e46af2a` → **PR #3 (esta missão)** |

## Mapa por fase

| Fase | Onde está o diff | Testes | Evidência real | Documento |
|---|---|---|---|---|
| PA-01 schema OBS e vetores | `b2a22b2` (A01, na base) + `2045910` (V2 sempre conferido) | `ObservabilitySchemaTest` 8, `PackageFingerprintVectorTest` 3 (0 ignorados) | os dois vetores sobre arquivos reais | [pa-01](pa-01-schema-e-vetores.md) |
| PA-02 transporte, pareamento, credencial | PR #1 (`7791cd8`, `d3e0f8a`): `platform/{HttpTransport,JdkHttpTransport,Destination,Json,SecretStore,DpapiSecretStore,CredentialVault,AuthorizationCache,MachineClient,Backoff}` e a parte de pareamento, presença e autorizações do `PlatformService` | `PlatformPairingTest` P1–P8, `JdkHttpTransportTest` H1–H3, `DpapiSecretStoreTest` S1–S2, `ArchitectureTest` | 001 J2, J8; 002 C1, C2, C3, C7 | [pa-02](pa-02-transporte-e-credencial.md) |
| PA-03 registro e relatórios | PR #1: `platform/{SupervisorReports,PackageFingerprint}`, registro e relatório no `PlatformService`, escolha da autorização no `ExecutionDialog` | `PlatformDeliveryTest` D1, D9, D10 | 001 J3, J7; 002 C3, C5, C6, C8 | [pa-03](pa-03-registro-e-relatorios.md) |
| PA-04 eventos duráveis | PR #1: `platform/{EventReader,DeliveryStore,AckCheck,DeliveryView}`, entrega no `PlatformService`, `ActivityService`/`ActivityLog`/`RunStore` (retenção atrelada ao ACK) | `PlatformDeliveryTest` D2–D8, D11–D13, `DeliveryRetentionTest`, `DeliveryQuotaTest` | 001 J4–J7; 002 C4, C5, C8 | [pa-04](pa-04-eventos-duraveis.md) |
| PA-05 homologação integrada | PR #2 (001: jornada J1–J9) + PR #3 (002: jornada complementar C1–C9) | jornadas com Agent empacotado e API reais | `docs/agent-platform-001/evidencias`, `evidencias/` desta pasta | [pa-05](pa-05-homologacao.md) |

O código das fases 02 a 04 está num único commit de implementação (`7791cd8`). Este mapa delimita cada fase por arquivo e
por teste, e os comandos de revisão de cada documento mostram só o diff da fase. Separar fisicamente em cinco PRs exige
recortar de novo as branches já publicadas. Esse recorte depende de decisão da titular (ver
[encaminhamentos](encaminhamentos.md)).

## Restrições da missão e onde são garantidas

| Restrição | Garantia |
|---|---|
| Executor e supervisor sem rede e sem chamar o transporte | `ArchitectureTest.layersRespectBoundaries`: `exec` e `obs` não importam `platform`. `noNetworkOrShellUsageInProductionCode` e a contraprova `networkIsRefusedOutsideTheTransport` |
| Transporte não inicia, reinicia nem cancela processos | `BANNED_IN_TRANSPORT`: `ProcessBuilder`, `ProcessHandle`, `ExecutionController`, `Runtime.getRuntime`, `.prune(`, `runs.save(`, com contraprova. Jornadas: 001 J6 e 002 C6 (nada relançado) |
| Rede só nas superfícies autorizadas | `java.net.http` só em `platform/`; `NetworkIsolationTest` (sem escuta, socket bruto, pipe, RMI, JMX) |
| `artifact.sha256` ≠ `package-fingerprint/1` | V1 e V2 conferem que a impressão não é o hash dos bytes. O Agent só lê `packageFingerprint` e `packageFingerprintRule`; `artifact.sha256` nem é lido |
| `storedThrough` ≠ `settledThrough` | `AckCheck` confere os dois separadamente. O progresso guarda os dois. 002 C5 mostra `stored < settled` na API real |
| Sem execução da Platform para pacote sem versão publicada | O registro exige uma autorização baixada da Platform para a impressão. Sem ela, a execução é só local (D-AP-1). Nada é registrado sem `authorizationId` |
| Sem fabricar versão, autorização ou identidade de Worker | O Agent não cria nada disso. No laboratório, autorização e Worker vêm das rotas da API, pela pessoa. A versão publicada é uma **fixture declarada** no banco descartável, porque a API não tem rota de publicação (E-AP-4) |
| Sem comandos remotos, agendamento, segredos, atualização automática, interface web | Nada disso foi implementado |
| Sem suspensão nem hibernação | Nenhuma jornada suspende a máquina |
