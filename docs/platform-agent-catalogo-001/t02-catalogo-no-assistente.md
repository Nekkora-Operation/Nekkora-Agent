# PLATFORM-AGENT-CATALOGO-001 · T02 — catálogo da máquina no assistente "Importar projeto (.zip)"

Depende da AGENT-PACOTES-001 (#11 → #12 → #13) e da rota de catálogo da API (T01, migration 036). Contrato:
`Nekkora-Platform-API/api/openapi/nekkora-agent-v1.yaml` (`GET /agent/api/v1/catalog`,
`GET /agent/api/v1/catalog/automations/{automationId}`).

## O que o Agent faz com o catálogo

- **Leitura:** na confirmação do vínculo (`PlatformService.confirmLink`), depois da presença e das autorizações. O Agent
  lê todas as páginas (100 por página, até 10 páginas), na linha do transporte. O resultado é `RemoteCatalog`:
  - `COMPLETE`: todas as páginas desta consulta;
  - `PARTIAL`: falha depois da primeira página, ou teto de páginas;
  - `UNAVAILABLE`: nenhuma página, com o motivo;
  - `UNSUPPORTED`: `404` na primeira página, ou seja, uma Platform sem a rota.
- **Cada página precisa ser deste vínculo** (organização, Agent e Worker). Senão nada dela é aceito.
- **Organização:** o nome de exibição vem do catálogo. Sem catálogo, a tela mostra o identificador e diz que o nome não
  foi consultado.
- **"Usar projeto existente":**
  - a lista mostra nome, workspace, quantidade de versões e o id curto (nomes podem repetir);
  - nada vem escolhido, nem pelo nome do ZIP;
  - na conclusão, a escolha é revalidada online (`checkSelection`): `VISIBLE` conclui; `NOT_VISIBLE` e `FAILED` não
    concluem, cada um com o seu texto.
- **"Preparar novo projeto":** "Pacote inicial 1.0.0 — projeto ainda não cadastrado por este assistente".
  - Só com o catálogo `COMPLETE`, na sugestão e de novo na conclusão.
  - Nunca afirma que o nome está livre.
  - Um homônimo no catálogo é avisado, nunca escolhido.
- **Versão:**
  - número da Platform (`nº N`) e versão declarada (`packageVersion`) são campos separados;
  - a ordem é numérica (1.10.0 > 1.9.0);
  - versão remota sem `MAIOR.MENOR.CORREÇÃO`: sem sugestão e sem conversão;
  - a sugestão é a próxima correção depois da maior versão declarada **visível**, sempre "sem reserva", e avisa que pode
    haver versão mais recente não autorizada a esta máquina;
  - o histórico local não prevalece: uma versão local acima da remota só gera aviso.
- **Comparação pela regra completa:**
  - a impressão `external-python-fingerprint/1` inclui a versão;
  - por isso o pacote preparado é comparado **com a versão declarada de cada versão visível**;
  - igual = `IDENTICAL`: sugere reutilizar aquela versão declarada;
  - diferente de todas = `DIFFERENT`; escolhas incompletas ou versões sem versão declarada = `UNAVAILABLE`;
  - nenhum código vai à API para comparar.
- **"Consultar a Platform de novo":** uma consulta por vez; o ZIP lido continua. Se a resposta for de outro vínculo, tudo
  é invalidado.
- **Falhas com texto próprio:** sem conexão, TLS recusado, tempo esgotado, `401` (recusa da credencial), `403`, `429`
  (com `Retry-After`), erro do servidor e resposta fora do contrato.
- **Cache:** nenhum cache persistente do catálogo. A tela mostra a hora da consulta, e concluir sempre consulta de novo.
- **Platform anterior (sem catálogo):** a tela explica a incompatibilidade, e o assistente não conclui pacotes. O
  histórico local não substitui a Platform. A execução de pacotes já preparados não muda.

## O que não muda

O manifesto e a impressão do pacote não mudam (nada de organização ou projeto dentro do pacote). A associação local
continua por identificadores e origem (`PackageHistory`). Nada é publicado, reservado ou criado na Platform. A execução
offline de pacotes já preparados segue igual.
