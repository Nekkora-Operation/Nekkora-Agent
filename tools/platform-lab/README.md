# Laboratório Agent ↔ Platform (AGENT-PLATFORM-001)

Prova integrada do Agent empacotado contra a API real, num laboratório descartável. Segredos ficam só em
`~/.nekkora/agent-lab/<id>/` (fora dos repositórios). Saídas brutas em `tools/platform-lab/saidas/<id>/` (ignoradas pelo Git);
as evidências sanitizadas vão para `docs/agent-platform-001/evidencias/`.

1. Cópia da API na `main`, com os jars: `git -C <API> worktree add --detach ../npa-main-agentlab origin/main` e
   `sh ./mvnw -q -DskipTests package` em `api/`.
2. Subir: `bash tools/platform-lab/subir.sh agentlab-001 ../npa-main-agentlab` (portas 55871/55872; recusa recursos existentes).
3. Pacote de laboratório e impressão (função do Agent): `java -cp target/classes tools/platform-lab/PacoteLab.java <pasta> slow 240 500`.
4. Pessoa na Platform real: `python -I tools/platform-lab/pessoa.py preparar agentlab-001 <impressão>` (e `autorizar` para
   outra impressão; `novo-codigo` revoga o Agent ativo e gera outro código; `consultar`, `revogar`, `rotacao`, `sql`).
5. Agent empacotado: `powershell -File tools/package-windows.ps1 -NoZip` (com `NEKKORA_TEST_PYTHON` para os testes com processo).
6. Jornada: `powershell -File tools/platform-lab/jornada.ps1 -Id agentlab-001 -Python <python do Framework 0.3.0>`
   (não usar o computador; `-SomenteLocalDepoisDaRevogacao` retoma só a parte final do J8 e o J9).
7. Parar sem apagar: `docker compose -f <API>/api/e2e/compose.e2e.yml -f tools/platform-lab/compose.lab.yml stop`
   (com as variáveis de `segredos.env`). Nunca `down -v`.

O nginx carrega a chave TLS na subida e ela é apagada em seguida. Por isso a jornada corta a comunicação com `pause` e não
com `stop`.

## Segundo laboratório e jornada complementar (AGENT-PLATFORM-002)

- Sub-rede própria, sem tocar um laboratório preservado: `LAB_REDE=48 LAB_PORTA=55873 LAB_PG_PORTA=55874 bash tools/platform-lab/subir.sh agentlab-002 ../npa-main-agentlab`
  (`compose.lab-rede.yml`; as ferramentas leem `LAB_COMPOSE_EXTRA` de `segredos.env`).
- Pacotes: `pacote-jornada` (`slow 400 350`) e `pacote-limite` (`slow 2600 0`, passa do limite do histórico); impressões em
  `saidas/<id>/impressoes.json`; `pessoa.py preparar` com a primeira e `autorizar` com a segunda.
- Jornada: `powershell -File tools/platform-lab/jornada-complemento.ps1 -Id agentlab-002 -Python <python do Framework 0.3.0>`
  (`-SomenteLimite` retoma só o C8 sobre os dados existentes). Apoio comum em `LabComum.ps1`.
- Parar: o mesmo `stop` do passo 7, com `-f tools/platform-lab/compose.lab-rede.yml` a mais.
