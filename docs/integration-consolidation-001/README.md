# NEKKORA-INTEGRATION-CONSOLIDATION-001 — Agent: inventário (C0), segurança e integridade (C1), candidato à main (C3)

Correção, revisão cruzada e consolidação controlada do canal Agent → Platform. Não há canal novo e nada vai para produção.
Merge e publicação continuam com a titular.

## Fontes de autoridade

| Item | Valor |
|---|---|
| Agent `main` | `afd1bb5` (commit inicial mínimo, 3 arquivos). **Não representa o produto homologado.** |
| Base sem PR | `base/agent-studio-local-001` `932e61c`: 52 commits desde a `main` |
| PRs | #1 `d3e0f8a` → #2 `e46af2a` → #3 `23ce414` (empilhados, sem merge) |
| Correções desta missão | `fix/agent-integration-c1-001` sobre o `23ce414` (PR próprio para o #3) |
| API | `main` `2e639244c22e0f439860344ac46557896431f51a`, migrations 001–027 |
| Contrato | `7d30c2437927e33cd56ba7c66eee600a9d46f598` (sha256 `c7f4e7f9…63410f7`). O E-AP-3 corrige só o texto do item de consulta de eventos (PR da API) |
| Studio | pacote real `da7bb94` (COMPAT-001), manifesto 1.5.0, Framework 0.3.0, impressão `5664f1a56556e8137dc6e62c435485469e18501d853a8f51268cb377ce1c0b3d` |

## C0 — inventário

`evidencias/c0-inventario.txt` registra commits, árvores, merge-base e faixas.

| Onde | O que está lá |
|---|---|
| `main` (remoto) | só o commit inicial `afd1bb5` |
| só na base intermediária (remota, sem PR) | os 52 commits AGENT-001, DESKTOP-001, OBS-001, OBS-PERSISTENCE-HARDENING, AGENT-LEAN-001, AGENT-PACKAGE-ORIGIN-001, F-AGT-01 e STUDIO-AGENT-LOCAL-001 |
| PRs #1–#3 (remotos) | transporte (2), jornada 001 (6), revisão por fase 002 (3) |
| só no Windows (branches locais) | `feat/agent-lean-001`, `feat/agent-package-origin-001`, `feat/nekkora-agent-001`, `feat/nekkora-desktop-001`, `fix/agent-f-agt-01-001` e `feat/agent-studio-local-001`, todas contidas na base (nada exclusivo); `fix/agent-integration-c1-001` (esta missão) |

O merge-base entre a `main` e o #3 é `afd1bb5`, então a pilha inteira entra na `main` por avanço rápido, sem conflito.

### Revisão da base (52 commits, além do diff do PR #1)

Revisão independente, só de leitura, sem confiar nas mensagens de commit nem nos documentos. Depois conferi cada achado
marcado abaixo como "confirmado".

| Id | Área | Achado | Situação |
|---|---|---|---|
| A4-1 | OBS | reabertura com o `atividade.jsonl` ilegível recomeçava a sequência e as posições (perda e salto de sequência) | **confirmado e corrigido** (`f87ffaf`, vermelho/verde) |
| A7-1 | Isolamento | o guarda textual não pegava `toURL().openStream()`, JNDI nem clientes de rede por processo | **confirmado e corrigido** (`8642492`, mutação M6) |
| A8-1 | Dado pessoal (repositório público) | caminhos do perfil do Windows em 3 documentos | **confirmado e higienizado** (`b903952`); o histórico anterior ainda os tem (reescrever exige force-push: decisão da titular) |
| A8-2 | Dado pessoal | e-mail pessoal como autor dos commits | RESSALVA: decisão da titular (e-mail noreply nos próximos) |
| A9-1 | Licença | o repositório não tem LICENSE e o design system "segue a licença da Nekkora" | RESSALVA: decisão da titular antes de distribuir |
| A1-1/A1-2 | F-AGT-01 | janela curta entre o `start()` e o `processo.json` e falha de `writeProcessRef` sem rastro | RESSALVA (apontado, não reproduzido): sem relançamento. Uma queda nessa janela vira `OUTCOME_UNKNOWN` |
| A1-3 | Posse | sem `startInstant` o processo não é adotável e o cancelamento só mata a raiz | RESSALVA (raro no Windows) |
| A2-1..3 | Adoção | dois registros vivos, prazo renovado na adoção, ADOPTED depois da supervisão | RESSALVA menor (apontado, não reproduzido) |
| A3-1/A3-2 | `estado.json` | registro ilegível fora da reconciliação; diretório sem sincronização depois da troca (Linux) | RESSALVA |
| A10-1/2 | Testes | 15 falhas Linux por caminhos `C:\` sem `@EnabledOnOs`; testes de processo pulados sem Python | RESSALVA (Linux continua documentado como "15 herdadas") |

Revisado e correto: nenhum relançamento (a única chamada de `start()` é o botão), `OUTCOME_UNKNOWN` sem prova, posse por
PID + instante + executável, `estado.json` 1.0.0/1.1.0, progresso só de checkpoint íntegro, pacote (nomes, symlink,
limites, cópia conferida). Dependências: FlatLaf 3.7.2 Apache-2.0, JNA 5.17.0 Apache-2.0 (só no transporte); em teste,
networknt 1.5.9 Apache-2.0 e JUnit 5.11.4 EPL-2.0; fontes OFL-1.1. Não há binário versionado (o DLL do FlatLaf sai do jar
no empacotamento). Não há segredo nem token: os e-mails dos testes são fictícios.

## Matriz fonte → contrato → teste → evidência

| Fonte (Agent) | Contrato | Teste | Evidência | Classe |
|---|---|---|---|---|
| `platform/CredentialVault`, `DpapiSecretStore` | 4.1–4.4, 12.4 | `PlatformPairingTest` P1–P8, `DpapiSecretStoreTest` | 001 J2, J8; 002 C1, C7 | ACEITO |
| `platform/JdkHttpTransport`, `Destination` | 4.4 (TLS normal, sem redirecionamento, destino fixo) | `JdkHttpTransportTest` H1–H3, P7 | 001 J1; D-AP-8/11 | ACEITO |
| `platform/AuthorizationCache` | 6.1–6.3 | `AuthorizationClockTest` (novo), `LocalOnlyChoiceTest` (novo) | 002 C3 (início offline) | ACEITO depois da correção D-AP-2 |
| escolha da autorização (`PlatformService.preselected`, `ExecutionDialog`) | 6.1–6.3 (D-AP-1) | `LocalOnlyChoiceTest` | 001 J3 | ACEITO |
| `platform/SupervisorReports`, registro | 7 | `PlatformDeliveryTest` D1, D9, D10 | 002 C6 (`OUTCOME_UNKNOWN`) | ACEITO |
| `platform/EventReader`, `DeliveryStore`, `AckCheck` | 8.1–8.3 | D2–D8, D11–D13, `LossDeclarationTest` (novo) | 001 J4–J7; 002 C4, C5, C8 | ACEITO |
| `obs/ActivityLog` (reabertura) | 8.1 (sequência nunca reutilizada) | `ActivityPersistenceTest` (novo caso) | prova vermelho/verde 1 | ACEITO depois da correção A4-1 |
| `obs/ActivityService.purge` | 8.3, 11 (perda local declarada) | D13, `LossDeclarationTest`, `ActivityServiceTest` | — | ACEITO (troca sincronizada) |
| isolamento | missão sec. 5 | `NetworkIsolationTest`, `ArchitectureTest` (M1–M6) | ISO-R | ACEITO |
| impressão do pacote | 6.4 | `PackageFingerprintVectorTest` V1/V2 | vetores real e sintético | ACEITO |
| versão publicada | 6.1, 13 | — | **fixture declarada** (E-AP-4) | RESSALVA: próxima missão (PLATFORM-AUTOMATION-LIFECYCLE-001) |
| 429, `INVALID` e `CONFLICT` reais | 8.3 | D5, D11, H1 | C4 desta missão (cliente de laboratório contra a API real) | ver `c4-homologacao.md` |

Nenhuma linha é BLOQUEANTE depois das correções. Os itens marcados como RESSALVA dependem da titular ou da próxima missão.

## C1 — segurança e integridade

Detalhes em [c1-seguranca.md](c1-seguranca.md). Correções (PR próprio, cada uma com teste e prova vermelho/verde):

| Commit | O quê |
|---|---|
| `f87ffaf` | reabertura com histórico ilegível adia a coleta (A4-1) |
| `3b07fc6` | D-AP-2: piso de relógio (hora do servidor + maior instante local consultado) |
| `9b8e4cd` | D-AP-1: regra de pré-escolha única e prova de que a execução só local não é registrada |
| `d115dab` | D-AP-3/7: expurgo local com troca sincronizada e declaração idempotente depois de queda |
| `8642492` | guarda de rede ampliado (A7-1) |
| `b903952` | higienização dos caminhos pessoais (A8-1) |

## C3 — candidato à `main`

- **Pilha mantida:** #1 → #2 → #3, revisão guiada por fase (`docs/agent-platform-002/`), sem cinco PRs novos e sem
  refazer commits. As correções desta missão estão num PR próprio sobre o #3 (`fix/agent-integration-c1-001`).
- **Consolidação para a `main`:** `integracao/agent-main-001` aponta para o MESMO commit final de
  `fix/agent-integration-c1-001`. O merge-base com a `main` é `afd1bb5`, então entra por avanço rápido, sem conflito. A
  origem de cada commit está em `evidencias/c0-inventario.txt` (base 52, #1 2, #2 6, #3 3, mais os desta missão). Um
  merge numa base intermediária NÃO integra nada à `main`: só o merge deste PR (ou da pilha inteira, na ordem) integra.
- **Árvore:** a distribuição homologada foi montada do commit `7986e84`, árvore `src` `2ab110d1e25e0ba59f3274c0658b28d581f964aa`.
  Os commits posteriores só tocam `docs/` e `tools/platform-lab/`, e `git rev-parse HEAD:src` continua `2ab110d1…`.

| Suíte (commit `7986e84`) | Resultado | Evidência |
|---|---|---|
| Windows `mvnw clean verify` (com `NEKKORA_TEST_PYTHON`) | **287** testes, 0 falhas, 0 erros, 2 ignorados (ISO-04 sem imagem montada; `SourceFileTest`, já existente) | `evidencias/c3-windows.txt` |
| Desktop `-Pdesktop clean verify` | **311** + 2 escalas, 0 falhas, 2 ignorados | `evidencias/c3-desktop.txt` |
| Linux Docker `eclipse-temurin:21-jdk-noble` (`git archive`, Maven offline) | 287 testes, **as mesmas 15 falhas herdadas** (ShortcutTest 13, AgentBootstrapTest 1, SensitiveDataLeakTest 1: caminhos `C:\` sem `@EnabledOnOs`), 38 ignorados. **Linux não é declarado verde** | `evidencias/c3-linux.txt` |
| Pacote de um clone novo (`git clone --no-local`, `package-windows.ps1 -NoZip` com os testes) | `VERSOES.txt` = `7986e84`, módulos `jdeps` só os esperados (`java.net.http` incluído para o transporte) | `evidencias/c3-pacote-clone-novo.txt` |
| Vetores da impressão | real `5664f1a5…` e sintético `cada6a64…` iguais aos do contrato; `artifact.sha256` diferente (V1/V2 em `PackageFingerprintVectorTest`) | regressão |
| Arquitetura e isolamento | `ArchitectureTest` 4, `NetworkIsolationTest` 5 (M1–M6 vermelho/verde) | regressão |
| E2E real | C4: J1–J9 22/22, C1–C9 com a retomada, destino 2/2, adversário 10/10 | [c4-homologacao.md](c4-homologacao.md) |

Mudanças pequenas de ferramenta (não de produto): `jornada.ps1` cria a pasta de saída; `subir.sh` aceita `LAB_REDE=49`
(`compose.lab-rede-49.yml`); `destino.ps1` e `adversario.py` novos.

## Parecer por PR (a titular decide e executa os merges)

| PR | Parecer | Condição |
|---|---|---|
| Agent #1 (transporte) | **MERGE_READY** junto com o PR das correções C1 | sozinho ainda tem o D-AP-2 e a reabertura (A4-1) |
| Agent #2 (jornada 001) | **MERGE_READY** | só documentos e ferramentas |
| Agent #3 (revisão por fase 002) | **MERGE_READY** | — |
| Agent: correções C1 (`fix/agent-integration-c1-001` → #3) | **MERGE_READY** | — |
| Agent: consolidação → `main` (`integracao/agent-main-001`) | **MERGE_READY com ressalvas da titular** | A8-1 (caminhos pessoais no histórico público), A8-2 (e-mail de autor), A9-1 (LICENSE). E-AP-4 (fixture de versão) continua para a PLATFORM-AUTOMATION-LIFECYCLE-001 |
| API E-AP-1 (runner + gateway) | **MERGE_READY** | — |
| API E-AP-2 (relatório do recurso 6) | **MERGE_READY** (só documento) | decisão D-EAP2 da titular antes de qualquer SQL |
| API E-AP-3 (contrato + IT) | **MERGE_READY** | o texto do contrato muda e o sha256 de referência muda junto (registrar no próximo inventário) |
| API E-AP-5 (sub-rede do E2E) | **MERGE_READY** | baixa prioridade; padrão igual ao anterior |
