# PA-01 — JSON Schema OBS 1.0.0 e vetores da impressão do pacote

## O que o contrato pede (§12.6 e §12.8)

O schema OBS 1.0.0 publicado pelo Agent precisa voltar a ser JSON válido, sem mudar a regra. Um teste carrega o arquivo
real. Os dois vetores da regra `nekkora-agent/package-fingerprint/1` (§6.4) precisam ser conferidos.

## Diff

| Commit | Conteúdo |
|---|---|
| `b2a22b2` (na base `932e61c`, publicado sem PR próprio) | `docs/desktop-001/observabilidade/nekkora-agent-observability-1.0.0.schema.json`: só o escape do ponto no padrão de `step_id` (`\.` → `\\.`). `ObservabilitySchemaTest` novo |
| `d3e0f8a` (PR #1) | `PackageFingerprintVectorTest` V1–V3 |
| `2045910` (PR #3) | V2 deixa de depender da variável `NEKKORA_REAL_PACKAGE`: usa o pacote do Studio `da7bb94` já versionado em `src/test/resources/com/nekkora/agent/exec/real/pacote-studio` (o mesmo `manifest.json` sha256 `ca9439c7…` da COMPAT-001) |

Revisão: `git show b2a22b2 -- docs src/test` e `git diff e46af2a 2045910 -- src/test/java/com/nekkora/agent/exec/PackageFingerprintVectorTest.java`.

## Semântica preservada

`ObservabilitySchemaTest` prova que desfazer o escape devolve exatamente o SHA-256 publicado na DESKTOP-001
(`9a6c62d1…`). A mudança é só de codificação JSON, e a regra continua a mesma. O teste também confere:

- o arquivo é JSON estrito e um schema 2020-12 válido contra o metaschema (validador independente networknt 1.5.9, só nos testes);
- a superfície e os padrões são iguais aos do codec do Agent;
- os exemplos publicados são válidos;
- os 1.382 envelopes reais da COMPAT-001 são válidos.

## Testes

| Teste | Resultado |
|---|---|
| `ObservabilitySchemaTest` (8) | 8/8 |
| `PackageFingerprintVectorTest` V1: vetor sintético do contrato (CRLF e binário) = `cada6a64…` | ok |
| `PackageFingerprintVectorTest` V2: pacote real do Studio = `5664f1a5…` | ok, **sem ignorar** (antes era ignorado sem a variável) |
| `PackageFingerprintVectorTest` V3: só `sha256:<64 hex minúsculos>` vira impressão do canal | ok |

V1 e V2 conferem também que a impressão **não** é o hash dos bytes do manifesto. A regra (`package-fingerprint/1`) e o
`artifact.sha256` da versão não se comparam. O Agent nunca lê `artifact.sha256`.

## Riscos e regressão

- Risco baixo: mudam só o teste e um recurso que já existia. O código de produção (`ExecutionPackage.fingerprint`) é o mesmo
  usado na execução, sem reimplementação.
- Regressão: as suítes do candidato estão em [pa-05](pa-05-homologacao.md#regressão).
