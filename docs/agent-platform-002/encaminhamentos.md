# AGENT-PLATFORM-002 — encaminhamentos e decisões pendentes

Os encaminhamentos E-AP-1 a E-AP-3 e as decisões D-AP-1 a D-AP-11 da AGENT-PLATFORM-001 continuam valendo
(`docs/agent-platform-001/decisoes-e-encaminhamentos.md`). Esta missão acrescenta o seguinte.

## E-AP-4 (API, fluxo de publicação — não bloqueia o Agent; impede uma prova sem fixture)

| | |
|---|---|
| Contrato | §6.1 (autorização sobre uma versão **publicada**), §6.4 (`command_json.agent_package`), §13 (pacote sem versão publicada, proposta) |
| Commits | contrato `7d30c24`; API `main` `2e639244c22e0f439860344ac46557896431f51a` |
| Constatação | a API só tem rotas de **leitura** para automações e versões: `GET /api/v1/o/{org}/automations/{id}`, `/automations/{id}/versions`, `/workspaces/{ws}/automations`. Não há rota para criar workspace, automação ou versão, nem para publicar versão |
| Reprodução | `grep -rhoE '@(Post\|Put\|Patch)Mapping\("[^"]*"' api --include=*.java \| grep -iE 'automation\|version\|workspace'` na `main`. Só aparecem `PUT /workers/{w}/workspaces/{ws}` e as rotas de `local-run-authorizations` |
| Consequência | nos laboratórios, o workspace, a automação e a versão publicada (com `agent_package.fingerprint` = a impressão calculada pelo Agent) são **fixtures declaradas**, gravadas por SQL no banco descartável (`tools/platform-lab/pessoa.py`), como no `AgentChannelE2E` da própria API. O Worker, o código de pareamento, a autorização, a revogação e a rotação vêm das **rotas reais** da Platform, pela pessoa com step-up. O Agent nunca cria versão, autorização ou identidade de Worker |
| Campo `artifact.sha256` da fixture | `0…0` (64 zeros) **declarado**: sem fluxo de publicação não existe artefato. O Agent não lê esse campo, e a autorização vale pela impressão (`package-fingerprint/1`). As duas medidas nunca são comparadas |
| Ajuste necessário (API) | um fluxo de publicação de versão (pela Platform ou pelo Studio) que grave `command_json.agent_package` com a impressão calculada pela regra §6.4 sobre o pacote publicado. Até lá, a prova ponta a ponta "Studio publica → pessoa autoriza → Agent executa" não existe sem fixture |

## E-AP-5 (laboratório da API — informativo)

O `api/e2e/compose.e2e.yml` fixa a sub-rede `10.251.47.0/24` e o endereço de cada serviço. Um segundo laboratório não
convive com um primeiro preservado para revisão. O complemento `tools/platform-lab/compose.lab-rede.yml` (lado do Agent,
sem alterar a API) usa a `10.251.48.0/24`. Sugestão para a API: sub-rede parametrizável por variável.

## Decisão pendente da titular: recorte físico por fase

O código das fases PA-02 a PA-04 está no commit `7791cd8`, dentro do PR #1, já publicado. Os documentos `pa-0x` delimitam
cada fase por arquivo, teste, evidência e comando de revisão. Há dois caminhos para ter um PR por fase:

1. **Manter** os PRs #1, #2 e #3 empilhados, com a revisão guiada pelos documentos por fase (estado atual).
2. **Recortar** em cinco PRs novos (PA-01 a PA-05), empilhados a partir da base, e fechar os PRs #1 e #2 sem merge.
   Recorte sem force-push, mas com o `PlatformService` dividido em estados intermediários que nunca foram homologados
   sozinhos. Exige uma regressão por fase.
