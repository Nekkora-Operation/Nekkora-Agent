# PLATFORM-LICENCIAMENTO-001 — recusa de registro por licença, visível no Agent

## O problema

O Agent executa a automação **antes** de registrar a execução na Platform. No modelo por licença, a Platform pode recusar esse
registro (403 com `reason`: `license_required`, `license_expired` ou `run_not_covered`). Antes desta mudança, qualquer 403
virava `AGT-PLT-015` ("autorização recusada"), o motivo não era lido, o envio parava para sempre e a tela dizia "Envio parado",
sem ação possível. Para a pessoa, o envio sumia.

## O que muda

- **Motivo próprio.** Três códigos novos, um por motivo: `AGT-PLT-035` (sem licença), `AGT-PLT-036` (licença vencida) e
  `AGT-PLT-037` (execução não coberta no início). Um 403 sem esses motivos continua `AGT-PLT-015`.
- **Executou aqui × não registrada lá.** Na execução, o envio passa a dizer "Executou neste computador · não registrada na
  Platform: <motivo>". Não aparece como falha: a execução aconteceu. O aviso da janela principal e o diálogo "Envios parados"
  dizem as duas coisas em linhas separadas.
- **Nada é descartado.** Os eventos e o registro local continuam guardados e protegidos, como antes.
- **Tentar registrar de novo.** O diálogo oferece o botão para essa recusa. A Platform só aceita o registro tardio de uma
  execução que estava coberta **quando começou**. Renovar a licença depois vale para as próximas execuções, não para a que rodou
  sem ela, e o texto do diálogo diz isso. Se continuar sem cobertura, o envio para de novo, com o motivo de agora. Não há repetição em laço: só a pedido da pessoa.
- **Registro em log.** `PLATFORM_DELIVERY_BLOCKED` ganha `ranLocally` e `registeredOnPlatform`.

O formato do arquivo de entregas não muda (`blockedCode` já guardava o código).

## O que não muda

- A recusa não impede a execução local: o Agent executa antes de registrar. Quem impede trabalho novo é a Platform, ao não
  entregar pedido nem autorização sem licença.
- "Desistir e declarar perda" continua valendo só para evento recusado ou lacuna.

## Testes

`PlatformDeliveryTest.registrationRefusedByLicense` e `licenseReasons`; `PlatformTextTest.notRegisteredByLicense`.
`mvnw verify`: 424 testes, 0 falhas, 42 ignorados (o grupo de janela real, que usa Robot e não foi executado).

## Limite

A janela real e o executável empacotado do Agent **não** foram exercitados com esta mudança: os testes de janela do
repositório usam Robot e ficam fora desta missão. O texto e o estado foram conferidos nos testes de unidade.
