# AGENT-001 — Matriz de homologação A01–A15

Data: 16/10/2026. Código de produto homologado: commit `2815a18` (o HEAD `bb445db` só altera o script de
preparação). Pacote homologado: `nekkora-agent-0.1.0-windows-x64-lab.zip`, SHA-256
`2a855a721c094c44cda7c22d3e398883b441accad3905e2983d0396b51e4c39d`.

**Ambiente:** Windows 11 Pro 10.0.26200 (64 bits), AMD Ryzen 7 8700F (8 núcleos/16 lógicos), 31,2 GiB,
tela 1920×1080 a 100%; build com Microsoft Build of OpenJDK 21.0.12 (x64); pacote com runtime próprio.

**Tipos de evidência:** **Auto** = teste automatizado sem tela (`mvnw verify`); **Desktop** = teste
automatizado em desktop Windows real dentro da JVM de teste (`mvnw -Pdesktop verify`: janela, bandeja
do sistema, `Robot`, arquivos reais); **Pacote** = roteiro sobre o `.exe` empacotado
(`tools/homologation/homologate-package.ps1`: processo real, PATH sem Java, UI Automation, teclado e
mouse sintéticos); **Simulado** = dublê de teste; **Não exercitado** = sem execução.

| ID | Cenário | Resultado | Tipo | Evidência |
|---|---|---|---|---|
| A01 | Construção limpa com dependências fixadas | **Aprovado** | Auto + manual | Clone novo do branch + `prepare-design-system.ps1 -Clean` (GitHub, repositório Maven vazio) + `mvnw verify`: 108/108. SHA-256 dos JARs do design system idênticos entre preparações (3 execuções, byte a byte). `DesignSystemProvenanceTest`: classes carregadas de `.design-system/m2` com o SHA do lock. Achado: checkout incompleto em pasta profunda (limite de 260 caracteres) — barrado pela verificação de árvore limpa do script e corrigido com `core.longpaths` (`bb445db`). |
| A02 | Primeiro início | **Aprovado** | Desktop + Pacote | Janela 540×683 dentro da área útil, um ícone na bandeja, estados corretos (`A02-janela-inicial.png`, `A02-A14-pacote-primeiro-inicio.png`). Pacote: 2 processos (lançador jpackage + JVM), nenhum console/`java.exe`, janela em 1,2–1,7 s. |
| A03 | Fechar e restaurar repetidamente | **Aprovado** | Desktop + Pacote | Desktop: 25 ciclos (1º com clique real do `Robot` no "X" da barra de título; restauração por ação do ícone e pelo item "Abrir"), um ícone, aviso de bandeja uma única vez. Pacote: 10/10 ciclos WM_CLOSE → ícone da bandeja → janela de volta, 1 ícone, 2 processos. Restauração no pacote pelo Invoke da UI Automation do Explorer (10/10) porque o estouro XAML do Windows 11 não entregou cliques sintéticos de mouse de forma estável; o clique real do mouse no ícone foi verificado à parte (protótipo e 5/5 cliques simples/duplos com ritmo humano). Achado corrigido: clique logo após outro chegava com `clickCount` 2 e era ignorado (`3fab25a`). |
| A04 | Sair e reabrir | **Aprovado** | Desktop + Pacote | Sair pelo menu do ícone (clique direito real + teclado): 0 processos, 0 ícones, `LIFE_EXIT`; reabre adquirindo a trava (arquivo mantido); configuração byte a byte igual e aplicada (tema claro em `A04-reaberto-config-preservada.png`). Desktop: trava livre após sair (`tryAcquire`). |
| A05 | Duas inicializações simultâneas | **Aprovado** | Auto + Pacote | Auto: 8 processos liberados juntos por barreira READY/GO → 1 proprietário, 7 recusados. Pacote: 5/5 rodadas com dois `.exe` disparados juntos → 1 proprietário (1 `LIFE_START`), a outra mostra "O Nekkora Agent já está aberto" (`A05-segunda-instancia-aviso.png`), não cria configuração, sai com código 3. |
| A06 | Queda e novo início | **Aprovado** | Auto + Pacote | Auto: dono morto por TerminateProcess → próximo processo adquire imediatamente, arquivo da trava mantido; trava ativa não pode ser apagada (`NOSHARE_DELETE`). Pacote: JVM e lançador mortos à força 3× → reabertura em ~1,2 s, 6/6 aquisições. Observação: o Windows mantém o ícone de um processo morto até o mouse passar sobre ele (comportamento do sistema); após a passagem, 0 ícones restantes. |
| A07 | Bandeja indisponível/falha | **Aprovado** (falha real: simulada) | Desktop + Pacote + Simulado | `--sem-bandeja` no pacote: nenhum ícone, fechar minimiza com processo vivo, Alt+S encerra. Desktop: modo sem bandeja e falha de instalação (`AWTException` simulada) com aviso e código (`A07-*.png`). A indisponibilidade real da bandeja do Windows não foi provocada (exigiria parar o Explorer). Achado corrigido: o aviso chegava após o dimensionamento e a janela rolava (`2815a18`, regressão no teste A07). |
| A08 | Configuração corrompida/incompatível e falha de escrita | **Aprovado** | Auto + Desktop | 9 variações de arquivo malformado, UTF-8 inválido, >64 KiB, versão 2, diretório no lugar do arquivo: padrões na sessão, arquivo intacto, gravação simples recusada; "Guardar cópia e salvar" preserva byte a byte (`A08-*.png`). Falha de escrita real (arquivo somente leitura) e simulada (temporário parcial): sem confirmação, configuração anterior em uso, sem temporários. Achado corrigido: alerta de falha ficava fora da área visível — regressão comprovada (falha com o código antigo, passa com a correção). |
| A09 | Nome da máquina indisponível | **Aprovado** | Auto + Desktop + Pacote | "Não disponível" e `AGT-HOST-001` (`A09-*.png`), inclusive no `.exe` com `COMPUTERNAME` removido do ambiente. |
| A10 | Atalhos válidos, ausentes e inválidos | **Aprovado** | Auto + Desktop + Pacote | 35 casos de política (URL com credenciais/parâmetros/fragmento/esquema; scripts, interpretadores, rede, aspas, `%`, argumentos). Pacote: nada abre sozinho; Espaço em "Abrir Studio" iniciou `C:\Windows\System32\charmap.exe` com linha de comando sem argumentos e pai = JVM; Alt+P abriu o navegador padrão, que pediu exatamente `GET /nekkora-agent-homologacao` a um TcpListener local; com o filho ainda aberto o Agent saiu e reabriu (trava não herdada); destinos ausentes do log. |
| A11 | Logs e exportação | **Aprovado** | Auto + Desktop | Rotação: ~6 MiB de eventos → ≤4 arquivos, cada ≤256 KiB, total ≤1 MiB; falha de diretório e de rotação suspendem o arquivo sem laço. Vazamento: valores sintéticos (nome da máquina, pessoa no caminho, host, token) ausentes de logs e diagnóstico; controles positivos (nome incluído por opção, linha vazada plantada) detectados. Exportação real pelo seletor de arquivos: arquivo = prévia, sem o nome da máquina (`A11-diagnostico-previa.png`). |
| A12 | Teclado, foco e escalas 100/150/200% | **Aprovado** | Desktop + Pacote | Tab percorre Configurações → Diagnóstico → Sair (atalhos indisponíveis fora do Tab, motivo em texto); teclado real: Tab, Alt+C, Esc (`Robot`), Alt+S, Alt+P e Espaço no pacote. Escalas em JVMs separadas: nenhum selo/botão/rótulo menor que o preferido; janelas dentro da área útil; a 200% rolagem na janela e no Diagnóstico (`escala-150/`, `escala-200/`). Achado corrigido: dicas de campo cortadas em Configurações. Leitor de tela não testado. |
| A13 | Ocioso, oculto e após suspensão | **Parcial — suspensão pendente** | Desktop + Pacote; suspensão não exercitada | Oculta: 0 ms de CPU e nenhum `javax.swing.Timer` na JVM de teste (10 s); pacote: 46,9 ms de CPU em 60 s (0,078% de um núcleo), memória estável. Métricas abaixo. **Suspensão/retorno do Windows não foi exercitada** (suspenderia a máquina de trabalho); roteiro pronto: `tools/homologation/suspend-check.ps1`. |
| A14 | Distribuição isolada | **Aprovado** | Pacote | ZIP extraído fora do repositório, SHA-256 conferido, PATH reduzido a `C:\Windows` (nenhum `java`), `JAVA_HOME` removido: abre; 93–95 módulos carregados, 0 fora do pacote/Windows, 0 do JDK/Studio/`.design-system`. Achado corrigido: a DLL nativa do FlatLaf era extraída e carregada de `%TEMP%` — agora vem de `app\lib`. |
| A15 | Estados de integração | **Aprovado** | Auto + Desktop | Todos os textos da janela, Configurações, Diagnóstico, dica e menu da bandeja varridos: nenhum "pronto", "pareado" (sem "não"), "conectado", "licença ativa", "execução disponível", `agent_id`/`installation_id`; todo selo tem texto. |

Extra — **D-05 (tema do Studio)**: aprovado no pacote: `HKCU\Software\JavaSoft\Prefs\com\nekkora\ui\theme`
igual antes e depois de toda a homologação; nenhum nó do Agent criado.

## Métricas (A13) — pacote, Windows real

| Execução | Janela visível (externo) | `startup_ms` (log, início do processo → janela aberta) | Working set (lançador + JVM) | Memória privada | Threads (JVM) |
|---|---|---|---|---|---|
| 1 | 1240 ms | 1037 ms | 153,4 MiB | 353,7 MiB | 63 |
| 2 | 1191 ms | 1036 ms | 151,7 MiB | 351,6 MiB | 48 |
| 3 | 1230 ms | 1058 ms | 152,9 MiB | 353,0 MiB | 63 |
| 4 | 1237 ms | 1042 ms | 153,8 MiB | 353,7 MiB | 64 |
| 5 | 1213 ms | 1035 ms | 152,0 MiB | 351,1 MiB | 47 |

Janela oculta por 60 s (execução 1): CPU 46,9 ms (0,078% de um núcleo); working set 153,4 MiB; privada
353,6 MiB. Precisão: "externo" é medido pelo roteiro com sondagem a cada 100 ms; `startup_ms` usa o
instante de criação do processo da JVM (o lançador jpackage parte ~0,1–0,2 s antes). Cinco execuções em
uma única máquina; não há meta definida na missão para comparar. Opções da JVM: `UseSerialGC`,
`-Xmx256m`, `TieredStopAtLevel=1`.

## Testes automatizados

- `mvnw verify` (sem tela): **108/108**.
- `mvnw -Pdesktop verify` (Windows real): **122/122** + escala 150% **1/1** + escala 200% **1/1**
  (resumo em `evidencias/desktop/surefire-resumo.txt`).

## Pendências de homologação

1. **A13 — suspensão e retorno do Windows**: executar `suspend-check.ps1 -Fase antes`, suspender, retomar,
   `suspend-check.ps1 -Fase depois`.
2. **A07 — indisponibilidade real da bandeja** (Explorer parado): coberta por simulação e pelo modo
   `--sem-bandeja`; não provocada em ambiente real.
3. **A03 — clique de mouse no ícone em série**: o roteiro usou o Invoke da UI Automation; cliques reais
   foram verificados em sessões avulsas, não nos 10 ciclos oficiais.
4. Leitor de tela (Narrador/NVDA) com Java Access Bridge: não testado.
