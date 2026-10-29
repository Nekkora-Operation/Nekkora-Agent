# AGENT-001 — Registro de decisões

Data: 16/10/2026. Escopo: SUPER MISSÃO AGENT-001, revisão 1. Cada decisão registra o problema, a
escolha e a evidência que a sustenta. Referências lidas (sem alteração): Nekkora-Studio `beae03f`
(design system), Nekkora-Extension-Chrome `2c7c06b`, Nekkora-Platform-API `20ccb06`.

## D-01 — Arquitetura: um módulo, cinco pacotes com fronteiras testadas

Um único módulo Maven (`nekkora-agent`) é a menor estrutura que preserva as fronteiras pedidas; não
há módulos vazios para funcionalidades futuras.

| Pacote | Papel | Pode depender de |
|---|---|---|
| `core` | estado e textos (códigos de erro, estados de integração, modelo da janela, nome da máquina) | só Java |
| `storage` | diretório de dados, trava, configuração (JSON estrito, gravação atômica), `Preferences` isoladas | `core` |
| `diagnostics` | logs com rotação e limite; diagnóstico de campos fechados | `core`, `storage` |
| `os` | bandeja do sistema, validação e abertura dos atalhos | `core`, AWT |
| `app` | inicialização (ordem: diretório → trava → logs → configuração → nome) e contexto | os anteriores, sem Swing |
| `ui` | janela, diálogos e ciclo de vida na EDT | todos + design system |

`ArchitectureTest` reprova importações que cruzem essas fronteiras e o uso de rede, `Runtime.exec`
ou `InetAddress` no código de produção. Arquivos, processos e navegador rodam na thread
`nekkora-agent-io`; logs na thread `nekkora-agent-log`; a EDT só monta e atualiza a interface.

## D-02 — Design system: construção fixada, isolada e reprodutível

Não há artefato versionado publicado de `nekkora-design-tokens`/`nekkora-swing-ui`. Escolha:

- `design-system.properties` fixa repositório, commit (`beae03f16cdb72de987280e0f1e85d0a0e9852ba`),
  versão (`1.0.0-SNAPSHOT`) e módulos.
- `tools/prepare-design-system.ps1` faz um checkout novo e destacado do commit (confere o hash e que a
  árvore está limpa), apaga artefatos `com/nekkora` anteriores do repositório isolado e roda
  `mvnw -pl nekkora-design-tokens,nekkora-swing-ui -am install` com `-Dmaven.repo.local=.design-system/m2`
  e `-Dproject.build.outputTimestamp=<data do commit>` (parâmetro de linha de comando — os fontes e as
  coordenadas do Studio não mudam). Os testes do Studio não rodam nesta etapa (pertencem ao Studio e
  alguns dependem da JDK).
- O lock (`.design-system/design-system.lock.properties`) guarda commit, JDK e SHA-256 dos artefatos.
  Duas preparações seguidas produziram SHA-256 idênticos (byte a byte).
- `.mvn/maven.config` faz o build do Agent usar **somente** `.design-system/m2`; a cópia SNAPSHOT do
  `~/.m2` nunca é consultada. `DesignSystemProvenanceTest` confere que as classes carregadas vêm de
  JARs dentro de `.design-system/m2` com o SHA-256 do lock.
- O pacote final inclui os JARs: o usuário não precisa do checkout.

Por que o commit `beae03f` e não o `b0bd441` citado na missão: a `main` do Studio avançou; a única
diferença nas bibliotecas visuais é `NekkoraList` (+26 linhas, acessibilidade da STUDIO-007), sem
impacto no Agent.

## D-03 — Instância única: bloqueio do SO mantido pela vida do processo

- `agent.lock` no diretório de dados **canônico** (`toRealPath`), aberto e bloqueado com
  `FileChannel.tryLock()` (LockFileEx), mantido até a saída. A exclusão vem do bloqueio — não da
  existência do arquivo nem de PID; o arquivo nunca é apagado.
- No Windows o arquivo é aberto com `NOSHARE_DELETE`: com a trava ativa ninguém consegue apagá-lo ou
  trocá-lo para "liberar" outra instância (teste `activeLockFileCannotBeDeletedOnWindows`).
- Queda: o Windows libera o bloqueio com o handle; o próximo início adquire de imediato (teste com
  processo morto por TerminateProcess e homologação A06).
- Segunda instância: não abre logs, não lê nem grava configuração, não cria ícone; mostra um aviso
  ("O Nekkora Agent já está aberto") e sai com código 3. Não há IPC para trazer a primeira janela.
- Alcance: por usuário do Windows **e** diretório de dados. `NEKKORA_AGENT_DATA_DIR` diferente cria
  outra "instalação" (usado nos testes). Exclusividade entre sessões/usuários da máquina não é
  prometida; fica para a futura identidade/admissão de execução.

## D-04 — Configuração: versionada, mínima, estrita e sem perda silenciosa

- `config/agent-config.json`: `format`, `version` (1), `theme`, `shortcuts.platformUrl`,
  `shortcuts.studioExecutable`. Nada de senha, token, organização ou execução.
- Leitura estrita (`StrictJson`): campo desconhecido, tipo errado, UTF-8 inválido, arquivo > 64 KiB
  ⇒ `MALFORMADA` (AGT-CFG-002); `version` > 1 ⇒ `VERSAO_NAO_SUPORTADA` (AGT-CFG-003); erro de E/S ou
  diretório no lugar do arquivo ⇒ `ILEGIVEL` (AGT-CFG-004).
- Arquivo incompatível: a sessão usa padrões; o arquivo fica intocado; "Salvar" vira "Guardar cópia
  e salvar", que move o arquivo para `agent-config.json.incompativel-<data>` (sem sobrescrever cópias
  anteriores) e só então cria o novo.
- Gravação atômica: temporário no mesmo diretório + `force` + `ATOMIC_MOVE`; em falha o temporário é
  removido e o destino fica como estava. Falha ⇒ AGT-CFG-005, diálogo continua aberto com o erro, a
  configuração em uso não muda e nenhum "salvo" é anunciado.
- Primeiro início não grava configuração (só ao salvar).

## D-05 — Tema: preferência própria; `Preferences` isoladas em memória

O `ThemeManager` da biblioteca lê/grava `Preferences.userNodeForPackage(ThemeManager.class)` — no
Windows `HKCU\Software\JavaSoft\Prefs\com\nekkora\ui\theme`, o **mesmo nó usado pelo Studio**. O Agent:

- guarda o tema na própria configuração e instala com `Nekkora.install(tema)` (que não lê a
  preferência) e troca com `ThemeManager.setTheme` (que só grava após `install()` sem argumento, nunca
  chamado);
- além disso, instala `IsolatedPreferencesFactory` (memória) antes de qualquer uso de `Preferences`
  (no `main` e nas opções da JVM do pacote). Mesmo uma mudança futura da biblioteca não alcança o
  registro. A homologação compara o nó do registro antes/depois: igual, e nenhum nó do Agent criado.

Não há compartilhamento de tema com o Studio nesta versão.

## D-06 — Bandeja e ciclo de vida

- Janela visível ao iniciar; um único `TrayIcon` (dica "Nekkora Agent"; menu Abrir / Configurações… /
  Diagnóstico… / Sair; clique e duplo clique restauram).
- Fechar ⇒ oculta na bandeja; o processo continua. O aviso "continua aberto na bandeja" aparece uma
  vez por sessão (balão) e o rodapé da janela explica o comportamento.
- Sem bandeja (inexistente, falha ao instalar ou `--sem-bandeja`): aviso com código na janela, fechar
  **minimiza** na barra de tarefas (nunca esconde o único acesso), botão e Alt+S "Sair".
- Sair: remove o ícone, descarta janelas, encerra a fila de E/S, registra `LIFE_EXIT`, fecha o log,
  libera a trava e termina. Gancho de desligamento repete o fechamento (idempotente).
- Nada periódico: nenhum timer, nenhuma animação de estado (sem selos "em execução"). Medido: 0 ms de
  CPU e nenhum `javax.swing.Timer` com a janela oculta (A13).
- Sem início automático, atualização automática, serviço ou elevação.

## D-07 — Logs técnicos

- `logs/agent.log` + `agent.1.log`…`agent.3.log`; 256 KiB por arquivo, **1 MiB no total**; arquivos de
  rotação fora do padrão são removidos ao abrir.
- Linha = horário UTC, nível, evento de catálogo e campos `chave=valor` de chaves fixas; valores
  filtrados (sem quebra de linha, barras, `?`, `=`…) e limitados a 64 caracteres. De exceções, só o nome
  da classe. O código nunca passa caminho, endereço, nome da máquina ou conteúdo de configuração.
- Thread própria com fila de 512 eventos; excesso é descartado e contado (AGT-LOG-003).
- Falha ao abrir/gravar/rotacionar ⇒ registro em arquivo suspenso nesta sessão (AGT-LOG-001/002),
  aviso na janela, sem nova tentativa (nenhum laço, nenhum crescimento).

## D-08 — Diagnóstico exportável

Lista fechada (`DiagnosticReport`), com prévia exata na tela: versão/commit/origem do design system,
Java e SO, estados (bandeja, configuração, logs, diretório, trava), estados de integração, situação dos
atalhos (sem destinos), códigos de erro com contagem e horário, início/duração/tempo de inicialização.
Nome da máquina só com a opção marcada (desmarcada por padrão). O arquivo gravado é o texto da prévia.
Sem upload. Os logs não entram no arquivo.

## D-09 — Atalhos

- Platform: só `http`/`https`, com servidor, sem usuário/senha, sem `?…` e sem `#…`; aberto com
  `Desktop.browse` após clique; sem consulta, login ou sessão.
- Studio: caminho absoluto local de `.exe` existente; recusados scripts, atalhos, interpretadores
  (`cmd.exe`, `powershell.exe`, `mshta.exe`…), caminhos de rede, aspas, curingas, `%…%` e argumentos.
  `ProcessBuilder` com um único elemento (o executável) e saída descartada — sem shell.
- Sem destino válido: botão desabilitado e motivo em texto visível ao lado.
- Dependência registrada: o Studio ainda não tem distribuição `.exe` (abre por `studio.cmd`, que é
  script). Até existir um executável, o atalho do Studio fica indisponível na prática.

## D-10 — Empacotamento de laboratório

`tools/package-windows.ps1`: `mvnw clean verify` → ícone gerado da marca (`tools/IconExport.java`) →
`jlink` (java.base, java.desktop, java.prefs, java.logging, jdk.unsupported, jdk.accessibility;
conferido com `jdeps`) → `jpackage --type app-image` → licenças, LEIA-ME, `VERSOES.txt` (SHA-256 das
bibliotecas, origem do design system, runtime) → ZIP + `.sha256`. A DLL nativa do FlatLaf
(`flatlaf-3.7.2-windows-x86_64.dll`) vai em `app\lib` com `-Dflatlaf.nativeLibraryPath=$APPDIR\lib`, para
não ser extraída e carregada de `%TEMP%`. Opções da JVM: `UseSerialGC`,
`-Xmx256m`, `TieredStopAtLevel=1`, fábrica de `Preferences` isolada. Windows x64; sem assinatura
digital; não é instalador de produção. Observação: o lançador do jpackage (JDK 21) cria um processo
filho — cada instância aparece como dois `Nekkora Agent.exe` (lançador ~8 MB + JVM).

## D-11 — Base do repositório e PR

O repositório remoto estava vazio. Foi criado **localmente** um commit-base mínimo em `main`
(`.gitignore`, `.gitattributes`, README curto) e a implementação está em `feat/nekkora-agent-001`.
Nada foi publicado: a publicação da base e a abertura do PR dependem do alinhamento com a responsável.

## D-12 — Nome da máquina e diretório de dados

- Nome: `COMPUTERNAME` (Windows) ou `HOSTNAME`; sem DNS nem rede; ausente/inválido ⇒ "Não disponível".
  Nenhum identificador local é criado (não há `agent_id`/`installation_id`).
- Dados: `%LOCALAPPDATA%\Nekkora\Agent` (Studio usa `...\Nekkora\Studio\data`; a extensão não usa esta
  pasta). Substituível por `NEKKORA_AGENT_DATA_DIR` ou `-Dnekkora.agent.dataDir` (testes/homologação).
  Sem diretório utilizável o Agent não abre (AGT-DATA-001): não há como garantir instância única.
