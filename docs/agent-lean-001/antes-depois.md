# AGENT-LEAN-001: antes e depois (medido)

Mesma máquina, mesmo JDK 21.0.12. Abertura e memória medidas no EXECUTÁVEL EMPACOTADO (ZIP extraído numa pasta nova,
PATH sem Java, dados descartáveis, nome de máquina fictício), 5 rodadas, 20 s ociosos com a janela aberta.

| Medida | Antes (`a3e5598`) | Depois (`feat/agent-lean-001`) | Diferença |
|---|---|---|---|
| Mudança | — | `-Xms16m` nas opções da JVM do pacote | código Java: **nenhuma** |
| ZIP | 35 716 578 bytes (`d649ad90…`) | 35 716 414 bytes (`cb4f860a…`) | igual (só o `.cfg` e o `VERSOES.txt` mudam) |
| Abertura (janela visível) | mediana **1 128 ms** (973–1 554) | mediana **1 151 ms** (1 133–1 549) | igual (ruído) |
| Conjunto de trabalho ocioso | mediana **149,1 MB** (148,7–149,7) | mediana **122,7 MB** (122,6–122,9) | **−26,4 MB (−18%)** |
| Memória privada ociosa | **~350 MB** (349,2–350,9) | **~108 MB** (107,8–108,5) | **−242 MB (−69%)** |
| Pico do conjunto de trabalho executando as automações reais da E2E-005 (aba Atividade aberta) | **237–243 MB** | **176–178 MB** | **−26%** |
| Duração das mesmas automações (AUT-01/02/03/05, 2 rodadas) | 10,0 / 8,7 / 4,1 / 24,6 s (r1) | 11,2 / 10,3 / 4,1 / 24,7 s (r1); 10,0 / 8,9 / 4,1 / 8,8 s (r2) | igual (variação de rede dos sandboxes públicos) |
| Saída volumosa (~33 MB de stdout/stderr, `lab_volumoso.py`, 3×) | SUCCEEDED em 0,2–0,3 s | SUCCEEDED em 0,2–0,3 s | igual |
| Testes Windows `clean verify` | 192 / 0 falhas / 1 pulado | 192 / 0 / 1 (casos idênticos) | — |
| Testes `-Pdesktop` (escalas 100/150/200%) | 215 casos (214 + 1 pulado) | 215 (idênticos) | — |
| Linux (Docker) | 192: 159 + 15 falhas da base + 18 pulados | idem, caso a caso | — |
| Clone Git | 6,8 MB rastreados | +4 documentos `docs/agent-lean-001` | nenhum arquivo removido |

O teto do heap (`-Xmx256m`), o GC serial e o `TieredStopAtLevel=1` são os mesmos: o Agent pode usar a mesma memória
máxima de antes, só não a reserva inteira na abertura.

## Conclusão

**`LEAN_ACCEPTANCE_READY`**: redução medida e grande na memória (privada −69% ociosa; conjunto de trabalho −18%
ocioso e −26% no pico em execução real), sem perda de função, com os mesmos casos de teste nos três ambientes, o ensaio
de 25 verificações, o roteiro de pacote da OBS-001 e as automações da E2E-005 verdes pelo pacote novo. O peso do ZIP
não muda: o runtime já era reduzido por `jlink`, e os candidatos restantes valem menos de 1% (ver `decisoes.md`).

As pendências físicas da DESKTOP-001 continuam como estavam: G2 e OBS-G6 pendentes, A13 adiado. O ensaio não
substitui a homologação física.

## Séries complementares pedidas na revisão (24/10, noite)

Mesmos cenários, ZIP antigo × novo intercalados. Detalhes, GC e roteiros estão no pacote de revisão.

| Série | Antes | Depois |
|---|---|---|
| 1ª abertura após extrair (3 extrações) | 1 318 / 1 477 / 1 293 ms | 1 477 / 1 491 / 1 346 ms |
| Aberturas seguintes (9), mediana | 1 014 ms | 1 008 ms |
| 10 execuções seguidas, saída volumosa, mesma instância | 10/10; pico 236 MB; privada ~393 MB | 10/10; pico 173 MB; privada 148 → 153 MB |
| 10 execuções seguidas, AUT-03 (Framework + portal local) | 10/10; 4,1 s; pico 245 MB; privada ~396 MB | 10/10; 4,0 s; pico 186 MB; privada ~159 MB |
| GC (`-Xlog:gc`), saída volumosa | 3 coletas; pausas 51 ms no total, máx. 22 ms; heap reservado 247 MB | 32 coletas; 70 ms no total, máx. 19 ms; reservado ≤ 19 MB |
| GC, AUT-03 × 10 | 8 coletas; 61 ms, máx. 23 ms | 86 coletas; 144 ms, máx. 21 ms |

Mais coletas jovens, cada uma de ~1 ms. A pausa máxima e a duração das execuções não mudam, e não há crescimento de
memória ao longo das repetições.

**Achado F-AGT-01 (presente nos DOIS ZIPs; não é regressão):** no Windows, a troca atômica do `estado.json` falha se
outro processo estiver com o arquivo aberto, mesmo com compartilhamento total. Quando isso acontece na gravação de
`RUNNING`, o processo já lançado fica sem supervisão e o registro fica em `STARTING`. A correção toca o
`ExecutionController` e aguarda autorização.
