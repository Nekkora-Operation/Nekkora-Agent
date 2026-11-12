# PLATFORM-AGENT-V2-004 · T13 — "Desvincular esta máquina" também na Platform (Agent)

> **Precedência.** Substitui a limitação do T06 (`docs/platform-agent-v2-002/t06-desvincular.md`): o desvínculo deixa de
> ser só local. O protocolo do servidor, a rota e a migration 035 estão na API
> (`docs/platform-agent-v2-004/t13-desvinculo-remoto.md` e `api/openapi/nekkora-agent-v1.yaml`, `POST /agent/api/v1/link/unlink`).

## Estados

| Estado | O que a pessoa vê | O que acontece |
|---|---|---|
| Confirmação | Diálogo com as consequências, a Platform, a organização e o Worker do vínculo | Cancelar (padrão, Enter e Esc) não muda nada |
| Envio | O botão mostra a espera | O pedido é gravado e enviado na hora, com conexão |
| **Aguardando a Platform** (`UNLINKING`) | Aviso no card: o que foi confirmado, as tentativas e o último resultado | Nada novo começa pelo vínculo antigo; só a operação de desvínculo sai pela rede, espaçada; pareamento recusado |
| Concluído | "Máquina desvinculada" com o desfecho (confirmado, já revogado pela administração, já encerrado pela máquina) ou "Desvinculada só nesta máquina" (Platform anterior) | Credencial, `vinculo.json`, autorizações e aprovações locais de pacote apagadas; pedido apagado |

## Arquivos (`<dados>/plataforma/`, sem segredo)

- `desvinculo-pendente.json` (`nekkora-agent/platform-unlink-request` 1.0.0): `operationId` (gerado na confirmação e
  reusado em toda repetição), origem, organização, Agent, Worker e credencial do vínculo original, pedidos aceitos e
  iniciados, tentativas e o último resultado não conclusivo. Gravado **antes** do primeiro envio; apagado só na conclusão.
  Ilegível: na dúvida, o vínculo antigo continua bloqueado e nada é enviado.
- `desvinculos/<agentId>.json` (registro do T06): gravado na confirmação com `server: PENDING` e as entregas não
  terminadas (marcadas como **não entregues**); atualizado na conclusão com o desfecho (`CONFIRMED`,
  `ENDED_BY_ADMINISTRATION`, `ENDED_BY_MACHINE`, `UNSUPPORTED`).
- A credencial continua na guarda do Windows (DPAPI) até a conclusão: é a única prova da máquina para concluir ou
  reconciliar a operação. Nenhum segredo vai para JSON ou log.

## Conclusão e reconciliação

- **200** desta operação (inclusive a repetição depois de uma resposta perdida): `CONFIRMED`.
- **409** `link_ended_by_administration` / `link_ended_by_machine`: o vínculo já tinha terminado; conclui.
- **404** (Platform sem a rota): `UNSUPPORTED`; desvincula só aqui e orienta a revogar em Workers.
- **401, 429, 5xx, sem resposta, resposta fora do contrato**: **não** concluem. Pendente; a mesma operação é repetida
  2 s → 4 s → … até 5 min (com variação e `Retry-After`), enquanto o Agent estiver aberto. Agent fechado não envia nada;
  ao abrir, retoma.
- Credencial marcada como recusada antes: só a rota de desvínculo a usa (`CredentialVault.tokenForUnlink`), porque um
  401 anterior não prova que o vínculo terminou.
- Renovação de credencial em voo: termina (ou é abandonada) antes; o desvínculo sai com a credencial que valer.

## Nada novo pelo vínculo antigo (inclusive em corrida)

| Caminho de início | Bloqueio |
|---|---|
| Tela, com autorização (`prepare`) | Recusa com o pedido presente; e confere de novo **depois** de gravar a intenção pré-início |
| Pedido da Platform (`RunRequestCoordinator`) | `linkRequestFor` vazio; e o gancho `PreLaunch`, **dentro** do `start` (trava do executor), recusa |
| Lançador externo (outro processo) | `LocalGate.pairedAgent` vazio com o pedido presente; e o lançador confere de novo **depois** de gravar `lancador.json` |

Do lado do desvínculo: grava o pedido e **só então** confere de novo a execução (inclusive lançadores vivos ainda sem
`estado.json`) e a intenção pré-início. Se algo começou, o pedido sai e nada muda (`AGT-PLT-029`). Cada lado grava a
própria marca antes de conferir a do outro: os dois nunca passam.

## Entregas pendentes

"Aguardar envios" (padrão) mantém tudo. "Desvincular mesmo assim" guarda as execuções como não entregues
(`UNLINKED_UNDELIVERED`); elas nunca são enviadas por outro vínculo nem outra organização.

## Aprovações locais de pacote

Na conclusão, `execucao/aprovacoes.json` é esvaziado (`ApprovalStore.clearAll`): um novo pareamento, inclusive em
outra organização, não herda aprovações. Execuções só locais voltam a pedir a aprovação da pessoa.

## Falhas injetadas (só laboratório)

`-Dnekkora.agent.lab.falha=perderRespostaDesvinculo` com o JAR `tools/lab-faults`: descarta uma vez a resposta de um
desvínculo que a Platform já concluiu (ponto `UNLINK_RESPONSE_RECEIVED`). Fora da distribuição normal
(`LabFaultsAbsentTest`).

## Códigos novos

`AGT-PLT-032` (aguardando a Platform), `AGT-PLT-033` (pareamento recusado enquanto aguarda), `AGT-PLT-034` (Platform sem
suporte: desvinculada só aqui).

## Testes

- **`PlatformRemoteUnlinkTest` (13):**
  - online;
  - sem rede, com pedido durável e reinício;
  - resposta perdida depois do commit, pela queda e pelo ponto de falha;
  - 401 isolado;
  - já revogado pela administração;
  - Platform anterior;
  - pedidos iniciados e não iniciados;
  - as duas corridas;
  - entregas pendentes;
  - chamadas atrasadas depois de um novo pareamento;
  - pedido ilegível.
- **`PlatformUnlinkTest`:** U8, U10, U13 e U14 foram atualizados para o comportamento novo, sem retirar verificações locais.
- **`ExternalLaunchTest.desvinculoConfirmadoDuranteOLancamentoImpedeOInicio`:** exige `NEKKORA_TEST_PYTHON`; sem ele, é pulado.
- **`PlatformTextTest`:** cobre os textos.
