# AGENT-LEAN-001: matriz de regressão

Comparação por CASO (classe + método + resultado), extraída dos relatórios do surefire antes e depois. Framework irmão
em `main`/0.3.0 nas duas medições (o mesmo da DESKTOP-001).

| Bateria | Antes (`a3e5598`) | Depois | Casos |
|---|---|---|---|
| Windows `./mvnw -o -B clean verify` (via `tools/package-windows.ps1`) | 192: 191 + 1 pulado | 192: 191 + 1 pulado | **idênticos**; pulado: `SourceFileTest.symbolicLinksAreRefused` (sem privilégio de ligação simbólica) |
| Windows `-Pdesktop clean verify` (Robot; escalas 100/150/200% em JVM própria) | 215: 214 + 1 pulado | 215: 214 + 1 pulado | **idênticos** |
| Linux Docker `eclipse-temurin:21-jdk-noble --init` | 192: 159 + **15 falhas da base** + 18 pulados | idem | **idênticos**, inclusive as 15 falhas herdadas (`ShortcutTest` 13, `AgentBootstrapTest` 1, `SensitiveDataLeakTest` 1: dependem do Windows); nada promovido a sucesso nem tratado como novo |
| Ensaio do roteiro físico `fisica-g2-a13.ps1 -Ensaio` com o ZIP NOVO (sem pausa, sem bloqueio, sem suspensão) | 25/25 (DESKTOP-001) | **25/25** | bloqueio/suspensão **NÃO EXERCIDOS**: não é a homologação G2/A13/OBS-G6 |
| Roteiro de pacote OBS-001 (`obs-001-pacote.ps1`: queda simulada, adoção, aba Atividade durante a execução, fim pelo protocolo, histórico após reabrir) | 14/14 com o ZIP antigo | **14/14 com o ZIP novo** | mesmas verificações e resultados |
| Saída volumosa (~33 MB, `lab_volumoso.py`, LAB_GENERIC) pelo executável | 3× SUCCEEDED | 3× SUCCEEDED | drenagem sem travar |
| Execuções sintéticas reais pela interface (E2E-005: AUT-01/02/03/05 × 2 rodadas, oráculos independentes) | 8/8 SUCCEEDED + PASS | **8/8 SUCCEEDED + PASS** | |

Cobertos pela bateria automatizada (inalterada e verde nos três ambientes): SUCCEEDED, FAILED, OUTCOME_UNKNOWN,
cancelamento, tempo esgotado, adoção após queda, perda explícita (`EVENT_GAP`), falhas de leitura e escrita, eventos sem
Framework, histórico vazio ou indisponível, exportação sanitizada, e os testes dos B-OBS-01/02/03 (`ActivityPersistenceTest`:
fsync incerto, fila cheia por linhas e por bytes, perda acumulada, sem duplicidade, linha do tempo nunca COMPLETA com
perda).

Não executado, de propósito: suspensão, hibernação e bloqueio físico (A13 adiado por decisão da titular; G2/OBS-G6
pendentes). Nenhum teste foi removido, desabilitado ou afrouxado.
