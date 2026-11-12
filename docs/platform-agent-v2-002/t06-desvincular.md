# PLATFORM-AGENT-V2-002 · T06 — Desvincular a máquina no Agent

> **Atualização (PLATFORM-AGENT-V2-004, T13, 12/11/2026):** o desvínculo passou a ser pedido também à Platform, por decisão da titular. Ver `docs/platform-agent-v2-004/t13-desvinculo-remoto.md`. O que este documento diz sobre o desvínculo ser só local vale para Agents anteriores e para Platforms sem a rota nova.

## O que muda no card "Platform"

Três intenções, cada uma com o próprio botão e o próprio tipo de código:

| Botão | Quando aparece | Código |
|---|---|---|
| **Parear com a Platform…** (Alt+A) | Não pareado | código de **pareamento** (Workers → Vincular Agent) |
| **Renovar credencial…** (Alt+N) | Pareado ou credencial recusada | código de **renovação** gerado na Platform para esta máquina |
| **Desvincular esta máquina…** (Alt+M) | Pareado (também durante uma renovação), credencial recusada, endereço alterado, versão recusada | — |

O card mostra **Platform** (host), **Organização**, **Worker** e **Agent**. O contrato da máquina (resposta do
`POST /agent/api/v1/enrollments/redeem`) **não traz nomes**, só identificadores (`organizationId`, `agentId`,
`installationId`, `workerId`, `credentialId`). Por isso o card mostra a forma curta (8 caracteres) e a forma completa
fica na dica e no nome acessível; a confirmação do desvínculo mostra os identificadores completos (copiáveis), para a
pessoa informar a quem administra a organização.

## Código do tipo errado

O Agent envia `Nekkora-Agent-Intent: pair` (pareamento) ou `Nekkora-Agent-Intent: rotate` (renovação, com o Bearer
atual). Os dois tipos de código têm o mesmo formato (`nkp1.…`): só a Platform sabe o tipo. Ela recusa o tipo errado com
`409 {"error":"conflict","reason":"pairing_code_for_rotation" | "rotation_code_for_pairing"}` **sem consumir o código**.
No Agent sai só a tentativa pendente (o material do resgate é apagado da guarda); o vínculo existente fica intacto.

| Situação | Mensagem (código) |
|---|---|
| código de pareamento em "Renovar credencial" | "Este é um código de pareamento. Para renovar a credencial desta máquina, gere um código de renovação na Platform. Para ligar esta máquina a outro Worker ou organização, desvincule-a primeiro e pareie de novo." (`AGT-PLT-027`) |
| código de renovação em "Parear" | "Este é um código de renovação de credencial, que só serve para uma máquina já pareada. Para parear, gere um código de pareamento na Platform (Workers → Vincular Agent)." (`AGT-PLT-028`) |

Outros `409` continuam como `AGT-PLT-005` (Worker já associado a outro Agent ativo).

## Desvincular esta máquina

1. **Execução em andamento** (da janela, de um pedido da Platform ou do lançador do Python externo): o desvínculo não é
   concluído; a pessoa vê a explicação e tenta depois (`AGT-PLT-029`). Conferido antes da confirmação e de novo no
   momento de aplicar. A coordenação fica em `app/MachineUnlink` (o transporte não conhece o executor).
2. **Consulta à Platform** (só com o vínculo em uso e sem resgate pendente): uma chamada de presença com a credencial.
   - aceita → "A Platform ainda aceita esta máquina: o vínculo continua lá até uma pessoa autorizada revogar o Agent em Workers."
   - `401` → "A Platform recusou a credencial desta máquina (revogada ou vencida)." (a credencial passa a recusada, como em
     qualquer chamada; nenhuma chamada nova depois)
   - sem resposta / indisponível → "A Platform não pôde ser consultada agora. Para concluir, uma pessoa com permissão deve
     revogar o Agent em Workers, na Platform de origem (host, organização …)."
   - não consultada (endereço alterado, versão recusada, renovação em andamento, sem credencial utilizável) → idem, com o motivo.
   O Agent **nunca** diz que o Worker está livre na Platform.
3. **Confirmação** (`UnlinkDialog`) com as consequências, antes de qualquer remoção: a máquina para de enviar e de
   receber pelo vínculo atual; a credencial é apagada da proteção do Windows; as autorizações guardadas deixam de valer;
   o histórico das execuções e as automações da máquina ficam; o Worker e as autorizações dele na Platform não mudam por
   esta máquina — para liberar o Worker, uma pessoa com permissão revoga o Agent na Platform (Workers). "Cancelar"
   (padrão, Enter/Esc) não muda nada.
4. **Envios pendentes** do vínculo atual: a confirmação mostra quantas execuções (e eventos) e oferece
   **"Aguardar envios"** (nada muda; o envio continua) ou **"Desvincular mesmo assim"**. Se aparecer envio pendente depois
   da confirmação, o transporte recusa (`AGT-PLT-030`) e a pessoa decide de novo.
5. **Renovação em andamento** (decisão): o desvínculo **abandona** a tentativa, com aviso na confirmação (o código de
   renovação pode já ter sido usado na Platform; a máquina fica sem credencial). Bloquear deixaria a pessoa presa enquanto a
   Platform não responde, e o resultado da renovação não importa mais (a credencial sai de qualquer forma). Um pareamento
   em andamento não tem vínculo a desfazer (o botão não aparece).

### O que o desvínculo faz (na linha do transporte, nunca no meio de uma rodada)

1. grava `plataforma/desvinculos/<agentId>.json` **antes** de remover qualquer coisa: Agent, organização, Worker, origem,
   instante, resposta da Platform consultada, execuções não entregues (com `settledThrough` e eventos pendentes), pedidos
   fechados e se havia intenção pré-início. Sem segredo;
2. apaga `credencial` e `resgate` da guarda DPAPI e o `vinculo.json` (sem vínculo, o lançador externo não acha Agent:
   `LocalGate` recusa novo monitoramento);
3. apaga `autorizacoes.json`;
4. fecha (`CLOSED`, `serverOutcome = UNLINKED`) os pedidos de execução do Agent anterior que não foram lançados — nunca
   decididos, enviados nem lançados depois;
5. descarta `entregas/intencao.json`;
6. zera o estado em memória da identidade anterior (prazos de presença, autorizações e pedidos, conectividade, pastas já
   vistas sem pedido de vínculo, desistências pendentes).

Ficam: `execucoes/` (histórico) e os arquivos de `entregas/`. As entregas que estavam pendentes aparecem como
**"Não entregue (vínculo removido)"** na janela de execução e na aba Atividade ("Envio à Platform"), também depois de
reiniciar. Elas continuam ligadas ao `agentId` anterior e **nunca** são enviadas por outro vínculo.

Depois do desvínculo, fechar e abrir o Agent mostra "Não pareado". Parear de novo na mesma pasta de dados (inclusive em
outra organização) funciona sem apagar nada; nenhuma autorização, entrega, pedido ou intenção anterior é usada.

## Limitações

- **Sem nomes**: organização e Worker aparecem por identificador; o contrato não traz nomes.
- **Revogação na Platform só por uma pessoa**: a máquina não tem rota para revogar nem para se desvincular no servidor, e a
  credencial da máquina não ganha poder de administração. O desvínculo é local; o Worker só fica livre na Platform quando
  uma pessoa com `agent.manage` (e step-up) revogar o Agent em Workers.
- **Desvínculo sem comunicação**: conclui localmente; os registros não entregues ficam guardados e identificados. A
  Platform continua com o Agent ativo até a revogação pela pessoa.
- Uma consulta que não responde em 45 s é dada como "não pôde ser consultada".

## Testes

- `PlatformUnlinkTest` (U1–U15, transporte simulado): cabeçalho de intenção; código errado nos dois sentidos (não
  consumido, vínculo intacto); código inválido/desconhecido; resposta perdida; Agent anterior sem cabeçalho; cancelar;
  desvínculo com credencial aceita, sem comunicação e depois da revogação (401); reabrir; execução em andamento;
  envios pendentes ("Aguardar" × "Desvincular mesmo assim"); novo pareamento na mesma pasta em outra organização;
  renovação em andamento; lançador externo.
- `PlatformTextTest`: textos (nunca "livre"; orientação com host e organização).
- `PlatformDesktopTest` (`@Tag("desktop")`): card pareado com as três intenções, confirmação, cancelar e confirmar.
- `FakeGateway` espelha o contrato novo do resgate (intenção, `409` com `reason`, código não consumido; sem cabeçalho e
  com Bearer, código de pareamento = `409`).
