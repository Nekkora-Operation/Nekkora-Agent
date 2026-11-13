package com.nekkora.agent.pack;

import com.nekkora.agent.exec.ExecRefused;
import com.nekkora.agent.exec.ExecutionPackage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * AGENT-PACOTES-001: o ZIP importado, já conferido e extraído numa pasta PRIVADA da operação (nunca na pasta da pessoa).
 * Tudo o que vem daqui é dado não confiável: nomes saneados só para a tela; nada foi executado.
 *
 * @param source o ZIP escolhido (nunca alterado)
 * @param sourceSize tamanho do ZIP quando foi copiado
 * @param sourceModified data de modificação do ZIP quando foi copiado
 * @param sourceSha256 SHA-256 dos bytes copiados (os mesmos que foram lidos)
 * @param workDir pasta da operação (apagada ao concluir ou cancelar)
 * @param root a raiz do projeto reconhecida, no disco
 * @param rootFolder nome da pasta externa única do ZIP, tratada como raiz ({@code ""} se os arquivos estão na raiz do ZIP)
 * @param kind o que o ZIP é
 * @param existing o pacote existente conferido pelo validador do Agent ({@code EXTERNAL_PACKAGE}, {@code STUDIO_PACKAGE}, {@code LAB_PACKAGE})
 * @param refusal por que o pacote existente não vale ({@code *_INVALID}), com o código do validador
 * @param files todos os arquivos do projeto, relativos à raiz, com a regra padrão
 * @param pythonFiles os {@code .py} que podem ser entrada (em ordem)
 * @param suggestedEntry sugestão por nome convencional ({@code main.py}, {@code __main__.py}); NUNCA confirmada sozinha
 * @param declarations o que o projeto declara sobre Python e dependências
 * @param blockers impedimentos para gerar um pacote novo a partir deste ZIP (motivo e texto para a tela)
 * @param notes avisos que não impedem (descritor em subpasta, arquivos ignorados…)
 */
public record ImportedProject(Path source, long sourceSize, Instant sourceModified, String sourceSha256, Path workDir, Path root, String rootFolder, Kind kind,
                              ExecutionPackage existing, ExecRefused refusal, List<ProjectFile> files, List<String> pythonFiles, Optional<String> suggestedEntry,
                              Declarations declarations, List<ImportProblem> blockers, List<String> notes) {

    public ImportedProject {
        files = List.copyOf(files);
        pythonFiles = List.copyOf(pythonFiles);
        blockers = List.copyOf(blockers);
        notes = List.copyOf(notes);
    }

    /** O que o ZIP é. */
    public enum Kind {
        /** Código sem descritor de pacote: o assistente prepara um manifesto externo novo. */
        CODE("Projeto Python (código sem manifesto)"),
        /** Pacote Python externo válido ({@code nekkora-external.json}): reconhecido como está, sem regravar o descritor. */
        EXTERNAL_PACKAGE("Pacote Python externo já preparado (nekkora-external.json)"),
        /** Pacote no formato do Studio ({@code manifest.json}): segue pelo fluxo já suportado, sem conversão. */
        STUDIO_PACKAGE("Pacote do Studio (manifest.json)"),
        /** Pacote de laboratório ({@code nekkora-agent-lab.json}): segue pelo fluxo já suportado. */
        LAB_PACKAGE("Pacote de laboratório (nekkora-agent-lab.json)"),
        /** Descritor externo presente, mas o pacote não é válido. */
        EXTERNAL_INVALID("Pacote Python externo inválido"),
        /** Manifesto do Studio ou descritor de laboratório presente, mas o pacote não é válido. */
        STUDIO_INVALID("Pacote do Studio ou de laboratório inválido"),
        /** Descritores de formatos diferentes na raiz. */
        CONFLICT("Formatos de pacote conflitantes");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** É um pacote pronto que segue para a preparação da execução como está. */
        public boolean readyPackage() {
            return this == EXTERNAL_PACKAGE || this == STUDIO_PACKAGE || this == LAB_PACKAGE;
        }
    }

    /**
     * Um arquivo do projeto.
     *
     * @param path caminho relativo à raiz, com {@code /} (texto do ZIP; para a tela use {@link ZipNames#display})
     * @param size bytes
     * @param hash hash do conteúdo pela regra do pacote ({@code sha256:…}, texto com LF)
     * @param rule regra padrão
     * @param contractSafe o nome cabe no formato do pacote
     */
    public record ProjectFile(String path, long size, String hash, InclusionRules.Rule rule, boolean contractSafe) {}

    public Optional<ProjectFile> file(String path) {
        return files.stream().filter(f -> f.path().equals(path)).findFirst();
    }
}
