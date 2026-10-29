# NEKKORA-DESKTOP-001 — Auditoria do roteiro físico G2/A13/OBS-G6

Resposta ao item 5 da revisão de 23/10 ("inspecionar o `.ps1` e a classe Java que ele chama; não alegar que o script não
tem efeitos inesperados sem examinar o código"). O ZIP entregue para revisão não trazia os fontes. Esta auditoria lista
os arquivos que o roteiro executa, com o SHA-256 de cada um, e todos os efeitos que eles têm sobre a máquina. O pacote de
fontes para conferência independente é gerado pelo comando da §5.

## 1. Arquivos executados (no commit que introduziu esta auditoria)

| Arquivo | Papel | Linhas | SHA-256 (conteúdo no `git archive`, igual em qualquer sistema) |
|---|---|---|---|
| `tools/homologation/fisica-g2-a13.ps1` | roteiro | 383 | `b438e4bba1b586e4bfbf1fe46ffae2a2888ca3514d01c5f85d0d9577742b4751` |
| `tools/homologation/NkHomolog.psm1` | janelas, bandeja, teclado e mouse | 193 | `1caac1f179e7e3bc97ce423eaa7bfedbc63583169b05c5f4abd9f1c1e949054f` |
| `tools/homologation/wincap.ps1` | captura das janelas P-* | 51 | `2f79a46256c9155d0cb098b31ebb334f609e3de7db1904e69706e268d43180ff` |
| `src/test/java/com/nekkora/agent/ui/DesktopHomologation.java` | semear-obs e sessao-cenario | 619 | `f6cdc7a22230327ff32825d7f8c23e279f76617010a683e06a32dee26309a021` |
| `src/test/java/com/nekkora/agent/ui/DesktopHarness.java` | abre o Agent real na JVM do cenário | 237 | `ac6bf6066e1f8c9048a6958804dab0d3badeee7d47d3e4c9bb26e2fdbcfec6c5` |
| `tools/lab/scripts/lab_comum.py` | apoio dos laboratórios | 39 | `3f811281835647cfab5d9e50477ee02ea6b544e952047799d76742f276fcd9a2` |
| `tools/lab/scripts/lab_sessao_sucesso.py` | S1 | 11 | `fe3c095b18d764f25276760b4ae3ca1fc5a1538d6f55d42eac061afd65cfba7c` |
| `tools/lab/scripts/lab_sessao_sem_protocolo.py` | S2 | 11 | `e693166be18de80d72de73fe07c4d5909d8f25303fdd796c4c2216722d87bb53` |
| `tools/lab/scripts/lab_sessao_falha.py` | S3 | 14 | `1df4e5b0d892de8aae0fbe9f51aba232a436edc0048325cd9cdd9c01b22dcb37` |
| `tools/lab/scripts/obs_studio_main.py` | P (eventos reais do nekkora_rpa) | 109 | `784ebdbb56ec7a3137c058fdb6639baa5d4ab5534b53ae1dd0fb3b67be8fc609` |

Nenhum outro arquivo do repositório é executado pelo roteiro. O executável do Agent vem do ZIP de `target\dist`, que o
roteiro confere pelo `.sha256` antes de extrair (primeira verificação).

## 2. O que o roteiro NUNCA faz (varredura dos 10 arquivos acima)

Nenhuma ocorrência de: `LockWorkStation`, `SetSuspendState`, `SetSystemPowerState`, `powrprof`, `rundll32`,
`ExitWindows`, `InitiateSystemShutdown`, `shutdown`, `Restart-Computer`, `Stop-Computer`, `WTSDisconnect*`; nenhum acesso
de rede (`Invoke-WebRequest`, `Invoke-RestMethod`, `WebClient`, `HttpClient`, `urllib`, `requests`, `socket`); nenhuma
escrita no registro do Windows nem tarefa agendada (`Set-ItemProperty`, `reg add`, `schtasks`). O bloqueio, a suspensão e
o desbloqueio só acontecem pelas mãos da pessoa; o roteiro apenas OBSERVA (processo `LogonUI` da sessão e salto do relógio
de parede contra o cronômetro). O único comando de energia é `powercfg /a`, que só LISTA os estados disponíveis.

A URL `https://cliente.example/api?token=abc9Z` em `obs_studio_main.py` é um texto SINTÉTICO de canário (domínio reservado
`.example`): o Framework precisa ocultá-lo nos eventos. Não há acesso a ela.

## 3. Efeitos que o roteiro TEM, um por um

| Efeito | Onde | Alcance |
|---|---|---|
| Lê `powercfg /a` | `fisica-g2-a13.ps1` §0 | somente leitura |
| Passa o mouse sobre ícones "Nekkora Agent" da bandeja (remove ícones fantasmas) e clica em "Mostrar ícones ocultos" | `Clear-GhostTrayIcons`, `Open-TrayOverflow` | só antes de começar; mouse fica sobre a bandeja |
| Envia `ESC` à janela em foco (fecha o estouro da bandeja depois de contar ícones) | `Get-TrayIconCount` | o roteiro foca antes a janela do Agent empacotado; só com a tela desbloqueada |
| Traz janelas do Agent para a frente (toque de `Alt` + `SetForegroundWindow`) | `ForceForeground` | só janelas dos processos que o roteiro abriu |
| Envia `Alt+E` (Execução local), `Alt+T` (aba Atividade), `ESC` (fecha o diálogo) e `Alt+S` (Sair) | `Open-Activity` e o fim do roteiro | depois de focar a janela do Agent empacotado; por isso não usar o computador durante a rodada |
| Envia `WM_CLOSE` à janela de aviso da segunda abertura | `Close-Window` | só a janela "O Nekkora Agent já está aberto" do processo novo |
| Captura `P-1-antes`, `P-2-depois`, `P-3-segunda-abertura-recusada` copiando os pixels da tela no retângulo da janela | `wincap.ps1` | a janela é trazida para a frente antes (corrigido nesta auditoria, ver §4); com "Não incomodar" desligado uma notificação ainda poderia cobrir; revisar as 3 imagens antes de compartilhar |
| Capturas `S-*.png` dos cenários | `DesktopHarness.capture` | a janela pinta a si mesma numa imagem (sem câmera de tela): nada de fora entra |
| Altera `PATH`, `JAVA_HOME`, `COMPUTERNAME` (`LAB-AGENT-01`), `NEKKORA_AGENT_DATA_DIR` | `Start-Agent`, `DesktopHarness.start` | só no processo do roteiro, e restaurados logo depois de abrir o Agent; nada persistente |
| Extrai o ZIP do Agent e grava dados, saídas e efeitos | `<Out>\bruto` | só dentro de `<Out>` (padrão: pasta temporária com data e hora); nunca no Agent instalado da pessoa nem em `%LOCALAPPDATA%\Nekkora` |
| Abre processos: o Agent extraído (2 processos), 1 `java` por cenário (3) e os laboratórios Python (P e S1–S3, 2 processos cada pelo lançador do venv) | §1 e §2 do roteiro | o Python é o do venv do Framework; os laboratórios só escrevem em `<Out>` |
| Encerra processos à força | só no `trap` (erro inesperado) | apenas: o `Nekkora Agent.exe` cujo caminho é a cópia extraída em `<Out>`; os 3 `java` dos cenários que ele mesmo abriu; e processos cuja linha de comando tem `--output` E a pasta `<Out>\bruto` (os laboratórios). Nenhum outro processo é tocado |
| Fim normal | §5 do roteiro | o Agent empacotado sai pelo próprio menu (`Alt+S`), sem encerramento forçado |
| Reescreve as evidências trocando usuário, máquina e caminhos por `<local>`/`<caminho>`, e reprova se sobrar algum | §6 | só em `<Out>\evidencias` |

Pré-condições que IMPEDEM o roteiro de começar (sai com código 2 sem abrir nada): SHA-256 do ZIP diferente, qualquer
`Nekkora Agent.exe` já aberto, ícone do Agent na bandeja, Python inexistente.

## 4. Defeitos achados por esta auditoria (corrigidos)

1. **Limpeza incompleta em caso de erro.** O `trap` encerrava o Agent extraído e os `java` dos cenários, mas não os
   laboratórios Python. O Windows não encerra os filhos de um processo encerrado, então a execução semeada (P) e os 3
   laboratórios continuariam vivos por cerca de 6 minutos, gravando só em `<Out>`. Agora o `trap` também encerra os
   processos com `--output` dentro de `<Out>\bruto`. **Prova com falha injetada** numa cópia do roteiro, logo depois de
   P adotada e S1–S3 em andamento: saiu com código 2 e restaram **0** processos com a pasta do teste na linha de comando
   e **0** `Nekkora Agent.exe`; cada cenário registrou o efeito
   **1×**.
2. **Captura de tela com possível sobreposição.** `P-*.png` copiam os pixels da tela; se outra janela estivesse por cima
   naquele instante, ela entraria na imagem. Agora a janela capturada vem para a frente antes da cópia.
3. **Python com caminho relativo (achado pelo ensaio da auditoria).** O guia ensinava
   `$py = "..\Nekkora-RPA-Framework\.venv\Scripts\python.exe"`; o ensaio anterior tinha usado o caminho absoluto. Com o
   relativo, o ensaio reprovou com 9 falhas: o registro semeado guardou o interpretador relativo, o Agent empacotado não
   conseguiu confirmar que o processo vivo era o da execução e, sem prova, **não o adotou nem relançou nada**: fechou P
   como `OUTCOME_UNKNOWN` (`AGT-EXE-025`, `adopted=false`). O produto se comportou como deve; o defeito era do roteiro, e
   na rodada física teria reprovado tudo. Os cenários S1–S3 também não chegaram a "em andamento". Agora o roteiro
   converte `-Python` em caminho absoluto logo depois de conferir que ele existe.
4. **O roteiro travava arquivos que o produto estava gravando (achado pelo ensaio seguinte).** Com o defeito 3
   corrigido, o ensaio reprovou numa única verificação: 964 eventos do Framework importados contra 968 linhas
   originais. Faltavam as sequências 58–61 no `atividade.jsonl`, gravadas em 17:18:04–06, exatamente quando o roteiro
   lia o arquivo para o instantâneo `execucoes-iniciadas`. `[IO.File]::ReadAllLines` abre o arquivo recusando escrita
   concorrente, então o acréscimo do Agent naquele instante falhou. O mesmo valia para o `estado.json`: `Get-Content`
   não aceita exclusão compartilhada, e o `AtomicWriter` troca o arquivo com `Files.move`, sem nova tentativa. Uma
   coincidência com a leitura que o roteiro faz a cada segundo faria falhar uma gravação do registro do supervisor;
   não aconteceu neste ensaio (nenhum aviso no `agent.log`), mas o risco era real. Agora todo arquivo vivo (registro,
   atividade, fases dos cenários) é lido com leitura, escrita e exclusão compartilhadas, com nova tentativa curta.

   **Comportamento do produto nesse ensaio: correto, e é uma prova real do OBS-R2.** A aba do Agent empacotado mostrou
   "Linha do tempo: INCOMPLETA", o aviso "Lacuna no histórico (`AGT-OBS-013`)" e o título "Linha do tempo
   (incompleta)", com "119 iniciadas · 119 concluídas" (a transação perdida não foi inventada). A captura está guardada
   fora do repositório; o ensaio inteiro foi substituído pelo da §4.

   **Decisão da titular (23/10): correção AUTORIZADA como OBS-PERSISTENCE-HARDENING**, implementada no commit
   `5c3fd6e` (só a persistência da telemetria local: `ActivityLog`, saúde/completude, aviso na tela e diagnóstico no
   log; `ExecutionController`, envelope, Framework, Studio e Platform intactos). Descrição em `observabilidade.md`
   §4.1; resultados em `relatorio.md` §8.1.

Ensaio completo do roteiro corrigido: ver `homologacao-fisica.md` §4.

## 5. Pacote de fontes para a revisão independente

Na raiz do repositório:

```powershell
git archive -o desktop-001-fontes-roteiro.zip HEAD tools/homologation tools/lab src/test/java/com/nekkora/agent/ui/DesktopHomologation.java src/test/java/com/nekkora/agent/ui/DesktopHarness.java docs/desktop-001/auditoria-roteiro-fisico.md docs/desktop-001/homologacao-fisica.md
```

## 6. Cuidados para a rodada física (reforço)

- Máquina livre, documentos salvos, "Não incomodar" ligado, senha de desbloqueio à mão.
- Fechar o Nekkora Agent instalado antes (o roteiro recusa começar com um aberto).
- Não usar teclado nem mouse do Enter até o `RESULTADO`, exceto para Win+L, desbloquear, suspender e retomar.
- Antes de compartilhar, abrir as 3 imagens `P-*.png`.
- Se qualquer verificação falhar, o `resultado.txt` registra o resultado real. O ensaio não substitui a rodada física.
