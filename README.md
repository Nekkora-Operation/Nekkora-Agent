# Nekkora Agent

Aplicativo desktop do Nekkora Agent para Windows: janela compacta com a identidade Nekkora, ícone na
bandeja do sistema, instância única por usuário, configuração local e diagnóstico.

**Estado desta versão (0.1.0, laboratório):** o Agent ainda **não** pareia com a Platform, não se
conecta, não verifica licença e não executa automações. A interface diz isso explicitamente
("Não pareado", "Sem conexão com a Platform", "Não verificada", "Execução indisponível nesta versão").

- Decisões de projeto: [`docs/agent-001/decisoes.md`](docs/agent-001/decisoes.md)
- Matriz de homologação A01–A15: [`docs/agent-001/matriz-homologacao.md`](docs/agent-001/matriz-homologacao.md)
- Relatório da missão: [`docs/agent-001/relatorio.md`](docs/agent-001/relatorio.md)

## Requisitos de desenvolvimento

- Windows 10/11 x64 (a homologação foi feita em Windows 11 x64)
- JDK 21 com `jlink`, `jpackage` e `jdeps` (testado com Microsoft Build of OpenJDK 21.0.12)
- Git e acesso ao repositório Nekkora-Studio na primeira preparação
- Maven: não precisa instalar; use `mvnw.cmd` (baixa o Maven 3.9.11 na primeira vez)

## 1. Preparar o design system (uma vez, ou quando `design-system.properties` mudar)

As bibliotecas visuais (`nekkora-design-tokens`, `nekkora-swing-ui`) são construídas a partir do
commit fixado do Studio em um repositório Maven isolado, sem alterar o Studio:

```powershell
powershell -ExecutionPolicy Bypass -File tools\prepare-design-system.ps1
# sem rede para o GitHub, a partir de um clone local (o commit continua conferido):
powershell -ExecutionPolicy Bypass -File tools\prepare-design-system.ps1 -StudioRepository C:\caminho\Nekkora-Studio
```

Resultado: `.design-system\m2` (repositório isolado, ignorado pelo Git) e
`.design-system\design-system.lock.properties` (commit, JDK e SHA-256 dos JARs). A construção do Agent
usa **somente** esse repositório (`.mvn/maven.config`), e o teste `DesignSystemProvenanceTest` confere
que as classes carregadas vêm dele com o SHA-256 registrado. O checkout em `.design-system\src` pode
ser apagado depois.

## 2. Construir e testar

```powershell
.\mvnw.cmd verify             # testes automatizados (sem tela)
.\mvnw.cmd -Pdesktop verify   # + testes em desktop Windows real (janela, bandeja, teclado, escalas 100/150/200%)
```

O perfil `desktop` abre janelas, usa a bandeja e o teclado: não use o computador enquanto ele roda.
Capturas: `target\desktop-evidence\escala-*\`.

Para abrir durante o desenvolvimento (com diretório de dados descartável):

```powershell
$env:NEKKORA_AGENT_DATA_DIR = "$env:TEMP\nekkora-agent-dev"
java -jar target\nekkora-agent.jar
```

## 3. Gerar a distribuição de laboratório

```powershell
powershell -ExecutionPolicy Bypass -File tools\package-windows.ps1
```

Gera `target\dist\nekkora-agent-0.1.0-windows-x64-lab.zip`, o `.sha256` e `VERSOES.txt`. O ZIP contém
`Nekkora Agent\Nekkora Agent.exe` com runtime Java próprio (jlink), `LEIA-ME.txt`, `VERSOES.txt` e
`legal\`. Não precisa de Java instalado nem do checkout do Studio. **Não é assinado digitalmente e não é
instalador de produção.**

Homologação do pacote em desktop real (extrai o ZIP fora do repositório, PATH sem Java):

```powershell
powershell -ExecutionPolicy Bypass -File tools\homologation\homologate-package.ps1 -Out C:\temp\homolog
# suspensão e retorno (manual): ver o cabeçalho de tools\homologation\suspend-check.ps1
```

## 4. Uso

- Abrir: `Nekkora Agent.exe`. Uma segunda abertura mostra "O Nekkora Agent já está aberto" e sai.
- Fechar a janela mantém o Agent na bandeja; clique no ícone (ou "Abrir o Nekkora Agent") para voltar.
- Encerrar: "Sair" (menu do ícone ou botão da janela, Alt+S).
- `--sem-bandeja`: abre só com a janela; fechar minimiza na barra de tarefas.
- Configurações: tema; endereço da Platform (http/https, sem usuário, senha, `?` ou `#`); executável
  do Studio (`.exe` completo; scripts, interpretadores e argumentos são recusados). Os atalhos só abrem
  com clique.

## 5. Dados, logs e diagnóstico

| Item | Local |
|---|---|
| Diretório de dados | `%LOCALAPPDATA%\Nekkora\Agent` (ou `NEKKORA_AGENT_DATA_DIR`) |
| Configuração | `config\agent-config.json` (versão 1) |
| Logs técnicos | `logs\agent.log`, `agent.1.log`…`agent.3.log` (256 KiB cada, 1 MiB no total) |
| Trava de instância única | `agent.lock` (fica no disco; a exclusão é o bloqueio do sistema) |

Os dados do Studio (`%LOCALAPPDATA%\Nekkora\Studio\data`) e da extensão não são tocados, e o tema do
Studio no registro não é lido nem alterado.

**Diagnóstico** (botão "Diagnóstico"): mostra a prévia exata do arquivo antes de exportar — lista
fechada de campos, sem caminhos, endereços ou conteúdo de configuração; nome da máquina só com a opção
marcada. Nada é enviado.

Códigos de erro:

| Código | Situação |
|---|---|
| AGT-DATA-001 | diretório de dados inutilizável (o Agent não abre) |
| AGT-LOCK-002 | trava de instância única indisponível (o Agent não abre) |
| AGT-CFG-002 / 003 / 004 | configuração malformada / versão não suportada / ilegível (arquivo preservado) |
| AGT-CFG-005 / 006 | falha ao gravar a configuração / ao guardar a cópia do arquivo incompatível |
| AGT-LOG-001 / 002 / 003 | logs indisponíveis / rotação falhou / eventos descartados por volume |
| AGT-TRAY-001 / 002 / 003 | bandeja inexistente / falha ao instalar o ícone / desativada por `--sem-bandeja` |
| AGT-HOST-001 | nome da máquina indisponível no ambiente |
| AGT-SC-002 / 003 | executável do Studio inválido / falha ao iniciar |
| AGT-SC-012 / 013 / 014 | endereço da Platform inválido / falha do navegador / sem navegador |
| AGT-DIAG-001 | falha ao gravar o diagnóstico |
| AGT-APP-001 | falha interna inesperada |

## 6. Remoção

1. "Sair" no Agent. 2. Apagar a pasta do pacote. 3. Opcional: apagar `%LOCALAPPDATA%\Nekkora\Agent`.
O Agent não grava no registro, não instala serviço nem início automático.

## Estrutura

```
src/main/java/com/nekkora/agent/
  core/         estado, textos e códigos (sem Swing)
  storage/      diretório de dados, trava, configuração, Preferences isoladas
  diagnostics/  logs com rotação e diagnóstico exportável
  os/           bandeja do sistema e atalhos
  app/          inicialização e contexto (sem Swing)
  ui/           janela, diálogos e ciclo de vida (EDT)
tools/          preparação do design system, empacotamento e homologação
docs/agent-001/ decisões, matriz de homologação, relatório e evidências
```
