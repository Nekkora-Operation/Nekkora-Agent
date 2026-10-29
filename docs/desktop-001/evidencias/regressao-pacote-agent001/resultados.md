# Homologação do pacote — 2026-10-22 00:39

| Item | Valor |
|---|---|
| sistema | Microsoft Windows 11 Pro 10.0.26200 build 26200 (64 bits) |
| processador | AMD Ryzen 7 8700F 8-Core Processor (8 núcleos, 16 lógicos) |
| memoriaGiB | 31.2 |
| tela | 1920x1080 |
| escalaWindows | 100% |
| zip | nekkora-agent-0.1.0-windows-x64-lab.zip |
| zipSha256 | 7cc3d8b5d492480a3aee4d498ffb78f6c7ba3671f3d13b64a34ed3a935431505 |

| ID | Resultado | Detalhe |
|---|---|---|
| A14 | APROVADO | java no PATH reduzido: nenhum; JAVA_HOME removido; 95 módulos carregados, fora do pacote/Windows: 0; do JDK/Studio/.design-system: 0 |
| A02 | APROVADO | janela em 1645 ms (externo), startup_ms=1313 (log); processos: 2 (lançador + JVM); consoles: 0; ícone na bandeja: no estouro (Mostrar ícones ocultos) |
| A03 | APROVADO | 5/5 ciclos fechar (WM_CLOSE) → ícone da bandeja → janela de volta (clique real do mouse: 5; Invoke da UI Automation quando o Explorer não entregou o clique sintético: 0); ícones do Agent na bandeja: 1; log: 5 ocultações, 5 restaurações; processos: 2 |
| A04 | APROVADO | Sair pelo menu da bandeja: processos restantes 0, ícone visível 0, LIFE_EXIT registrado; reabriu com a trava (arquivo mantido: True); config igual antes/depois: True (tema claro aplicado na captura) |
| A05 | APROVADO | 3/3 rodadas com duas aberturas simultâneas: um único proprietário (1 LIFE_START, nenhuma configuração criada pela segunda), a outra mostrou o aviso e saiu; códigos de saída da segunda: 3,3,3 |
| A06 | APROVADO | TerminateProcess (sem saída ordenada) e novo início: jvm→reaberto em 1190 ms; lancador→reaberto em 1200 ms; jvm→reaberto em 1186 ms; trava adquirida em todos os 6 inícios, arquivo da trava mantido; ícones restantes após passar o mouse na bandeja: 0 (o Windows mantém o ícone de processo morto até o mouse passar) |
| A07 | APROVADO | --sem-bandeja: nenhum ícone; fechar → minimizada com processo vivo (True); Alt+S (Sair) pelo teclado encerrou: True. Falha real de instalação da bandeja: simulada no teste automatizado |
| A09 | APROVADO | COMPUTERNAME removido do ambiente do processo: código AGT-HOST-001 no log; a captura mostra 'Não disponível' |
| A10 | APROVADO | nada abriu sozinho (0); Espaço em 'Abrir Studio' → linha de comando do filho: C:\Windows\System32\charmap.exe (pai = JVM do Agent); não exercitado (-SemNavegador); com o filho ainda aberto o Agent saiu e reabriu (sem herdar a trava); destinos ausentes do log |
| A13 | MEDIDO | janela (externo) 1216/1200/1201 ms; startup_ms (log) 1068/1073/1063; WS visível 156,9/157,6/157,2 MiB; oculto 30 s: CPU 31.2 ms (0.104% de um núcleo), WS 157 MiB. Suspensão/retorno: não exercitado por este roteiro |
| D-05 | APROVADO | registro HKCU\Software\JavaSoft\Prefs\com\nekkora\ui\theme igual antes e depois de toda a homologação (inclui troca de tema por configuração); nenhum nó do Agent criado |
| SAIDAS | INFO | saídas ordenadas usadas pelo roteiro: 7 pelo menu da bandeja (clique direito real), 0 por Alt+S na janela quando o clique sintético não foi entregue |

## Métricas (A13)

```
[
    {
        "execucao":  1,
        "janelaMsExterno":  1216,
        "startupMsLog":  1068,
        "wsVisivelMiB":  156.9,
        "privadaVisivelMiB":  349.9,
        "threads":  63,
        "ocioso":  {
                       "segundos":  30,
                       "cpuMs":  31.2,
                       "cpuPctDeUmNucleo":  0.104,
                       "wsOcultoMiB":  157,
                       "privadaOcultoMiB":  349.8
                   }
    },
    {
        "execucao":  2,
        "janelaMsExterno":  1200,
        "startupMsLog":  1073,
        "wsVisivelMiB":  157.6,
        "privadaVisivelMiB":  351.3,
        "threads":  63
    },
    {
        "execucao":  3,
        "janelaMsExterno":  1201,
        "startupMsLog":  1063,
        "wsVisivelMiB":  157.2,
        "privadaVisivelMiB":  350.3,
        "threads":  63
    }
]
```
