# PA-02 — cliente HTTPS, pareamento, credencial DPAPI, resposta perdida, rotação, heartbeat e autorizações offline

## Diff (PR #1, `7791cd8` + `d3e0f8a`)

Revisão: `git diff 932e61c d3e0f8a -- <arquivos abaixo>`.

| Arquivo | Papel |
|---|---|
| `platform/HttpTransport.java`, `platform/JdkHttpTransport.java` | único cliente de rede (`java.net.http`). HTTPS com validação normal; sem seguir redirecionamento; 10 s para conectar, 30 s para responder; resposta de até 1 MiB; `http` recusado |
| `platform/Destination.java` | origem `https` do pareamento, gravada no vínculo. Mudança de configuração = nada é enviado (D-AP-8) |
| `platform/SecretStore.java`, `platform/DpapiSecretStore.java` | DPAPI do usuário (JNA 5.17.0, entropia `nekkora-agent/platform-secret/1`). Fora do Windows o pareamento é recusado, nunca vai para arquivo comum (D-AP-10) |
| `platform/CredentialVault.java` | `vinculo.json` (sem segredo), segredos `credencial` e `resgate` |
| `platform/MachineClient.java`, `platform/Json.java` | chamadas do contrato (§4, §5, §6.1) e leitura estrita das respostas |
| `platform/AuthorizationCache.java` | autorizações válidas guardadas para uso offline, só dentro de `validUntil` (D-AP-2) |
| `platform/Backoff.java` | espera crescente com aleatoriedade; `Retry-After` respeitado até 1 h |
| `platform/PlatformService.java` (parte de pareamento, rotação, presença e autorizações) | linha de fundo `nekkora-agent-plataforma`: resgate pendente → presença (60 s) → autorizações (5 min) |
| `ui/PairingDialog.java`, `ui/MainWindow.java`, `ui/PlatformText.java`, `app/PlatformLog.java` | card Platform, pareamento (Alt+A) e nova credencial (Alt+N). Código em campo de senha, nunca exibido nem registrado |
| `pom.xml`, `tools/package-windows.ps1` | `jna-platform` 5.17.0; módulos `java.net.http` e `jdk.crypto.ec` no runtime |

## Comportamento

- **Pareamento (§4.2):** o segredo é gerado na máquina (32 bytes, `SecureRandom`) e o material do resgate vai para o DPAPI
  **antes** de qualquer tentativa. Uma resposta perdida é repetida com o **mesmo** material, sem trancar a máquina.
- **Rotação (§4.3):** a credencial anterior só sai depois da confirmação. Um reinício no meio conclui pela repetição idempotente.
- **401:** o transporte para **inteiro** na primeira recusa, sem insistir (D-AP-9).
- **Heartbeat (§5):** a cada 60 s, com a versão do contrato e as capacidades.

## Testes (transporte simulado `FakeGateway`, falhas injetadas e identificadas)

| Teste | Controle |
|---|---|
| P1 | material guardado antes de qualquer tentativa; resposta perdida repetida com o mesmo material |
| P2 | mesmo código com outro segredo é recusado e não cria nada |
| P3 | repetição depois de rotação ou revogação é recusada |
| P4, P5 | queda no meio da rotação, nos dois pontos: o reinício conclui |
| P6, P7 | código malformado, `http`, sem proteção do sistema, endereço alterado e `3xx`: nada sai |
| P8 | credencial, segredo e código nunca em arquivo comum, diagnóstico ou `toString` |
| H1–H3 (HTTPS **real** local) | certificado confiável funciona; não confiável e nome divergente falham; redirecionamento nunca é seguido; `http` recusado; resposta acima do limite é falha |
| S1, S2 | DPAPI ida e volta, arquivo sem o texto do segredo; sem proteção do sistema, nada é gravado |
| `ArchitectureTest` | rede só no transporte, com contraprova; o executor e a coleta não citam o transporte nem por nome completo (`executorAndCollectorNeverReachTheTransport`, novo nesta missão, com contraprova e mutação M5) |

## Evidência real

- AGENT-PLATFORM-001: J2 (pareamento pela janela), J8 (revogação: uma recusa, transporte parado, execução local continua).
- Esta missão: C1 (pareamento), C2 (heartbeat no banco), C3 (início offline pela autorização guardada), C7 (**rotação real**
  pela janela). Ver [pa-05](pa-05-homologacao.md).

## Riscos

- DPAPI só no Windows; o JNA extrai a biblioteca nativa para a pasta temporária do usuário na primeira proteção.
- Não há proxy HTTP: a conexão é direta.
- O uso offline confia no relógio local para `validUntil` (D-AP-2).
