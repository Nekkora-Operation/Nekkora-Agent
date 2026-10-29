package com.nekkora.agent.exec;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.storage.AtomicWriter;
import com.nekkora.agent.storage.StrictJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aprovações locais de pacotes ({@code nekkora-agent/approvals} 1.0.0): a pessoa operadora aprova UM pacote pela sua
 * impressão digital. Arquivo mudado → outra impressão → sem aprovação. Guarda só a impressão, o tipo, um rótulo derivado
 * da impressão e a data — nenhum nome de pasta, caminho, entrada ou dado. Arquivo ilegível vale como "nenhuma aprovação" (nada é presumido aprovado).
 */
public final class ApprovalStore {
    public static final String SCHEMA = "nekkora-agent/approvals";
    public static final String VERSION = "1.0.0";
    static final int MAX = 200;

    public record Approval(String fingerprint, ExecutionPackage.Kind kind, String name, Instant at) {}

    private final Path file;
    private final AtomicWriter writer;

    public ApprovalStore(Path file, AtomicWriter writer) {
        this.file = file;
        this.writer = writer;
    }

    public synchronized boolean approved(ExecutionPackage p) {
        return load().stream().anyMatch(a -> a.fingerprint().equals(p.fingerprint()) && a.kind() == p.kind());
    }

    /** Registra a aprovação (idempotente). */
    public synchronized void approve(ExecutionPackage p, Instant at) throws ExecRefused {
        List<Approval> all = new ArrayList<>(load());
        all.removeIf(a -> a.fingerprint().equals(p.fingerprint()));
        // nunca o nome da pasta (pode ser o nome de um cliente): só o resumo da impressão digital
        all.add(new Approval(p.fingerprint(), p.kind(), "pacote " + p.shortFingerprint(), at));
        while (all.size() > MAX) all.remove(0);
        save(all);
    }

    /** Retira a aprovação de um pacote (o pacote volta a precisar de aprovação). */
    public synchronized void revoke(ExecutionPackage p) throws ExecRefused {
        List<Approval> all = new ArrayList<>(load());
        if (all.removeIf(a -> a.fingerprint().equals(p.fingerprint()))) save(all);
    }

    List<Approval> load() {
        if (!Files.isRegularFile(file)) return List.of();
        try {
            Map<String, Object> m = StrictJson.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            if (!SCHEMA.equals(m.get("schema")) || !VERSION.equals(m.get("version")) || !(m.get("approvals") instanceof Map<?, ?> list)) return List.of();
            List<Approval> out = new ArrayList<>();
            for (Object o : list.values()) {
                if (!(o instanceof Map<?, ?> a) || !(a.get("fingerprint") instanceof String fp) || !fp.matches("sha256:[0-9a-f]{64}")
                        || !(a.get("kind") instanceof String k) || !(a.get("name") instanceof String n) || !(a.get("at") instanceof String at)) {
                    return List.of();
                }
                out.add(new Approval(fp, ExecutionPackage.Kind.valueOf(k), n, Instant.parse(at)));
            }
            return out;
        } catch (IOException | StrictJson.SyntaxException | RuntimeException e) {
            return List.of();
        }
    }

    private void save(List<Approval> all) throws ExecRefused {
        // StrictJson não tem listas: as aprovações vão como objeto indexado pela ordem
        StringBuilder b = new StringBuilder("{\n  \"schema\": ").append(StrictJson.quote(SCHEMA)).append(",\n  \"version\": ")
                .append(StrictJson.quote(VERSION)).append(",\n  \"approvals\": {");
        Map<String, Approval> indexed = new LinkedHashMap<>();
        for (int i = 0; i < all.size(); i++) indexed.put(String.format("%04d", i), all.get(i));
        int n = 0;
        for (Map.Entry<String, Approval> e : indexed.entrySet()) {
            Approval a = e.getValue();
            b.append(n++ == 0 ? "\n" : ",\n").append("    ").append(StrictJson.quote(e.getKey())).append(": {\"fingerprint\": ")
                    .append(StrictJson.quote(a.fingerprint())).append(", \"kind\": ").append(StrictJson.quote(a.kind().name())).append(", \"name\": ")
                    .append(StrictJson.quote(a.name())).append(", \"at\": ").append(StrictJson.quote(a.at().toString())).append('}');
        }
        b.append(all.isEmpty() ? "}\n}\n" : "\n  }\n}\n");
        try {
            Files.createDirectories(file.getParent());
            writer.write(file, b.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ExecRefused(ErrorCode.EXEC_STORAGE_FAILED);
        }
    }
}
