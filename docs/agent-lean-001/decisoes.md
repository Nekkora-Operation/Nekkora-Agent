# AGENT-LEAN-001: decisões

Princípio da missão: tornar o Agent menor não pode torná-lo menos confiável. A observabilidade 1.0.0, os B-OBS-01/02/03,
a recuperação/adoção, o cancelamento, a sanitização, os códigos AGT-* e as pendências físicas foram preservados
integralmente: nenhum teste, documento ou mecanismo foi removido.

| # | Decisão | Por quê | Onde | Evidência |
|---|---|---|---|---|
| A-01 | **`-Xms16m` nas opções da JVM do pacote** (`tools/package-windows.ps1`; teto `-Xmx256m`, GC serial e `TieredStopAtLevel=1` inalterados) | sem ele, o heap inicial padrão é 1/64 da RAM (limitado ao `-Xmx256m`) e fica todo COMPROMETIDO na abertura. Medido numa cópia do pacote: memória privada 350 → 108 MB e conjunto de trabalho 149 → 123 MB, com abertura igual | `package-windows.ps1` e `VERSOES.txt` | `antes-depois.md` (pacote real reconstruído e medido de novo) e regressões sob carga em `matriz-regressao.md` |
| A-02 | NÃO excluir `runtime/lib/jvm.lib` | 194 KB no ZIP (0,5%): ganho desprezível para mais uma regra de empacotamento | — | listagem do ZIP |
| A-03 | NÃO remover DLLs opcionais do `java.desktop` nem o `jdk.accessibility` | menos de 250 KB; o Access Bridge serve a leitores de tela (acessibilidade) | — | inventário |
| A-04 | NÃO adicionar CDS/AppCDS | +15–20 MB no pacote contra uma abertura que já fica em ~1,1 s | — | inventário |
| A-05 | NÃO mexer em documentação nem evidência | os documentos vigentes da DESKTOP-001 são a referência das pendências físicas (G2/OBS-G6, A13 adiado) e das revisões B-OBS | — | — |
| A-06 | `ExecutionController`, contratos e schema 1.0.0 intocados | missão de limpeza; nenhuma descoberta crítica | — | `git diff a3e5598 -- src` vazio |
| A-07 | Nenhuma comunicação Studio → Agent ou Agent → Platform | fora do escopo | — | — |
