# Matriz de autorização do canal local (para revisão)

Contrato: [`nekkora-agent/local-control` 1.0.0](contrato-local-control-1.0.0.md). Esta matriz vale para o canal **quando
existir**. Hoje o único ator é a pessoa na janela do Agent.

**Legenda:**
- **Sim**: permitido;
- **Não**: recusado com o código indicado;
- **Pessoa**: só acontece depois de uma ação humana na janela do Agent;
- *Studio pareado*: conexão autenticada com o `secret` da abertura **e** a `pairing_key` de um pareamento ativo.

## 1. Quem pode o quê

| Operação | Pessoa na janela do Agent | Studio pareado (canal ligado) | Conexão autenticada sem pareamento | Qualquer outro (sem `secret`, outro usuário, rede) |
|---|---|---|---|---|
| Ligar ou desligar o canal | Sim | Não | Não | Não (não há canal) |
| Parear um Studio | **Pessoa** (código de 6 dígitos e Parear) | — | só `pair.begin` | Não (`AGT-LCL-003`) |
| Revogar um pareamento | Sim | Não | Não | Não |
| **Aprovar um pacote** (impressão digital) | Sim (fluxo atual) | **Não**: não existe mensagem para isso (R2). O pedido abre o fluxo de aprovação para a **Pessoa** | Não | Não |
| **Iniciar uma execução** | Sim (Iniciar) | **Pessoa**: `run.request` leva a `AWAITING_CONFIRMATION`, e só "Iniciar esta execução" na janela inicia (R1) | Não (`AGT-LCL-004`) | Não |
| Desistir de um pedido ainda não iniciado | Sim (Recusar) | Sim, só pedidos **dele** (`request.withdraw`) | Não | Não |
| Consultar um pedido | — | Sim, só pedidos **dele** (`request_id` que ele criou) | Não | Não |
| Consultar o estado de uma execução | Sim | Sim, **qualquer** execução do histórico (o registro já exclui valores e caminhos) | Não | Não |
| Ler a atividade (envelopes 1.0.0) | Sim | Sim, qualquer execução | Não | Não |
| **Cancelar uma execução** | Sim, qualquer uma | Sim, só as que **ele** pediu (`origin.pairing_id` igual ao da sessão). Outra: `NOT_REQUESTED_BY_THIS_ORIGIN` | Não | Não |
| Exportar a atividade para arquivo | Sim | Não (o canal não grava arquivos) | Não | Não |
| Mudar os ajustes do Agent | Sim | Não | Não | Não |
| Fornecer segredos à automação | Não (`AGT-EXE-006`) | Não | Não | Não |

## 2. Condições de cada pedido de execução (todas obrigatórias)

| # | Condição | Falhou → |
|---|---|---|
| C1 | canal ligado pela pessoa | sem socket (`AGT-LCL-001` só para quem tem a descoberta antiga) |
| C2 | handshake válido (`secret` desta abertura, nonces novos) | `AGT-LCL-003` |
| C3 | pareamento ativo e não revogado | `AGT-LCL-004` |
| C4 | mensagem dentro do schema (`seq`, `message_id`, campos fechados) | `AGT-LCL-010` |
| C5 | dentro dos limites (taxa, pendentes, conexões) | `AGT-LCL-005` ou `AGT-LCL-006` |
| C6 | `request_id` novo, ou repetido com o **mesmo** conteúdo | `AGT-LCL-009` |
| C7 | caminhos absolutos locais, sem rede, dispositivo, `..`, ligação nem junção | `AGT-LCL-012` |
| C8 | pacote inspecionado: hashes, tipo e sem segredos | `AGT-EXE-001` a `006` |
| C9 | impressão calculada **pelo Agent** igual à `expected_fingerprint`, se vier | `AGT-EXE-004` |
| C10 | impressão **aprovada** nesta máquina, ou aprovada agora pela **Pessoa** | `AWAITING_APPROVAL`, depois `AGT-LCL-008` ou `AGT-LCL-007` |
| C11 | Python com a versão exata do Framework do manifesto | `AGT-EXE-008` ou `009` |
| C12 | entrada e saída válidas (regras atuais) | `AGT-EXE-010` ou `011` |
| C13 | **confirmação da Pessoa para este pedido** | `AWAITING_CONFIRMATION`, depois `AGT-LCL-008` ou `AGT-LCL-007` |
| C14 | sessão interativa desbloqueada e nenhuma execução ativa | `AGT-EXE-012`, `013` ou `014` |
| C15 | no lançamento, a impressão ainda é a **confirmada** | `BLOCKED`/`AGT-EXE-004` |

## 3. O que fica registrado (D-SA-05)

| Situação | `origin.kind` | Outros campos | Tela |
|---|---|---|---|
| Iniciada pela janela (hoje) | `LOCAL_UI` | — | "Pedido por: Janela do Agent (pessoa operadora)" |
| Iniciada por pedido do Studio | `STUDIO_LOCAL` | `pairing_id`, `request_id` (1.2.0) | "Pedido por: Studio nesta máquina (canal local)", mais o pareamento |
| Registro 1.0.0 (anterior) | `UNKNOWN` | — | "Não registrada (execução anterior a esta versão)" |
| Procedência do pacote (sempre) | — | gerador **declarado** | "Origem não verificada · gerador declarado pelo pacote: …" (não muda com o canal) |
| Cancelamento | — | `cancellation.source` (`LOCAL_UI`, `AGENT_SHUTDOWN` ou `STUDIO_LOCAL`) e `outcome` | "Confirmado · pedido por … às …" |
