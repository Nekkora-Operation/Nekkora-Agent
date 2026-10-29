# AGENT-PLATFORM-001 — inventário inicial e mapa

## Bases usadas

| Item | Valor |
|---|---|
| Agent, base | `feat/agent-studio-local-001` @ `932e61c` (local; árvore limpa). Contém a F-AGT-01 (`f77e00e`, ainda pendente de revisão independente) e a correção A01 do schema OBS (`b2a22b2`) |
| Agent, branch desta missão | `feat/agent-platform-transport-001` |
| API, referência fixa do contrato | `Nekkora-Platform-API` @ `7d30c2437927e33cd56ba7c66eee600a9d46f598` |
| API, `main` integrada | `2e639244c22e0f439860344ac46557896431f51a` (merges #28, #29, #30 e #31) |
| Contrato na `main` | `2026-10-28-platform-agent-contrato.md` sha256 `c7f4e7f9…63410f7` e `exemplos.md` sha256 `3bd33378…19aa70aa`: **byte a byte iguais** aos da referência fixa |
| Diferença `7d30c24` → `main` | só a migration 027 (lote do expurgo), o runner e os testes de homologação 026/027 e o relatório. **Nenhuma mudança** em `api/nekkora-agent-gateway` nem no contrato |
| Remoto do Agent | `Nekkora-Operation/Nekkora-Agent`, **público e vazio** (nenhuma ref) |

## Mapa: capacidade → alteração → aceite → dependência

| Capacidade existente | Alteração | Teste de aceite | Dependência externa |
|---|---|---|---|
| Executor supervisionado (`ExecutionController`), `estado.json` 1.1.0 gravado a cada transição; reconciliação sem relançar | **Nenhuma.** O transporte só lê o `estado.json` pelo `RunStore` | D1, D10 (cadeia de relatórios), jornada J6 | — |
| Histórico OBS (`atividade.jsonl`): só acréscimo, sequência estável, `fsync`, lacunas explícitas (`AGT-OBS-009`), cota de 96 MiB com `HISTORY_PURGED` | Leitura pelo transporte (sem segundo histórico); `durableThrough` (só o sincronizado sai); a cota poupa primeiro o que não teve ACK | D2–D7, D13, Q1–Q2 | — |
| Retenção das execuções (`RunStore.prune`, 50) | Poupa as execuções com entrega pendente (até +200) | R1 | — |
| Impressão do pacote (`ExecutionPackage.fingerprint`, regra `package-fingerprint/1`) | Nenhuma: só a forma do canal (64 hex sem `sha256:`) | V1 (`cada6a64…`), V2 (`5664f1a5…`, pacote real) | — |
| Proibição de rede (`ArchitectureTest`, `NetworkIsolationTest`) | `java.net.http` permitido **só** em `com.nekkora.agent.platform`; escuta, socket bruto, pipe, RMI e JMX seguem proibidos em todo o código; contraprova | `networkIsRefusedOutsideTheTransport`, ISO-01/02 | — |
| Persistência segura de credenciais | **Ausente.** Nova guarda `SecretStore`: DPAPI do usuário (JNA 5.17.0, Apache-2.0); fora do Windows, indisponível (pareamento recusado) | S1 (Windows), S2, P8 | JNA (Maven Central) |
| Cliente HTTPS, pareamento, presença, autorizações, registro, relatórios, eventos | **Ausentes.** Pacote `com.nekkora.agent.platform` | P1–P8, D1–D13, H1–H3 | API (gateway) |
| Tela: estados fixos "Não pareado / Sem conexão" | Card "Platform" dinâmico; diálogo de pareamento; escolha da autorização; envio separado do desfecho; envios parados | jornada J2/J3/J7/J8 (capturas) | — |
