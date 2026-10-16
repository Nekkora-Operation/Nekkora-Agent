# Relatório — SUPER MISSÃO AGENT-001 (aplicativo desktop do Nekkora Agent)

Data: 16/10/2026. Situação: **implementada; homologação Windows concluída exceto a suspensão/retorno
(A13), que fica pendente com roteiro pronto**. O merge é da Nícolly; nada foi publicado.

## 1. Objetivo

Primeira funcionalidade completa do Nekkora Agent: aplicativo Windows com identidade Nekkora, janela
compacta, bandeja, instância única, configuração local, diagnóstico e distribuição de laboratório
sem terminal — sem pareamento, conexão, licença ou execução (estados honestos na interface).

## 2. Repositórios

| Repositório | Uso | Commit |
|---|---|---|
| Nekkora-Agent | alterado (branch `feat/nekkora-agent-001`) | ver §4 |
| Nekkora-Studio | leitura + construção isolada das bibliotecas visuais | `beae03f16cdb72de987280e0f1e85d0a0e9852ba` (main; a missão citava `b0bd441` — a diferença nas bibliotecas é só `NekkoraList`, +26 linhas) |
| Nekkora-Extension-Chrome | leitura | `2c7c06b` (igual ao documento) |
| Nekkora-Platform-API | leitura (migrations até 016; 017 inexistente) | `20ccb06` (igual ao documento) |

Nenhum arquivo do Studio, da extensão ou da API foi alterado; não houve revisão ou comentário em PRs
deles. O repositório Agent estava vazio e não tinha `AGENTS.md`/`CLAUDE.md`.

## 3. O que foi entregue

- **Aplicativo** (Java 21, Swing com `nekkora-design-tokens`/`nekkora-swing-ui`, FlatLaf 3.7.2):
  janela 540×~680 com máquina, versão/build e estados "Não pareado", "Não vinculado", "Sem conexão com a
  Platform", "Não verificada", "Execução indisponível nesta versão", com explicação; atalhos Studio e
  Platform (indisponíveis com motivo em texto); Configurações, Diagnóstico e Sair.
- **Bandeja e ciclo de vida**: um ícone; fechar oculta; aviso uma vez por sessão; sem bandeja, fechar
  minimiza e "Sair" fica explícito; saída ordenada (ícone, janelas, fila de E/S, log, trava).
- **Instância única**: bloqueio do SO sobre `agent.lock` no diretório canônico, mantido pela vida do
  processo, arquivo não apagável enquanto ativo; segunda instância avisa e sai (código 3) sem tocar em nada.
- **Configuração** versionada e estrita, gravação atômica, arquivo incompatível preservado.
- **Diagnóstico**: logs com rotação (1 MiB no total) sem texto livre; exportação de campos fechados com
  prévia exata e nome da máquina opcional.
- **Isolamento do Studio**: `Preferences` em memória; tema do Studio no registro intocado.
- **Design system reproduzível**: preparação a partir do commit fixado em repositório Maven isolado,
  JARs idênticos byte a byte, origem conferida por teste.
- **Distribuição**: ZIP com `Nekkora Agent.exe` + runtime jlink (6 módulos), DLL nativa do FlatLaf no
  pacote, LEIA-ME, `VERSOES.txt`, licenças e SHA-256.
- **Documentação**: README, decisões D-01..D-12, matriz A01–A15, este relatório, evidências.

## 4. Commits (branch `feat/nekkora-agent-001`, sobre a base local `afd1bb5`)

| Commit | Conteúdo |
|---|---|
| `afd1bb5` (`main`, local) | commit-base mínimo (`.gitignore`, `.gitattributes`, README curto) — **não publicado** |
| `7d99a78` | implementação, testes, empacotamento |
| `60e49dd` | DLL do FlatLaf no pacote, roteiros de homologação, documentação inicial |
| `c0c91e3` | `suspend-check.ps1` com BOM |
| `3fab25a` | clique esquerdo em qualquer contagem restaura; roteiro com caminhos alternativos |
| `0ab4f60` | Alt+T/Alt+P nos atalhos; roteiro mais robusto |
| `1889ab3` | roteiro traz a janela ao primeiro plano antes de enviar teclas |
| `2815a18` | reajuste da janela quando o aviso da bandeja chega — **código de produto homologado** |
| `bb445db` | preparação com `core.longpaths` |
| (este) | matriz, relatório e evidências |

## 5. Testes e comandos

```powershell
powershell -ExecutionPolicy Bypass -File tools\prepare-design-system.ps1   # A01 (também em clone novo, -Clean)
.\mvnw.cmd verify                                                         # 108/108
.\mvnw.cmd -Pdesktop verify                                               # 122/122 + 150% 1/1 + 200% 1/1
powershell -ExecutionPolicy Bypass -File tools\package-windows.ps1        # pacote + SHA-256
powershell -ExecutionPolicy Bypass -File tools\homologation\homologate-package.ps1 -Out <pasta>
```

Resultado da matriz: A01–A12, A14, A15 aprovados; A13 medido, suspensão pendente. Detalhes e tipos de
evidência em `matriz-homologacao.md`.

## 6. Defeitos encontrados e corrigidos na própria missão

| # | Defeito | Como apareceu | Correção | Regressão |
|---|---|---|---|---|
| R-01 | Selos de estado cortados ("Sem conexão com a Platf…") | primeira captura | layout em 2 colunas com largura mínima de projeto | `ScaleEvidenceTest` (largura ≥ preferida) |
| R-02 | Janela com altura da tela inteira | primeira captura | medir após `pack()` (layout só existe com peer nativo) | `a02…` (janela ≤ 720) |
| R-03 | Dicas de campo cortadas em Configurações | A12 automatizado | textos mais curtos | `ScaleEvidenceTest` |
| R-04 | Alerta de falha de gravação fora da área visível | captura A08 | alerta no topo + rolagem até ele | `a08WriteFailure…` — **falha com o código antigo, passa com a correção** |
| R-05 | DLL nativa do FlatLaf extraída/carregada de `%TEMP%` | A14 no pacote | DLL em `app\lib` + `flatlaf.nativeLibraryPath` | A14 (0 módulos fora do pacote) |
| R-06 | Clique no ícone logo após outro não restaurava (chegava como `clickCount` 2) | homologação do pacote | qualquer clique esquerdo abre (idempotente) | pacote A03 |
| R-07 | Aviso de bandeja chegava após o dimensionamento e a janela rolava | captura A07 do pacote | reajuste único da altura | `a07…` — **falha sem o reajuste, passa com ele** |
| R-08 | Checkout do Studio incompleto em pasta profunda (260 caracteres) | construção limpa (A01) | `core.longpaths` no clone/checkout | A01 em clone novo |

Ajustes de testes que não eram defeitos do produto foram registrados nos commits (por exemplo, a
asserção de log que prometia mais do que o filtro faz e a regra do A15 que não conhecia `NAO_PAREADO`).

## 7. Métricas (Windows 11, Ryzen 7 8700F, 1920×1080 a 100%)

- Tempo até a janela: 1191–1240 ms (externo); `startup_ms` 1035–1058 ms (5 execuções).
- Memória com a janela visível: working set 151,7–153,8 MiB (lançador + JVM); privada ~352 MiB.
- Oculto 60 s: 46,9 ms de CPU (0,078% de um núcleo); nenhuma animação ou timer ativo (verificado na
  JVM de teste: 0 ms, nenhum `javax.swing.Timer`).
- Pacote: ZIP 33,9 MiB; pasta 49,5 MiB.

Não há meta numérica na missão; os valores são medições de uma única máquina, sem afirmação de
precisão além da descrita na matriz.

## 8. Limitações e dependências

- **Suspensão/retorno do Windows (A13) não exercitada** — roteiro `suspend-check.ps1` pronto.
- Indisponibilidade real da bandeja não provocada (simulada; modo `--sem-bandeja` real).
- No pacote, a restauração pela bandeja em série usou o Invoke da UI Automation; cliques reais do mouse
  foram verificados em sessões avulsas (o estouro XAML do Windows 11 descarta parte dos cliques
  sintéticos). Leitor de tela não testado.
- O lançador do jpackage (JDK 21) cria um processo filho: cada instância aparece como dois
  `Nekkora Agent.exe`.
- Após queda forçada, o Windows mantém o ícone "fantasma" até o mouse passar sobre ele.
- **Dependência do Studio**: não há distribuição `.exe` do Studio (abre por `studio.cmd`, um script);
  como o Agent só aceita `.exe` sem shell, o atalho do Studio fica indisponível na prática até existir
  um executável. Decisão para o Studio, fora deste escopo.
- **Dependência do design system**: as bibliotecas não têm artefato versionado; a preparação local é a
  solução desta missão. Publicar as bibliotecas fica para decisão.
- Instância única vale por usuário e diretório de dados; não entre sessões/usuários (para a futura
  identidade/admissão de execução).
- Pacote **sem assinatura digital**; laboratório, não produção. Arquitetura suportada e testada:
  Windows x64 (o ZIP só contém a DLL x64 do FlatLaf e runtime x64).
- Ambiente Linux/headless não foi usado como equivalência de homologação.

## 9. Entrega para revisão

- O repositório remoto continua vazio: o commit-base `afd1bb5` (`main`) e o branch existem só
  localmente em `C:\Users\nicol\nekkora\Nekkora-Agent`. Para abrir o PR é preciso publicar a base mínima
  (`main` = `afd1bb5`) e o branch — **a publicar somente após o alinhamento com a responsável**.
- Merge, force-push e auto-merge: não realizados.

## 10. Artefatos

| Artefato | Caminho |
|---|---|
| Pacote (gerado, não versionado) | `target\dist\nekkora-agent-0.1.0-windows-x64-lab.zip` + `.sha256` + `VERSOES.txt` |
| SHA-256 do pacote homologado | `2a855a721c094c44cda7c22d3e398883b441accad3905e2983d0396b51e4c39d` |
| Capturas de desktop (100/150/200%) | `docs\agent-001\evidencias\desktop\` |
| Capturas e resultados do pacote | `docs\agent-001\evidencias\pacote\` (`resultados.md`, `resultados.json`) |
| Decisões / matriz | `docs\agent-001\decisoes.md`, `docs\agent-001\matriz-homologacao.md` |
