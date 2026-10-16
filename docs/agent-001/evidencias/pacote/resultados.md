# Homologação do pacote — 2026-10-16 19:16

| Item | Valor |
|---|---|
| sistema | Microsoft Windows 11 Pro 10.0.26200 build 26200 (64 bits) |
| processador | AMD Ryzen 7 8700F 8-Core Processor (8 núcleos, 16 lógicos) |
| memoriaGiB | 31.2 |
| tela | 1920x1080 |
| escalaWindows | 100% |
| zip | nekkora-agent-0.1.0-windows-x64-lab.zip |
| zipSha256 | 2a855a721c094c44cda7c22d3e398883b441accad3905e2983d0396b51e4c39d |

| ID | Resultado | Detalhe |
|---|---|---|
| A14 | APROVADO | java no PATH reduzido: nenhum; JAVA_HOME removido; 95 módulos carregados, fora do pacote/Windows: 0; do JDK/Studio/.design-system: 0 |
| A02 | APROVADO | janela em 1717 ms (externo), startup_ms=1346 (log); processos: 2 (lançador + JVM); consoles: 0; ícone na bandeja: no estouro (Mostrar ícones ocultos) |
| A03 | APROVADO | 10/10 ciclos fechar (WM_CLOSE) → ícone da bandeja → janela de volta (clique real do mouse: 0; Invoke da UI Automation quando o Explorer não entregou o clique sintético: 10); ícones do Agent na bandeja: 1; log: 10 ocultações, 10 restaurações; processos: 2 |
| A04 | APROVADO | Sair pelo menu da bandeja: processos restantes 0, ícone visível 0, LIFE_EXIT registrado; reabriu com a trava (arquivo mantido: True); config igual antes/depois: True (tema claro aplicado na captura) |
| A05 | APROVADO | 5/5 rodadas com duas aberturas simultâneas: um único proprietário (1 LIFE_START, nenhuma configuração criada pela segunda), a outra mostrou o aviso e saiu; códigos de saída da segunda: 3,3,3,3,3 |
| A06 | APROVADO | TerminateProcess (sem saída ordenada) e novo início: jvm→reaberto em 1216 ms; lancador→reaberto em 1199 ms; jvm→reaberto em 1228 ms; trava adquirida em todos os 6 inícios, arquivo da trava mantido; ícones restantes após passar o mouse na bandeja: 0 (o Windows mantém o ícone de processo morto até o mouse passar) |
| A07 | APROVADO | --sem-bandeja: nenhum ícone; fechar → minimizada com processo vivo (True); Alt+S (Sair) pelo teclado encerrou: True. Falha real de instalação da bandeja: simulada no teste automatizado |
| A09 | APROVADO | COMPUTERNAME removido do ambiente do processo: código AGT-HOST-001 no log; a captura mostra 'Não disponível' |
| A10 | APROVADO | nada abriu sozinho (0); Espaço em 'Abrir Studio' → linha de comando do filho: C:\Windows\System32\charmap.exe (pai = JVM do Agent); navegador padrão pediu 'GET /nekkora-agent-homologacao HTTP/1.1'; com o filho ainda aberto o Agent saiu e reabriu (sem herdar a trava); destinos ausentes do log |
| A13 | MEDIDO | janela (externo) 1240/1191/1230/1237/1213 ms; startup_ms (log) 1037/1036/1058/1042/1035; WS visível 153,4/151,7/152,9/153,8/152 MiB; oculto 60 s: CPU 46.9 ms (0.078% de um núcleo), WS 153.4 MiB. Suspensão/retorno: não exercitado por este roteiro |
| D-05 | APROVADO | registro HKCU\Software\JavaSoft\Prefs\com\nekkora\ui\theme igual antes e depois de toda a homologação (inclui troca de tema por configuração); nenhum nó do Agent criado |
| SAIDAS | INFO | saídas ordenadas usadas pelo roteiro: 2 pelo menu da bandeja (clique direito real), 7 por Alt+S na janela quando o clique sintético não foi entregue |

## Métricas (A13)

```
[
    {
        "execucao":  1,
        "janelaMsExterno":  1240,
        "startupMsLog":  1037,
        "wsVisivelMiB":  153.4,
        "privadaVisivelMiB":  353.7,
        "threads":  63,
        "ocioso":  {
                       "segundos":  60,
                       "cpuMs":  46.9,
                       "cpuPctDeUmNucleo":  0.078,
                       "wsOcultoMiB":  153.4,
                       "privadaOcultoMiB":  353.6
                   }
    },
    {
        "execucao":  2,
        "janelaMsExterno":  1191,
        "startupMsLog":  1036,
        "wsVisivelMiB":  151.7,
        "privadaVisivelMiB":  351.6,
        "threads":  48
    },
    {
        "execucao":  3,
        "janelaMsExterno":  1230,
        "startupMsLog":  1058,
        "wsVisivelMiB":  152.9,
        "privadaVisivelMiB":  353,
        "threads":  63
    },
    {
        "execucao":  4,
        "janelaMsExterno":  1237,
        "startupMsLog":  1042,
        "wsVisivelMiB":  153.8,
        "privadaVisivelMiB":  353.7,
        "threads":  64
    },
    {
        "execucao":  5,
        "janelaMsExterno":  1213,
        "startupMsLog":  1035,
        "wsVisivelMiB":  152,
        "privadaVisivelMiB":  351.1,
        "threads":  47
    }
]
```
