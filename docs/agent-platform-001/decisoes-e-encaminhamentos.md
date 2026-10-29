# AGENT-PLATFORM-001 — decisões tomadas nos limites do contrato e encaminhamentos

Nada aqui muda o protocolo. Onde o contrato deixa uma regra a cargo do Agent, a decisão está registrada para revisão.

## Decisões do lado do Agent (para revisão da titular)

| Id | Decisão | Base |
|---|---|---|
| D-AP-1 | A execução **só local** continua permitida com a máquina pareada: a pessoa escolhe "Registrar na Platform" (autorização válida para a impressão) ou "Não registrar". Com exatamente uma autorização válida para o pacote, ela vem pré-escolhida; com mais de uma, ninguém escolhe pela pessoa. A autorização é conferida de novo e gravada (intenção) **antes** do início; se não valer mais, nada começa e a pessoa escolhe de novo | contrato 6.1–6.3; executor sem mudança de semântica |
| D-AP-2 | Uso offline limitado ao `validUntil` de cada autorização guardada, pelo relógio local. Revogação conhecida = a autorização sumiu num download posterior (mostrada como "encerrada na Platform"). Não existe autorização offline ilimitada | contrato 6.2 |
| D-AP-3 | `declaredMissing` só em dois casos registrados pelo próprio Agent: lacuna seguida do aviso `AGT-OBS-009` e expurgo local (`HISTORY_PURGED` com `purged_until_sequence`). Qualquer outra lacuna **para** a entrega daquela execução ("Envio parado"); a pessoa pode desistir explicitamente, e só então o número vira perda declarada | contrato 8.2 e 8.3 |
| D-AP-4 | `INVALID` para a entrega naquele número, sem reenvio em laço; desistência pela pessoa = perda declarada `[n, n]` | contrato 8.3 |
| D-AP-5 | O transporte só entrega o que já está **sincronizado** no disco (`durableThrough` da coleta aberta): um evento visível mas sem `fsync` confirmado não sai antes. Evita que uma queda do sistema faça a sequência ser reusada com outro conteúdo | contrato 8.1 (o progresso só depois do ACK) |
| D-AP-6 | Relatórios intermediários: se o registro gravado está vários passos adiante do último relatório aceito, a Platform recebe antes os passos que o registro **comprova** (`STARTING`; `RUNNING` só quando há `started`), com os dados do registro e sem desfecho | contrato 7 (transições do Agent) |
| D-AP-7 | Retenção atrelada ao ACK: a limpeza das execuções poupa as pendentes (até +200) e a cota de 96 MiB reduz primeiro as já liquidadas; se não houver outro jeito, a pendente também é reduzida e o transporte declara a faixa exata (`[settled+1, purged_until_sequence]`) | contrato 8.3 e 11 |
| D-AP-8 | Destino: a origem `https` do pareamento fica gravada no vínculo. Se a configuração mudar, nada é enviado ("Endereço da Platform alterado") até a pessoa decidir; redirecionamentos nunca são seguidos | contrato 4.4; missão sec. 4 |
| D-AP-9 | Recusa `401`: o transporte para **inteiro** na primeira recusa (não insiste: a API limita recusas por IP). A pessoa decide: trocar a credencial (rotação) ou remover o vínculo local e parear de novo. Entregas pendentes do vínculo anterior ficam guardadas e **não** são enviadas pelo vínculo novo (seriam atribuídas a outro `agent_id`) | contrato 4.3 e 4.4 |
| D-AP-10 | Guarda do segredo: DPAPI do usuário (JNA 5.17.0, Apache-2.0, só no pacote do transporte). Fora do Windows não há proteção do sistema compatível nesta base: o pareamento é recusado (nunca arquivo comum) | missão sec. 5; contrato 12.4 |
| D-AP-11 | Laboratório: confiança no certificado do laboratório só na CÓPIA de laboratório do Agent (armazenamento de confiança próprio no `.cfg` dela); a validação TLS continua normal. O produto não tem opção de "confiar em certificado" | missão sec. 4 |

## Encaminhamentos

### E-AP-1 (API, homologação — não bloqueia)

- **Repositório e commit:** Nekkora-Platform-API `2e639244`.
- **Operação afetada:** homologação oficial do canal da máquina pelo runner `ops/homologacao`.
- **Esperado:** o contrato (12.9) diz que a prova integrada sobe com `api/e2e/run.sh`; a missão pedia o runner com a lista de elevação aprovada.
- **Observado:** a stack de `ops/homologacao` não tem o processo `nekkora-agent-gateway` nem a rota `/agent/api/v1/` no nginx (só Platform e Portal). Um laboratório do runner não atende o Agent sem mudar a API.
- **O que foi feito aqui:** laboratório descartável `agentlab-001` com a stack E2E da própria API (sem alterá-la), migrations 001–027 em instalação limpa pelo modelo do migrador (`aplicar_como_migrador.sh`, o mesmo das bases de teste), janelas `0|0|0` conferidas. Nenhuma linha de elevação de outro ambiente foi usada.
- **Alteração necessária, se a homologação oficial do canal for desejada:** serviço do gateway e rota `/agent/api/v1/` no compose e no nginx do runner.
- **Teste de aceite:** um laboratório do runner responde `400 unsupported_contract` em `/agent/api/v1/authorizations` sem o cabeçalho, e a jornada `tools/platform-lab/jornada.ps1` passa contra ele.

### E-AP-2 (API, fixture — informativo)

A revisão de limite com `resource_code = 6` (`AGENT_EVENT_BYTES_DAILY`, 026) é recusada por `organization_resource_limit_revisions`. A fixture do laboratório provisiona 1–5, como o `AgentChannelE2E`. A ingestão funcionou sem ela. Se o recurso 6 deveria ser provisionável, a API decide.

### Fora desta missão (registrado)

Despacho, cancelamento e agendamento remotos (contrato 14, D-PA-7), pacote local sem versão publicada (contrato 13) e entrega de segredos às automações (a recusa `AGT-EXE-006` continua).
