# Análise de ameaças do canal local Studio → Agent (para revisão)

**Escopo:** o contrato proposto [`nekkora-agent/local-control` 1.0.0](contrato-local-control-1.0.0.md). O canal
**não existe**: esta análise vem antes da implementação, como a missão pede. Ela se baseia no código atual do Agent, no
Bridge do Studio (`da7bb94`) e em medições reais desta missão. Cada medição está marcada com **[medido]**.

## 1. O que se protege

| Ativo | Por quê |
|---|---|
| A1. A **decisão de executar** | uma automação age em sistemas reais (cliques, planilhas, envios). Uma execução indevida pode ter efeito externo irreversível |
| A2. A **aprovação** por impressão digital | é a única prova de que uma pessoa aceitou aquele conteúdo exato |
| A3. A **integridade do conteúdo executado** | o Agent roda da cópia conferida. Trocar o pacote depois da aprovação não pode chegar ao processo |
| A4. O **histórico** (`estado.json`, `eventos.jsonl`, atividade) | é a base da revisão humana e, no futuro, da Platform. Não pode mentir sobre a origem nem sobre o desfecho |
| A5. A **automação em curso** | o observador não pode derrubá-la nem alterá-la |
| A6. Os **segredos** do canal (`secret` por abertura e `pairing_key`) | permitem falar com o Agent |
| A7. A **disponibilidade** do Agent | o canal não pode travar a janela nem a supervisão |

## 2. Atores e fronteiras

```
 [pessoa] ──(janela, teclado)──► [Agent: supervisor, aprovação, confirmação]  ◄── fronteira de confiança: só a pessoa decide
                                         ▲ AF_UNIX em <dados>\controle (ACL só do dono)
 [Studio pareado] ──(local-control)──────┘
 [outro processo do MESMO usuário]  ── consegue ler arquivos do usuário (fora do alcance de qualquer ACL de usuário)
 [outro usuário da máquina] ─────── barrado pela ACL do diretório, do socket e dos arquivos
 [rede, inclusive navegador] ─────── sem nenhum ponto de entrada (sem TCP, sem loopback)
```

## 3. Ameaças (STRIDE) e mitigações

| Id | Ameaça | Cat. | Mitigação no contrato | Residual |
|---|---|---|---|---|
| T1 | **Processo malicioso do mesmo usuário** lê o arquivo de descoberta e a `pairing_key` do Studio, conecta e pede execuções | S, E | (a) Canal **desligado por padrão**. (b) R1: **nenhuma execução sem confirmação humana por pedido** na janela do Agent, com a impressão e a origem. (c) R2: o pedido nunca aprova pacote, e pacote novo exige a aprovação existente. (d) Limite de 10 pedidos por hora e 1 pendente; pedido sem ação expira em 10 min. (e) Revogação do pareamento | **Aceito e declarado.** Um processo do mesmo usuário já pode fazer quase tudo que a pessoa faz (inclusive abrir o Agent e enviar teclas). O canal não aumenta o que ele consegue executar **sem a pessoa**. O que ele ganha é poder **pedir** e encher a tela de pedidos, o que a taxa limita |
| T2 | **Outro usuário** da máquina conecta | S | ACL definida e conferida no diretório, no socket e na descoberta. AF_UNIX exige permissão de escrita no arquivo do socket. Medido na STUDIO-002 para o Bridge | baixo. ISO-S03 prova com um usuário de teste local |
| T3 | **Página web** (navegador) alcança o canal (CSRF para localhost, DNS rebinding) | S, E | **Sem TCP**: nenhuma porta, nem em loopback. O navegador não fala AF_UNIX. Esta é a diferença deliberada em relação ao Bridge (P-03) | nulo enquanto não houver TCP. ISO-R prova que não há porta, e o ISO-S02 prova o mesmo com o canal ligado |
| T4 | **Repetição** de um handshake ou de um pedido capturado | S, T | provas sobre os dois nonces da conexão, `server_nonce` novo a cada conexão, `secret` novo a cada abertura, `seq` estritamente crescente, `message_id` único na sessão e `request_id` idempotente **persistido** (a repetição devolve o mesmo resultado, nunca uma segunda execução) | baixo |
| T5 | **Troca do pacote** entre o pedido e a execução (TOCTOU) | T | R6: impressão calculada pelo Agent na inspeção. A pessoa confirma **essa** impressão. No lançamento, a conferência atual refaz os hashes e recusa a divergência (`AGT-EXE-004`). O processo roda da cópia conferida (prova real: a3 da compat-001) | baixo |
| T6 | **Pedido forjado com parâmetros perigosos** (caminho de rede, `..`, junção, argumentos) | T, E | schema fechado (sem texto livre, sem argumentos). Caminhos absolutos locais validados; ligação ou junção recusada na conferência (`AGT-LCL-012`). Comando montado pelo adaptador, como hoje. O teste do contrato recusa UNC, `\\?\`, `..`, relativo e argumentos | baixo |
| T7 | **Mentira de origem**: o histórico diz "pedido pelo Studio" para um pacote escrito à mão, ou "gerado pelo Studio" | R | R5: `origin` é a origem **do pedido** (pareamento), e a procedência do pacote continua "Origem não verificada · gerador declarado" (AGENT-PACKAGE-ORIGIN-001). A tela mostra as duas coisas separadas | baixo |
| T8 | **Negação de serviço**: quadros enormes, muitas conexões, muitas mensagens, pedidos em massa, atividade gigante | D | 1 conexão, quadro de 64 KiB, 20 mensagens/s, 10 pedidos/h, 1 pendente, página de 500 ou 1 MiB, bloqueio de 5 min após 5 falhas de autenticação, prazos de handshake e de inatividade. O leitor nunca aloca além do limite. A interface nunca bloqueia: o canal roda em thread própria e a confirmação é assíncrona | baixo. ISO-S07 a S10 medem |
| T9 | **Vazamento** pelo canal (caminhos, valores da automação, segredos) | I | o canal só devolve o registro e os envelopes que já excluem valores (OBS-001). Caminhos só no pedido, nunca nas respostas, e a tela mostra nomes curtos. O `secret` e a `pairing_key` nunca vão para logs | baixo. ISO-S11 procura sentinelas |
| T10 | **O observador derruba a automação** | D (A5) | **[medido]** no Windows, ler o `checkpoint.json` enquanto o Framework grava faz o `os.replace` falhar: 74% a 77% das gravações em laço, com 0% sem leitor (F-FW-04). A S0 desta missão garante que o Agent só lê o checkpoint com o processo **já terminado**. O canal **nunca** lê arquivos da automação por pedido do Studio; só lê o que o Agent já registrou | residual no **Framework** (F-FW-04): qualquer outro leitor (antivírus, backup) ainda pode derrubá-lo. Recomendada uma missão no Framework (nova tentativa no `atomic_write`) |
| T11 | **Desfecho mal traduzido** no Studio: `OUTCOME_UNKNOWN` lido como falha e repetido, ou parcial lido como sucesso | T | `OUTCOME_UNKNOWN` sempre explícito (nível da execução). `progress` só existe sem resultado final, e o schema proíbe `progress` com `SUCCEEDED`. `recovered` marca o desfecho lido depois de um reinício. Repetir é decisão humana no Studio, nunca automática | médio até a S4 (o cliente no Studio) existir e ser homologado |
| T12 | **Socket indisponível ou caminho longo** leva alguém a "resolver" com TCP | E | P-03: sem fallback. `AGT-LCL-013` fica visível. **[medido]** o AF_UNIX recusa caminhos longos ("Unix domain path too long") | baixo, se a regra for mantida na revisão |
| T13 | **Ligar o canal sem perceber** (padrão, importação de configuração, outro processo editando o `config.json`) | E | desligado por padrão. Ligar só pela janela, com registro no log. Um `config.json` alterado por fora que ligue o canal fica, na 1.0.0, **sem efeito até a pessoa confirmar na janela** (proposta: o ajuste exige uma confirmação gravada com o instante e a versão do Agent) | médio. Decisão de produto na S1 |
| T14 | **Pareamento sem a pessoa** (o código curto é aceito sozinho) | S | código de 6 dígitos mostrado nos dois lados e confirmação **na janela do Agent**, com "Recusar" como padrão e prazo de 2 min. Um processo do mesmo usuário poderia clicar por automação de interface (T1) | igual a T1 |
| T15 | **Canal ativo depois de revogar ou desligar** | E | revogar ou desligar fecha a conexão na hora, apaga a descoberta e troca o `secret` | baixo. ISO-S06 |

## 4. O que o canal **não** faz (fora do escopo, de propósito)

- Não aprova pacote, não executa sem a pessoa e não tem "sempre permitir".
- Não fala com a rede nem com a Platform.
- Não entrega segredos: `AGT-EXE-006` continua.
- Não muda o envelope de observabilidade 1.0.0 nem os estados da execução.
- Não lê nada da pasta da automação a pedido do Studio.

## 5. Medições que embasam esta análise (desta missão)

| Medição | Resultado | Evidência |
|---|---|---|
| ISO-R no Agent empacotado `5fa0354e…`, ocioso e durante execuções reais (a1, a2, a5 e a5b) | **0 portas TCP, 0 UDP, 0 AF_UNIX e 0 pipes do Agent** em todas as amostras | `evidencias/real/*/…-isolamento-*.json` |
| Controle positivo do ISO-R | TCP em loopback, UDP, AF_UNIX e pipe nomeado **detectados** | `evidencias/isolamento/controle-*` |
| Atribuição de pipes | numa máquina em uso aparecem pipes novos de Chromium, Node, PowerShell e Git Bash. Atribuídos pelo PID no nome, nenhum era do Agent | idem |
| Limite de caminho do AF_UNIX | caminho longo recusado pelo sistema | controle positivo, 1ª tentativa |
| F-FW-04 | leitor concorrente faz 74% a 77% das gravações do checkpoint falharem; sem leitor, 0% | `evidencias/f-fw-04/` |
| Guarda estático antigo | deixava passar 8 formas de canal (ISO-02) | `NetworkIsolationTest` |
