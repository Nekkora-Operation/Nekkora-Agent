# Avisos de terceiros — Nekkora Agent

O pacote de laboratório do Nekkora Agent inclui os componentes abaixo. Os textos completos das
licenças estão na pasta `legal\` do pacote (e, para o runtime Java, em `runtime\legal\`).

| Componente | Versão | Origem | Licença | Texto no pacote |
|---|---|---|---|---|
| FlatLaf | 3.7.2 | `com.formdev:flatlaf` (Maven Central) | Apache License 2.0 | `legal\FlatLaf-LICENSE-Apache-2.0.txt` |
| Fonte Inter | a da biblioteca Nekkora Swing UI | dentro de `nekkora-swing-ui` | SIL Open Font License 1.1 | `legal\OFL-Inter.txt` |
| Fonte Sora | a da biblioteca Nekkora Swing UI | dentro de `nekkora-swing-ui` | SIL Open Font License 1.1 | `legal\OFL-Sora.txt` |
| Fonte JetBrains Mono | a da biblioteca Nekkora Swing UI | dentro de `nekkora-swing-ui` | SIL Open Font License 1.1 | `legal\OFL-JetBrainsMono.txt` |
| Runtime Java (OpenJDK, Microsoft Build of OpenJDK 21) | ver `VERSOES.txt` | JDK usado no empacotamento (jlink) | GPLv2 com Classpath Exception e avisos de terceiros do OpenJDK | `runtime\legal\` |

Componentes Nekkora incluídos (`nekkora-design-tokens`, `nekkora-swing-ui`): construídos a partir do
repositório Nekkora-Studio no commit registrado em `VERSOES.txt`; seguem a licença definida pela
Nekkora para aquele repositório.

O Maven Wrapper (`mvnw`, `mvnw.cmd`, Apache License 2.0) é usado apenas na construção e não faz
parte do pacote.
