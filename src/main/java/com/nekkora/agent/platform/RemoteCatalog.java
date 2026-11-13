package com.nekkora.agent.platform;

import com.nekkora.agent.platform.PlatformService.LinkCheckOutcome;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PLATFORM-AGENT-CATALOGO-001 T02: o catálogo da máquina lido da Platform ({@code GET /agent/api/v1/catalog}). É o SUBCONJUNTO
 * autorizado ao Worker desta máquina, não a lista da organização: a ausência de um projeto nunca prova que ele não existe.
 * Só metadados (nomes, números, impressões); nada aqui autoriza executar, aprovar ou publicar.
 *
 * @param state       COMPLETE: todas as páginas lidas nesta consulta; PARTIAL: algumas páginas e depois falha (ou teto de
 *                    páginas); UNAVAILABLE: nenhuma página; UNSUPPORTED: a Platform conectada não tem a rota (API anterior)
 * @param problem     por que não está completo (nulo em COMPLETE)
 * @param observedAt  hora do SERVIDOR na primeira página lida (nulo sem página)
 */
public record RemoteCatalog(State state, LinkCheckOutcome problem, Optional<Long> retryAfterSeconds, Organization organization, List<Project> projects,
                            Instant observedAt) {

    public enum State { COMPLETE, PARTIAL, UNAVAILABLE, UNSUPPORTED }

    public RemoteCatalog {
        projects = List.copyOf(projects);
        retryAfterSeconds = retryAfterSeconds == null ? Optional.empty() : retryAfterSeconds;
    }

    /** A organização do vínculo, com o nome de exibição informado pela Platform. */
    public record Organization(UUID id, String displayName) {}

    /**
     * Versão visível a esta máquina. {@code versionNumber}: número INTERNO da Platform (não é semver). {@code packageVersion}:
     * versão DECLARADA no pacote externo, quando a Platform a tem. {@code fingerprint}: com o prefixo {@code sha256:}, pela
     * regra {@code rule} (inclui o descritor do pacote; não é o SHA-256 dos bytes do ZIP).
     */
    public record Version(UUID automationVersionId, int versionNumber, Instant publishedAt, String sourceKind, String rule, String fingerprint,
                          String packageName, String packageVersion) {}

    /** Projeto (automação) visível. {@code versions} vazio: visível sem versão elegível (só monitoramento). */
    public record Project(UUID workspaceId, String workspaceName, UUID automationId, String automationName, List<Version> versions,
                          boolean versionsTruncated) {
        public Project {
            versions = List.copyOf(versions);
        }
    }

    public static RemoteCatalog unavailable(LinkCheckOutcome why, Optional<Long> retry) {
        return new RemoteCatalog(State.UNAVAILABLE, why, retry, null, List.of(), null);
    }

    public static RemoteCatalog unsupported() {
        return new RemoteCatalog(State.UNSUPPORTED, null, Optional.empty(), null, List.of(), null);
    }

    public boolean complete() {
        return state == State.COMPLETE;
    }

    /** Algo foi lido (completo ou parcial): dá para mostrar e escolher, nunca para concluir "não existe". */
    public boolean readable() {
        return state == State.COMPLETE || state == State.PARTIAL;
    }

    public Optional<Project> project(UUID automationId) {
        return projects.stream().filter(p -> p.automationId().equals(automationId)).findFirst();
    }

    // ------------------------------------------------------------------ versão declarada (semver simples)

    private static final Pattern SEMVER = Pattern.compile("(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,5})");

    /** MAIOR.MENOR.CORREÇÃO do contrato do pacote externo; vazio se não for (sem conversão inventada). */
    public static Optional<int[]> semver(String v) {
        if (v == null) return Optional.empty();
        Matcher m = SEMVER.matcher(v);
        if (!m.matches()) return Optional.empty();
        return Optional.of(new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))});
    }

    /** Ordem numérica (1.10.0 depois de 1.9.0), nunca lexicográfica. Só para versões já válidas por {@link #semver}. */
    public static final Comparator<String> SEMVER_ORDER = (a, b) -> {
        int[] x = semver(a).orElseThrow();
        int[] y = semver(b).orElseThrow();
        for (int i = 0; i < 3; i++) if (x[i] != y[i]) return Integer.compare(x[i], y[i]);
        return 0;
    };

    /** A maior versão declarada válida entre as versões visíveis (vazio se nenhuma tem). */
    public static Optional<Version> highestDeclared(List<Version> versions) {
        return versions.stream().filter(v -> semver(v.packageVersion()).isPresent()).max((a, b) -> SEMVER_ORDER.compare(a.packageVersion(), b.packageVersion()));
    }

    /** Próxima correção: 1.10.0 → 1.10.1. */
    public static Optional<String> nextPatch(String v) {
        return semver(v).filter(x -> x[2] < 999_999).map(x -> x[0] + "." + x[1] + "." + (x[2] + 1));
    }
}
