# Evidências da STUDIO-AGENT-LOCAL-001

Sanitizadas por `roteiros/montar_evidencias.py`:
- `<sessao>`, `<nekkora>`, `<usuario>`, `<maquina>`, `<temp>` e `<dados-studio-compat>` no lugar de caminhos, usuário
  e máquina;
- o script falha se sobrar qualquer marca pessoal num arquivo de texto;
- as capturas de tela foram conferidas uma a uma: a janela do Agent só mostra nomes curtos.

| Pasta | Conteúdo |
|---|---|
| `real/a1-sucesso` | execução real pelo Agent empacotado (ZIP `5fa0354e…`) do pacote real do Studio. Contém: capturas 01 a 07; `estado.json` 1.1.0, `eventos.jsonl` e `atividade.jsonl` da execução; `resultado.json` do roteiro; `console.txt`; ISO-R ocioso e em execução; **conferência independente 23/23** (`conferencia.txt`/`.json`) |
| `real/a2-cancelamento` | cancelamento real pela interface (Sair → Encerrar execução e sair). `conferencia-progresso.txt`: o progresso do Agent é igual ao checkpoint carregado pelo `CheckpointStore.load()` do Framework 0.3.0 |
| `real/a5-agent-derrubado` | Agent derrubado (`Stop-Process -Force`), automação terminando sozinha, reabertura. **`FAILED`/AGT-EXE-022 recuperado do protocolo**. `conferencia-recuperacao.*` 12/12 |
| `real/a5b-agent-e-automacao-derrubados` | Agent e automação derrubados (`taskkill /T /F`). `OUTCOME_UNKNOWN` com `PROTOCOL_REJECTED NO_PROTOCOL` e progresso parcial conferido contra o Framework |
| `isolamento/` | controles positivos do ISO-R (`Escuta.java`: TCP em loopback, UDP e AF_UNIX; um PowerShell com pipe nomeado), todos detectados |
| `f-fw-04/` | o experimento do achado F-FW-04 (`writer.py` com o `CheckpointStore` real, `Reader.java`) e o resultado de 3 rodadas |
| `tela/` | D15 a D17 do teste de desktop: janela de execução, janela principal e aba Atividade depois de um cancelamento real |
| `vermelho/` | os testes novos contra o código anterior: A01 8/8, A02 8/8, A03 2 de 5 (os de comportamento) |
| `roteiros/` | roteiro da execução real (`run-in-agent-sal.ps1`, gerado por `patch_roteiro.py` a partir do roteiro da compat-001), conferidores independentes, chamada (`run.sh`) e sanitizador |
| `regressao.md` | baterias Windows, desktop e Linux |

**Conferidores independentes.** Leem só arquivos e não importam o Agent:
- `conferir-sal.py`: cópia do conferidor da compat-001, que agora aceita o registro 1.1.0 e valida contra o schema
  **real** do repositório;
- `conferir_progresso.py`: usa o próprio Framework para carregar o checkpoint com a integridade;
- `conferir_recuperacao.py`: compara o registro do Agent com o `summary.json` e o diário do Framework.
