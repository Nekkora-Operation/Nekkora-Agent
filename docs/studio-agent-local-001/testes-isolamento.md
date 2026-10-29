# Testes de isolamento do canal local (para revisão)

Há duas partes:
1. **Implementados e verdes agora**: provam que o Agent atual não tem nenhum canal. São a linha de base.
2. **Especificados para a S1 a S3**: os testes que o canal terá de passar antes de ser habilitado. Cada um tem
   procedimento e resultado esperado. Nenhum deles pode ser afrouxado para o canal passar.

## 1. Implementados (linha de base, sem canal)

| Id | Onde | O que prova | Resultado |
|---|---|---|---|
| ISO-01 | `NetworkIsolationTest.productionCodeHasNoChannel` | nenhum código de produção usa TCP, UDP, loopback, AF_UNIX, pipe nomeado, RMI, JMX remoto ou HTTP. Pega importação, nome qualificado, nome simples e curinga | verde (Windows e Linux) |
| ISO-02 | `NetworkIsolationTest.guardCatchesEveryForm` | o guarda pega 9 formas reais de canal. O guarda antigo (`ArchitectureTest`) deixava passar **8** delas (escuta e conexão AF_UNIX, `ServerSocket`, UDP, pipe, curinga, JMX e servidor HTTP) | verde |
| ISO-03 | `NetworkIsolationTest.noLocalChannelYet` | o pacote `com.nekkora.agent.local` não existe até a aprovação | verde |
| ISO-04 | `NetworkIsolationTest.packagingOpensNothing` | o empacotamento não liga JDWP, agente nem JMX, e a imagem montada não tem `jdk.jdwp.agent`, `jdk.management.agent`, `java.rmi`, `jdk.httpserver`, `java.net.http`, `java.naming` nem `jdk.net` | verde. Imagem `5fa0354e…`: 8 módulos (`java.base`, `java.datatransfer`, `java.xml`, `java.prefs`, `java.desktop`, `java.logging`, `jdk.accessibility`, `jdk.unsupported`) |
| ISO-05 | `NetworkIsolationTest.runtimeScriptCoversEveryChannel` | o roteiro de execução real cobre TCP, UDP, AF_UNIX e pipes do processo | verde |
| ISO-R | `tools/isolation/verificar-isolamento.ps1` no **Agent empacotado real** | amostra a cada 0,5 s as portas TCP (inclusive em escuta) e UDP do PID do Agent, os pontos de reparse AF_UNIX nas pastas do Agent e os pipes nomeados novos, atribuídos pelo PID no nome e sem abri-los | **0/0/0/0** com o Agent ocioso (15 s) e durante 4 execuções reais (a1, a2, a5 e a5b), com o ZIP `5fa0354e…` |
| ISO-R+ | controle positivo do ISO-R | um processo Java escutando TCP em loopback, UDP e AF_UNIX, e um PowerShell com pipe nomeado: **todos detectados** (`CANAL ENCONTRADO`) | o instrumento não dá falso negativo nesses quatro canais |

### Correções do instrumento durante a missão (registradas, sem esconder)

1. Na 1ª medição real, o ISO-R atribuiu ao Agent 20 pipes que eram de **outros programas abertos** (`mojo.<pid>` do
   Chromium, `uv\N-<pid>` do Node e `PSHost.<pid>` do PowerShell). A atribuição passou a usar o PID no nome. A
   medição foi refeita, e as tentativas ficam em `evidencias/descartadas/`.
2. Numa medição seguinte, 49 pipes de **Git Bash** (`msys-…-<pid>-sigwait` e `…-<pid>-pipe-nt-0x…`) de outra sessão
   em paralelo nesta máquina ficaram sem atribuição, porque o processo já tinha terminado. Os formatos conhecidos
   passaram a ser reconhecidos pelo PID no lugar certo. Pipe com o PID do Agent continua contando como canal, e pipe
   sem atribuição, no modo quieto, também.

## 2. Especificados para o canal (antes de habilitar)

Pré-condição de todos: o ISO-01 passa a ter **uma** exceção, restrita ao pacote `com.nekkora.agent.local` e só para
`ServerSocketChannel` com `StandardProtocolFamily.UNIX` e `UnixDomainSocketAddress`. Qualquer `InetSocketAddress`,
`InetAddress`, `ServerSocket`, `DatagramSocket` ou pipe continua proibido, inclusive nesse pacote. O ISO-03 é
substituído por essa regra.

| Id | Procedimento | Esperado |
|---|---|---|
| ISO-S01 | ISO-R com o canal **desligado** (padrão), com o Agent ocioso e durante uma execução | idêntico à linha de base: 0 TCP, 0 UDP, 0 AF_UNIX e 0 pipes, sem arquivo de descoberta |
| ISO-S02 | ISO-R com o canal **ligado** | exatamente **1** AF_UNIX (`<controle>\agent.sock`), 0 TCP, 0 UDP e 0 pipes. Porta em escuta: zero |
| ISO-S03 | um **segundo usuário** local tenta abrir o socket e ler a descoberta | acesso negado nos dois. ACL efetiva do diretório, do socket e da descoberta com um único principal (o dono) |
| ISO-S04 | cliente **sem o `secret`**, ou com provas trocadas, repetidas de uma conexão anterior ou de uma abertura anterior do Agent | `AGT-LCL-003` em até 5 s e conexão fechada. 5 falhas em 60 s bloqueiam o canal por 5 min |
| ISO-S05 | cliente com o `secret` mas **sem pareamento**: `run.request`, `run.cancel`, `run.status` | `AGT-LCL-004`. Só `pair.begin` é aceito. Nenhum registro de execução criado |
| ISO-S06 | desligar o canal e revogar o pareamento com uma conexão ativa | a conexão fecha em até 1 s, a descoberta some e o `secret` muda. Nova conexão com o `secret` antigo: recusada |
| ISO-S07 | quadro de 64 KiB + 1, quadro truncado, JSON inválido, `seq` repetido, `message_id` repetido | `AGT-LCL-010` e a conexão fecha. O Agent continua responsivo (janela e supervisão) |
| ISO-S08 | 1 000 mensagens/s por 30 s | `AGT-LCL-006`, depois o fechamento. Memória e CPU do Agent dentro da faixa medida na AGENT-LEAN-001 |
| ISO-S09 | 11 `run.request` em uma hora, e 2 pedidos simultâneos aguardando a pessoa | o 11º é recusado (`AGT-LCL-006`) e o 2º pendente também (`AGT-LCL-005`) |
| ISO-S10 | 2 clientes ao mesmo tempo | o 2º recebe `AGT-LCL-005`. O 1º não é afetado |
| ISO-S11 | sentinelas (CPF, e-mail, token, caminho de cliente, valor de planilha) em todos os campos permitidos e proibidos de `run.request` | recusa pelo schema. Nenhuma sentinela no log, no `estado.json`, na atividade nem nas respostas |
| ISO-S12 | **R1 de ponta a ponta**: pacote aprovado, pedido válido do Studio, ninguém confirma | nenhum processo lançado em 10 min. Depois, `EXPIRED`/`AGT-LCL-007`. Registro de execução **não** criado |
| ISO-S13 | **R2**: pacote não aprovado. Pedido válido, a pessoa recusa a aprovação | `REFUSED`/`AGT-LCL-008` e aprovação **não** gravada |
| ISO-S14 | **TOCTOU**: alterar um arquivo do pacote entre a confirmação e o lançamento | `BLOCKED`/`AGT-EXE-004`. O processo não é lançado |
| ISO-S15 | caminhos `\\servidor\share`, `\\?\C:\…`, `C:\a\..\b`, uma junção para fora e um relativo | `AGT-LCL-012`, sem inspeção nem leitura do destino |
| ISO-S16 | **repetição** do mesmo `run.request` (mesmo `request_id`) depois de reabrir o Agent | mesma resposta e mesmo `agent_execution_id`. Uma única execução no histórico e um único `ENGINE_STARTED` |
| ISO-S17 | `run.cancel` de uma execução iniciada pela janela | `NOT_REQUESTED_BY_THIS_ORIGIN`, e a execução segue |
| ISO-S18 | caminho de dados longo (socket com mais de 100 caracteres) | o canal fica indisponível (`AGT-LCL-013`), **sem porta TCP** (ISO-R) |
| ISO-S19 | durante uma execução, o Studio consulta `run.status` e `run.activity` em laço por 5 min | nenhuma leitura de arquivo da pasta da automação pelo canal (o canal só lê o histórico do Agent) e nenhuma falha de gravação no Framework (F-FW-04) |

Cada ISO-S terá vermelho comprovado: teste escrito antes da implementação e falhando pelo motivo certo. As evidências
seguem o padrão desta missão: execução real do ZIP, conferidor independente e SHA-256.
