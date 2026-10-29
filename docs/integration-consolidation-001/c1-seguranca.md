# C1 — segurança e integridade do Agent

Todos os testes citados rodam na regressão (`mvnw clean verify`). As reproduções são determinísticas: relógio manual,
gateway simulado e falhas injetadas por número de chamada. Os testes do Windows usam bloqueio real de arquivo.

## Rede só no transporte, executor e coletor sem transporte

| Garantia | Teste | Contraprova |
|---|---|---|
| `java.net.http` só em `platform/`; nenhum código escuta; nenhuma conexão por socket bruto, UDS, pipe, RMI, JMX, URL, JNDI ou processo de rede (curl, wget, PowerShell, BITS, certutil) | `NetworkIsolationTest` ISO-01/ISO-02 | M6 (`toURL().openStream()` no `RunStore`): vermelho |
| `exec/` e `obs/` não citam `com.nekkora.agent.platform`, `PlatformService`, `MachineClient` nem `HttpTransport` | `ArchitectureTest.executorAndCollectorNeverReachTheTransport` | M5 (002) |
| o transporte não inicia, reinicia nem cancela processo | `ArchitectureTest` (`BANNED_IN_TRANSPORT`) | M1–M4 (001) |

## Credencial, pareamento e destino

| Cenário | Teste |
|---|---|
| DPAPI: ida e volta, arquivo sem o segredo, apagar remove; sem proteção do sistema, nada é gravado | `DpapiSecretStoreTest` S1–S2 |
| resposta de pareamento perdida: repetida com o MESMO material, guardado antes da tentativa | P1 |
| mesmo código com outro segredo: recusado | P2 |
| resgate repetido depois de rotação ou revogação: recusado, nada reativado | P3 |
| queda no meio da rotação ou entre a credencial e o vínculo | P4, P5 |
| destino `http`, código malformado ou sem proteção do sistema: recusado sem rede | P6 |
| destino alterado: a credencial não acompanha e 3xx nunca é seguido | P7, H3 |
| segredo nunca em arquivo comum, diagnóstico ou `toString` | P8 |
| TLS: certificado não confiável e nome divergente não conectam; nada é relaxado | H2 |
| confiança de laboratório: só na cópia de laboratório (armazenamento próprio no `.cfg`); o produto não tem opção | D-AP-11; H2 |
| `401`: o transporte inteiro para na primeira recusa e os fatos locais ficam intactos | D8 (D-AP-9) |

## D-AP-1 — execução só local com a máquina pareada

`LocalOnlyChoiceTest`:
- sem autorização, ou com duas válidas, nada é pré-escolhido ("Não registrar" fica escolhido). Com uma válida e uma vencida,
  a válida é pré-escolhida;
- a vencida aparece marcada (`VENCIDA`) e o `prepare` a recusa (`AGT-PLT` de autorização recusada), então nada começa sob ela;
- execução só local com a máquina pareada: nenhum `POST /runs` (nem registro, relatório ou lote), nenhuma entrega com o id
  da execução, `estado.json` byte a byte igual. A presença da máquina continua. A execução não recebe identidade remota
  nem selo de telemetria central.

A regra de pré-escolha agora existe num lugar só (`PlatformService.preselected`), usado pela janela.

## D-AP-2 — relógio local e autorizações guardadas

**Antes:** o `validUntil` era conferido só pelo relógio local. Atrasar o relógio fazia uma autorização vencida voltar a
"Registrar na Platform". A API não confia no relógio do Agent: no registro, `authorization_state_at_registration` sai da
hora do servidor (`valid_until <= clock_timestamp()` → 3 = EXPIRED, em `GatewayService.registerOnce`). Por isso o servidor
não tinha bypass, mas a escolha no Agent tinha. **Correção pontual** (`3b07fc6`): o prazo é conferido contra um piso, que
é a hora do servidor da última presença (gravada no download como `serverTimeAt`) e, entre contatos, o maior instante
local já consultado (só na memória).

| Situação | Efeito agora (`AuthorizationClockTest`) |
|---|---|
| relógio atrasado depois de a autorização vencer, na mesma sessão | continua vencida |
| reinício com relógio muito atrasado | piso = última hora do servidor. Se ela ainda estiver antes do prazo, a autorização vale (limite abaixo) |
| relógio adiantado | vence antes (falha fechada) até a próxima presença, cuja hora do servidor corrige o piso |
| arquivo gravado pela versão anterior (sem `serverTimeAt`) | lido normalmente |

**Limite que fica:** com o Agent offline, um atraso aplicado depois de um reinício e que ainda fique acima da última hora
do servidor não é detectável no Agent. A API marca o registro como EXPIRED pela hora dela. Mitigação proposta para a
versão seguinte: o servidor devolve a hora em cada resposta e grava a diferença entre o `agent_created_at` declarado e o
recebimento, para auditoria.

## D-AP-3, D-AP-4 e D-AP-7 — perda só quando definitiva e registrada

| Cenário | Teste | Resultado |
|---|---|---|
| escrita do histórico falha (arquivo em uso, `fsync` falho, escrita parcial) | `ActivityPersistenceTest` (18) | pendente, gravado uma vez em ordem. Atraso nunca vira perda |
| reabertura com o histórico ilegível | `ActivityPersistenceTest` (novo) | coleta adiada. Sem a correção, sequência pulava e eventos sumiam |
| cota excedida | `DeliveryQuotaTest`, D13 | pendentes poupadas primeiro. Se reduzidas, faixa exata `[settled+1, purged_until]` declarada |
| perda de disco ou de arquivo, linha incompleta | D6 | linha incompleta e não sincronizada não são perda. Perda registrada vira faixa exata gravada antes do envio |
| evento `INVALID` | D5 | entrega para no número, sem laço, até a decisão da pessoa (`[n, n]`) |
| resposta ausente (ACK perdido) | D3, D4 | reenvio `DUPLICATE`, sem avanço antes do ACK |
| queda entre registrar a perda e apagar os bytes | `ActivityService.purge` + `LossDeclarationTest` | a troca do arquivo É o registro (temporário sincronizado e troca atômica: ou o arquivo antigo inteiro, ou só o `HISTORY_PURGED`). A declaração com a resposta perdida e o reinício chegam uma vez (`DUPLICATE`), sem outra faixa |

`declaredMissing` só nasce de `AGT-OBS-009` registrado no disco ou de `HISTORY_PURGED` com `purged_until_sequence`, ou de
desistência explícita da pessoa (D-AP-3/4). Uma lacuna sem registro para a entrega ("Envio parado") e nunca é declarada
sozinha.

## Desfecho e completude

`OUTCOME_UNKNOWN` continua sem nova tentativa de negócio (D10; 002 C6). O desfecho (`SUCCEEDED/KNOWN`, `UNKNOWN`) e a
completude (`COMPLETE`/`INCOMPLETE` com `LOST_LOCAL` ou `LOST_AT_SOURCE`) são dimensões separadas na API (002 C5 e C8).
