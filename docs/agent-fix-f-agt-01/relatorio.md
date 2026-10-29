# AGENT-FIX-F-AGT-01: estado da execução quando o Windows bloqueia o `estado.json`

Autorização da titular: correção localizada no `ExecutionController` (prioridade 1). Branch local
`fix/agent-f-agt-01-001`, criada a partir de `feat/agent-package-origin-001` (`1507344`). **Sem push, PR, merge nem
publicação.** Sem comunicação Studio–Agent ou Agent–Platform. Sem suspensão nem hibernação.

## 1. O defeito (reproduzido antes da correção)

No Windows, `Files.move(…, ATOMIC_MOVE, REPLACE_EXISTING)` é negado (`AccessDeniedException`) enquanto OUTRO processo
tem o destino aberto, mesmo com compartilhamento total. Antivírus, indexador, backup ou uma pessoa abrindo o arquivo
bastam. No código anterior:

- **F-AGT-01:** a gravação de `RUNNING` acontecia DEPOIS de `ProcessBuilder.start()`, sem proteção. A exceção
  escapava de `start()` com o processo já lançado, e o resultado era:
  - nenhum dono: sem drenagem da saída, sem prazo e sem cancelamento;
  - o registro preso em `STARTING`;
  - a tela dizendo "bloqueado".
- **F-AGT-02:** o estado FINAL era gravado com `saveQuietly`, que engolia a falha. O disco ficava em `RUNNING`, sem
  nova tentativa.
- Depois de uma queda do Agent, o registro `STARTING` era fechado como `OUTCOME_UNKNOWN` sem saber do processo.
  A vaga única de execução ficava livre com o processo ainda vivo, o que permitia uma **segunda execução simultânea**.

**Vermelho antes:** os testes novos rodaram contra o código anterior (`1507344`), com 6 falhas exatamente nesses
pontos:

| Teste | Falha no código anterior |
|---|---|
| `falhaTransitoriaAoGravarRunning` | `start()` lança AGT-EXE-016 |
| `bloqueioRealNaTrocaParaRunning` | `start()` lança AGT-EXE-016 |
| `falhaPersistenteDepoisDoLancamento` | `start()` lança AGT-EXE-016 |
| `falhaTransitoriaNoEstadoFinal` | disco em `RUNNING` |
| `quedaComRegistroEmStartingEProcessoVivo` | processo vivo não adotado |
| `AtomicWriterTest.leitorLiberadoLogo` | `AccessDenied` na hora |

Os três testes-guarda passaram (`leitorQueNaoSolta`, `quedaNoMeioDaGravacao`, `escritaParcial`).

## 2. A correção

| Requisito da titular | Como fica |
|---|---|
| 1. Nada relançado por falha de persistência | Nenhum caminho novo lança processo. A reconciliação continua só ADOTANDO (o mesmo PID + início + executável) ou fechando. Testes com efeito não idempotente (`lab_efeito_curto.py`) conferem um único envio. |
| 2. Gravação incerta não é dada por concluída | O estado só conta como gravado quando `RunStore.save` volta sem erro. Enquanto isso, a tela mostra "registro não gravado" (`Snapshot.recordSaved = false`). O disco fica no ÚLTIMO estado gravado. Depois de um reinício, um registro não terminal é fechado como `OUTCOME_UNKNOWN`, nunca como sucesso. |
| 3. Processo iniciado rastreável | **Na sessão:** depois de `pb.start()` nada escapa de `start()`. A supervisão (drenagem, prazo, cancelamento) começa mesmo se a gravação falhar. **Depois de uma queda:** `processo.json` (PID, início, resumo do executável) é gravado num arquivo NOVO logo após o lançamento. Um arquivo recém-criado não pode estar aberto por outro processo. O registro fechado guarda a posse do processo. |
| 4. Recuperação controlada de falha temporária | **Gravação atômica:** a troca negada por arquivo em uso é tentada de novo 6 vezes, com espera crescente de 10 a 320 ms (~0,6 s no total). O temporário já está completo e sincronizado, então repetir a troca não muda o conteúdo. **Supervisor:** o estado em memória é regravado a cada volta (500 ms) até dar certo. **Estado final:** o supervisor insiste por 10 s; depois disso o estado fica guardado e é gravado na próxima ocasião (novo início, reconciliação, encerramento). |
| 5. Falha persistente sinalizada | A primeira falha, a recuperação e a perda do estado final viram: (a) linhas `EXEC_RECORD_DELAYED`, `EXEC_RECORD_RECOVERED` e `EXEC_RECORD_LOST` no log, com AGT-EXE-016; (b) eventos `RECORD_SAVE_*` no `eventos.jsonl`, que a Atividade mostra; (c) um aviso na janela e na linha de estado. É uma linha por mudança, não por tentativa. |
| 6. Duplicidade preservada após reinício | Um registro `STARTING` com `processo.json` válido e o processo vivo é ADOTADO: a vaga única fica ocupada e outro início é recusado com AGT-EXE-014. Com o processo morto, ou `processo.json` ausente, pela metade ou inválido, o registro vai para `OUTCOME_UNKNOWN` e não há adoção. |
| 7. Observabilidade não altera o resultado | O desfecho é decidido antes da gravação (protocolo, código de saída, prazo, cancelamento) e não muda por falha de registro. Uma falha de gravação não encerra nem cancela o processo. |
| 8. Testes de bloqueio real, queda, escrita parcial e recuperação | Ver §3. |

**Contratos:** permanecem inalterados:

- o `estado.json` (`nekkora-agent/execution` 1.0.0);
- o manifesto, a impressão digital, a aceitação e a recusa de pacotes;
- os códigos AGT-* (a sinalização reutiliza o AGT-EXE-016).

**Acréscimos internos, sem remoção:**

- **`processo.json`:** arquivo interno da pasta da execução. Só a reconciliação o lê; não vai ao diagnóstico.
- **Nomes de evento:** `RECORD_SAVE_DELAYED`, `RECORD_SAVE_RECOVERED` e `RECORD_SAVE_FAILED`, conhecidos pela
  normalização da OBS.
- **`Snapshot.recordSaved`:** o construtor antigo de 3 argumentos foi mantido.
- **Log e controlador:** três `LogEvent` e a interface `RecordDiagnostics`.

**Arquivos:**

- `ExecutionController.java`;
- `AtomicWriter.java`;
- `AgentBootstrap.java` (a ligação com o log);
- `AgentEvents.java` e `ActivityText.java` (os nomes novos);
- `ExecutionDialog.java` e `AgentApp.java` (o aviso);
- `LogEvent.java`.

## 3. Testes novos

| Teste | O que prova |
|---|---|
| `AtomicWriterTest.leitorLiberadoLogo` (Windows) | OUTRO processo (JVM auxiliar `FileHolder`) segura o arquivo, que é solto em 150 ms: a troca é concluída e nenhum temporário sobra |
| `AtomicWriterTest.leitorQueNaoSolta` (Windows) | Com o leitor segurando o arquivo, a falha é EXPLÍCITA, em menos de 3 s, com o destino intacto e sem temporário |
| `AtomicWriterTest.quedaNoMeioDaGravacao` | Um processo regrava 4 MB sem parar e é MORTO 6 vezes em instantes aleatórios: o alvo é sempre uma versão inteira |
| `ExecutionPersistenceTest.falhaTransitoriaAoGravarRunning` | A gravação de RUNNING falha 1 vez: `start()` não falha, o processo é supervisionado, o disco chega a `SUCCEEDED`, há 1 envio, o log mostra atraso e recuperação, os eventos estão em ordem e o `processo.json` está gravado |
| `ExecutionPersistenceTest.bloqueioRealNaTrocaParaRunning` (Windows) | OUTRO processo abre o `estado.json` exatamente na troca para RUNNING e o segura por 1,5 s, mais que as tentativas da gravação atômica: o supervisor assume e grava depois |
| `ExecutionPersistenceTest.falhaPersistenteDepoisDoLancamento` | O registro nunca volta: o processo roda supervisionado até o fim, o resultado em memória é o do protocolo, a perda é sinalizada e o disco fica em `STARTING`. Depois de um reinício: `OUTCOME_UNKNOWN`, com a posse registrada e 1 envio |
| `ExecutionPersistenceTest.falhaTransitoriaNoEstadoFinal` | O estado final falha 1 vez e chega ao disco |
| `ExecutionPersistenceTest.estadoFinalGravadoNaProximaOcasiao` | O estado final é perdido no prazo e sinalizado; o disco volta e o estado é gravado ANTES do novo início, com 1 envio |
| `ExecutionPersistenceTest.quedaComRegistroEmStartingEProcessoVivo` | Queda REAL: o Agent auxiliar (`CrashingAgent`, outra JVM) é morto com `destroyForcibly` depois de lançar um processo, com o disco só em `STARTING`. O novo Agent ADOTA o processo, recusa outra execução (AGT-EXE-014), cancela e grava `CANCELLED` |
| `ExecutionPersistenceTest.escritaParcial` | Um temporário pela metade não é lido como registro; um `estado.json` truncado aparece como ilegível (não some nem vira outro estado); um `processo.json` pela metade não é prova de posse (`OUTCOME_UNKNOWN`, sem adoção); nada é lançado |
| `ExecutionDesktopTest.registroNaoGravadoAvisado` (janela real) | OUTRO processo segura o `estado.json` no cancelamento: a janela e a linha de estado mostram o aviso, o desfecho continua `CANCELLED`; depois de solto, o disco grava, o aviso some e o log tem atraso e recuperação (capturas `D13`, `D14`) |

**Estabilidade:** os 10 testes não visuais foram repetidos 5 vezes seguidas, com 5/5 verdes.

## 4. Regressões

As regressões estão registradas em `matriz-regressao.md`, nesta pasta.
