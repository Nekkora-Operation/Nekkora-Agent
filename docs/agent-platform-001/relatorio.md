# AGENT-PLATFORM-001 — transporte durável Agent → Platform e integração real

## Resultado

Uma automação executada pelo **Agent empacotado real** aparece na **Platform API real** com identidade, eventos, desfecho e
completude corretos. O transporte sobreviveu a uma interrupção de comunicação, a um ACK perdido (falha injetada e
identificada) e a um reinício do Agent com a automação rodando, sem duplicar fatos nem repetir a automação. A revogação foi
respeitada: a primeira chamada autenticada seguinte foi recusada, o transporte parou e a execução local continuou.

| | Valor |
|---|---|
| Agent, candidato homologado | `e4e5617c5efc43c437ac9623a15a46ac4870a375` (build empacotado, `VERSOES.txt` conferido pela jornada). Commits seguintes só mudam ferramentas e documentos |
| API usada | `Nekkora-Platform-API` `main` @ `2e639244c22e0f439860344ac46557896431f51a` (contém o canal da 025 e as migrations 026 e 027) |
| Contrato | `2026-10-28-platform-agent-contrato.md` sha256 `c7f4e7f9…63410f7`: byte a byte igual à referência fixa `7d30c24` (ver `inventario.md`) |
| Laboratório | `agentlab-001`, descartável, stack E2E da própria API (sem alterá-la), 001–027 em instalação limpa, janelas do migrador `0|0|0`. Parado no fim; containers e volumes preservados |

## Jornada integrada (evidências em `evidencias/jornada` e `evidencias/jornada-retomada-j8`)

Rótulos: **[REAL]** processo e API reais; **[INJETADA]** falha controlada identificada. Nada da jornada usa transporte simulado.

| Passo | O que foi provado | Fonte |
|---|---|---|
| J1 [REAL] | Agent empacotado do commit candidato, sem Java no PATH; a cópia de laboratório confia só no certificado do laboratório (validação TLS normal) | linha do tempo, `j1-nao-pareado.png` |
| J2 [REAL] | Pareamento pela janela: código digitado no campo de senha e arquivo apagado; `PLATFORM_PAIRED`; `vinculo.json` sem segredo e credencial só em DPAPI; Agent `ACTIVE` associado ao Worker no banco; 2 autorizações baixadas | `j2-pareado.png`, `resultado.json` |
| J3 [REAL] | Execução local real (pacote de laboratório no formato do Studio, eventos reais do `nekkora_rpa` 0.3.0, dados fictícios), aprovada pela pessoa; a autorização da impressão vem pré-escolhida; vínculo e registro na Platform | `j3-pronto-com-autorizacao.png`, `j3-em-andamento.png` |
| J4 [REAL] | nginx do laboratório pausado com a automação rodando: `PLATFORM_DISCONNECTED AGT-PLT-010`; a automação seguiu `RUNNING`; a entrega retomou | `j4-sem-comunicacao.png` |
| J5 [INJETADA] | Trava `FOR UPDATE` da linha da execução + nginx pausado: o gateway gravou e a resposta se perdeu; o reenvio devolveu `DUPLICATE` (contagem no `PLATFORM_ACK`) | linha do tempo |
| J6 [REAL] | Processo do Agent encerrado à força; reaberto, adotou a MESMA automação (`EXEC_RECONCILED`, uma só raiz de processo: o filho é o lançador do venv) e retomou a entrega de `settledThrough + 1` | `resultado.json` |
| J7 [REAL] | Fim pelo supervisor `SUCCEEDED`; envio concluído. Banco: `stored = settled = eventos = 1936` (igual ao último local), 0 recusas, 0 ausências, 1 registro, 1 pasta de saída. Relatórios: `RUNNING:APPLIED`, `RUNNING:APPLIED` (adoção), `SUCCEEDED:APPLIED`. Consulta da pessoa: `SUCCEEDED / ENDED / KNOWN`, histórico `COMPLETE` sem motivos, retenção `AVAILABLE`, eventos 1..1936 em ordem e sem repetição | `j7-consulta-platform.json`, `j7-automacao-e-envio-concluidos.png` |
| J8 [REAL] | Agent revogado pela pessoa (step-up): a primeira chamada autenticada foi recusada (`AGT-PLT-008`) e nenhuma outra foi tentada (recusas no gateway: 1 → 1). Uma execução local seguinte terminou `SUCCEEDED`, sem registro e sem vínculo de entrega | `j8-credencial-recusada.png`, `jornada-retomada-j8` |
| J9 | Nenhum segredo (código, credencial, senha e fator da pessoa) em evidências, dados, logs dos 5 serviços nem envelopes | `resultado.json` |

**Rodada final:** 21/22 conferências na jornada e 4/4 na retomada. A falha da rodada foi do roteiro: o pacote curto do J8 não
tinha sido aprovado naquela pasta de dados. O Agent manteve "Iniciar" desabilitado, o controle de aprovação local já
existente. A retomada (`-SomenteLocalDepoisDaRevogacao`), sobre os mesmos dados e com o vínculo ainda recusado, provou a
parte que faltava.

**Tentativas anteriores** (laboratório e roteiro; nenhuma mudou fatos da API): o certificado do laboratório foi renovado
uma vez, porque o nginx parado não reinicia sem a chave, que é apagada depois da subida. Foram descobertos e corrigidos:

- o atalho Alt+R, capturado globalmente pelo NVIDIA Overlay desta máquina (o botão passou a Alt+A, com teste de desktop);
- uma cópia velha do Agent que a jornada abriu por não conseguir apagá-la (a jornada agora confere o commit do build);
- conferências de banco com coluna ambígua;
- a paginação da consulta (E-AP-3).

Cada nova jornada revogou o Agent anterior pela Platform (`pessoa.py novo-codigo`).

## Testes do candidato

| Suíte | Resultado |
|---|---|
| Windows `mvnw clean verify` (build empacotado `e4e5617`) | 278 testes, 0 falhas, 0 erros, 3 ignorados (ISO-04 antes da imagem montada; vetor real sem a variável; `SourceFileTest`, já existente) |
| Vetor real `5664f1a5…` com `NEKKORA_REAL_PACKAGE` | 3/3 (0 ignorados) |
| Desktop `-Pdesktop clean verify` | ver `matriz-testes.md` |
| Linux (Docker) | ver `matriz-testes.md` |
| Prova vermelho/verde (4 controles desligados de propósito) | 4/4 vermelhos, verde depois de reverter (`evidencias/prova-vermelho-verde.txt`) |

Testes novos do transporte (simulado = `FakeGateway`, que reproduz a semântica do gateway no commit da API; falhas injetadas
identificadas): `PlatformPairingTest` (8), `PlatformDeliveryTest` (13), `JdkHttpTransportTest` (3, HTTPS real local),
`DpapiSecretStoreTest` (2), `PackageFingerprintVectorTest` (3), `DeliveryRetentionTest` (1), `DeliveryQuotaTest` (2),
`PlatformDesktopTest` (1, desktop), contraprova arquitetural `networkIsRefusedOutsideTheTransport`.

## Arquitetura (resumo)

`com.nekkora.agent.platform` é o único pacote com rede (`java.net.http`; HTTPS, validação normal, sem redirecionamento,
10 s de conexão, 30 s de resposta, 1 MiB de resposta). Ele não chama o executor nem é chamado por ele: lê o `estado.json`
pelo `RunStore` e o `atividade.jsonl` (o mesmo histórico da tela, sem segundo histórico) e grava só em `<dados>/plataforma`
(`vinculo.json`, `autorizacoes.json`, `entregas/<execução>.json`, segredos em DPAPI). Uma linha de fundo
(`nekkora-agent-plataforma`) faz resgate pendente → presença (60 s) → autorizações (5 min) → entregas. A tela só lê o retrato.
Decisões nos limites do contrato: `decisoes-e-encaminhamentos.md`.

## Limitações

- **DPAPI só no Windows.** Nos demais sistemas o pareamento é recusado.
- **Proxy.** Não há proxy HTTP: a conexão é direta.
- **JNA.** O JNA extrai a biblioteca nativa para a pasta temporária do usuário na primeira proteção.
- **Rotação na jornada real.** A rotação foi provada contra o transporte simulado (P3, P4, P5) e não na jornada real.
- **Retenção no servidor.** Nenhum expurgo foi ligado (D-RET-1 continua desligado).
- **Execução só local.** Continua permitida com a máquina pareada (D-AP-1, para confirmação da titular).
- **Comunicação depois de uma recusa 401.** O card mostra "Conectado", porque a recusa foi uma resposta do servidor. O vínculo aparece como "Credencial recusada" na mesma tela.

## Operação

`tools/platform-lab/README.md`.
