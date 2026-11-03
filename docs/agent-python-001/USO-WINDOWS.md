# Python externo com o Nekkora Agent, no Windows

Instruções curtas para os dois marcos da AGENT-PYTHON-001. **[pessoa]** marca um ato da pessoa, na Platform ou na máquina,
que nenhum programa faz por ela. Nada aqui exige o Studio.

Os executáveis ficam na pasta do Agent instalado (`Nekkora Agent\`):
- `Nekkora Agent.exe`: a janela e a bandeja; precisa estar aberto para **entregar** os fatos à Platform;
- `nekkora-lancar.exe`: o lançador que a tarefa agendada chama;
- `nekkora-console.exe`: comandos de console (`vincular`, `vinculos`, `desvincular`, `pacote instalar`).

## Marco 1: monitorar um Python que o Agendador de Tarefas já executa

Exemplo sem nenhuma dependência Nekkora: `tools/external-package/exemplo-relatorio/relatorio.py`, que lê um CSV e grava um
resumo.

1. **[pessoa, Platform]**
   - Cadastre a automação (Workspaces → Criar automação).
   - Pareie o Agent e autorize o Worker no workspace.
   - Na página da automação, em **Monitoramento de Python externo**, escolha o Worker e clique em **Autorizar monitoramento**
     (com step-up).
   - Copie o identificador da automação mostrado ali.
2. **[pessoa, máquina]** Aprove o vínculo. Os caminhos ficam só na máquina.
   ```powershell
   & "C:\...\Nekkora Agent\nekkora-console.exe" vincular --nome relatorio-csv --automacao <id da automação> `
       --python C:\cliente\venv\Scripts\python.exe --script C:\cliente\relatorio.py --pasta C:\cliente
   # o console mostra o que será aprovado; digite SIM
   ```
3. **[pessoa, máquina]** Na tarefa agendada, troque **só o programa**. Ambiente virtual, script, argumentos, pasta de trabalho
   ("Iniciar em") e horário continuam os mesmos:

   | | Programa | Argumentos |
   |---|---|---|
   | antes | `C:\cliente\venv\Scripts\python.exe` | `"C:\cliente\relatorio.py" --entrada vendas.csv --saida resumo.txt` |
   | depois | `C:\...\Nekkora Agent\nekkora-lancar.exe` | `--vinculo relatorio-csv -- "C:\cliente\venv\Scripts\python.exe" "C:\cliente\relatorio.py" --entrada vendas.csv --saida resumo.txt` |

4. O Agent precisa ter baixado a autorização. Isso acontece até 5 minutos depois de ele ficar conectado.

**O que aparece na tela (Supervisão):** uma linha por invocação, com:
- automação e máquina;
- origem "Python externo monitorado";
- início, fim e duração;
- desfecho.

| Código | O que a tela diz |
|---|---|
| 0, sem sinal | **Terminou sem erro sinalizado** (código 0; sucesso de negócio não sinalizado) |
| ≠ 0 | **Terminou com erro** (código de saída N) |
| sinal `BUSINESS_SUCCESS` gravado pelo script em `%NEKKORA_SINAL%` (opcional) | Concluída com sucesso |
| lançador morto sem ver o fim | Desconhecido |

**O que o agendador vê:**
- o mesmo código de saída do script;
- `120` quando o vínculo não confere;
- `121` quando não há autorização válida. Nesse caso, nada roda.

## Marco 2: empacotar, publicar, aprovar na máquina e "Executar"

Exemplo: `tools/external-package/exemplo-pedido/` (`principal.py` + `resumo.py`, CSV de exemplo sem dados reais).

1. **Build:** o projeto é empacotado, não convertido em EXE. O gerador usa só a biblioteca padrão.
   ```powershell
   py -3 tools\external-package\nekkora_empacotar.py C:\projetos\relatorio --nome relatorio-pedido --versao 1.0.0 `
       --entrada principal.py --incluir dados_exemplo\vendas.csv --saida C:\pacotes
   ```
   **O que entra:**
   - os arquivos do projeto e o descritor `nekkora-external.json` (nome, versão, entrada, Python, dependências exatas do
     `requirements.txt` e o hash de cada arquivo).

   **O que nunca entra:**
   - `.venv`, `venv`, `env`, `__pycache__`, `.git`, `.env*`, `*.pem`, `*.key`, `*.pfx`, `id_rsa*`, `.pypirc`, `*.pyc`.

   **O que fica fora por padrão:**
   - `dados/`, `data/`, `dist/`, `build/`, `*.csv`, `*.xlsx`, `*.db`, `*.log`. Use `--incluir` só para um exemplo sem dados
     reais.

   **A saída mostra:**
   - o **sha256 do ZIP**, que identifica os bytes do artefato. O ZIP é determinístico, então a mesma entrada gera o mesmo
     sha256;
   - a **impressão** `external-python-fingerprint/1`, que identifica o conteúdo e é a mesma que o Agent e a Platform calculam.

   **Recusas:**
   - entrada fora da raiz;
   - dependência sem `==`;
   - arquivo proibido pedido em `--incluir`.
2. **[pessoa, Platform]**
   - Crie a versão e envie o ZIP. A Platform confere sem executar nada e recusa pacote alterado.
   - Confira tipo, Python, dependências e impressão.
   - **Publique** (step-up) e **autorize** o Worker para a versão (step-up).
3. **[pessoa, máquina]** Prepare o ambiente Python e **aprove o pacote** nesta máquina. Nada é instalado: o Agent só confere a
   versão do Python e as dependências exatas.
   ```powershell
   & "C:\...\Nekkora Agent\nekkora-console.exe" pacote instalar C:\pacotes\relatorio-pedido-1.0.0.zip `
       --python C:\ambientes\relatorio\Scripts\python.exe --saida C:\saidas\relatorio
   # mostra tipo, versão, entrada, Python, dependências e impressão; digite SIM
   ```
4. **[pessoa, Platform]** Na versão, clique em **Executar** na autorização vigente, escolha a máquina e a validade e confirme
   com step-up.

**O que aparece no painel do pedido:** os estágios abaixo são distintos. Entregar ou aceitar **não** é iniciar.

| Estágio | O que a tela diz |
|---|---|
| registrado | Solicitação registrada · aguardando a máquina |
| entregue | Entregue à máquina · aguardando decisão |
| aceito | Aceita pela máquina · início ainda não confirmado |
| iniciado | Execução iniciada (só com o instante do supervisor) |
| fim | Desfecho reportado: … |
| desvios | Recusada (com motivo), Aceita mas não iniciada, Expirada sem decisão, Revogada antes da decisão |

**Máquina desligada:** o pedido continua "aguardando a máquina" até ela voltar ou o prazo vencer.

**Recusa:** a máquina recusa e não inicia nada quando acontece qualquer um destes casos:
- o pacote não está presente ou não foi aprovado;
- a autorização não serve;
- o ambiente é incompatível;
- a máquina está ocupada;
- o pedido venceu.

**Garantia:** no máximo um processo por pedido, enquanto a pasta de dados do Agent existir. Uma queda na fronteira do início
vira **desfecho desconhecido**, visível, e nunca é relançada.
