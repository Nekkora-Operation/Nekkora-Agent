package com.nekkora.agent.platform;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.exec.ExecutionRecord;
import com.nekkora.agent.exec.ExecutionState;
import com.nekkora.agent.exec.RunStore;
import com.nekkora.agent.obs.ActivityCodec;
import com.nekkora.agent.obs.ActivityEvent;
import com.nekkora.agent.storage.AtomicWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Apoio dos testes do transporte: tudo determinístico (relógio manual, rodadas chamadas diretamente, sem pausas). */
final class PlatformFixtures {
    private PlatformFixtures() {}

    static final String FP = "5664f1a56556e8137dc6e62c435485469e18501d853a8f51268cb377ce1c0b3d";
    static final Instant T0 = Instant.parse("2026-10-28T12:00:00Z");

    static final class ManualClock extends Clock {
        volatile Instant now;

        ManualClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Guarda de segredos em memória (o DPAPI tem teste próprio no Windows); falha de gravação injetável. */
    static final class MemorySecrets implements SecretStore {
        final Map<String, byte[]> data = new ConcurrentHashMap<>();
        volatile boolean available = true;
        volatile int failWrites;
        final List<String> order = new CopyOnWriteArrayList<>();

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public void write(String name, byte[] secret) throws IOException {
            SecretStore.checkName(name);
            if (failWrites > 0) {
                failWrites--;
                throw new IOException("falha injetada");
            }
            order.add("write:" + name);
            data.put(name, secret.clone());
        }

        @Override
        public Optional<byte[]> read(String name) {
            return Optional.ofNullable(data.get(name)).map(byte[]::clone);
        }

        @Override
        public void delete(String name) {
            order.add("delete:" + name);
            data.remove(name);
        }

        String text(String name) {
            byte[] b = data.get(name);
            return b == null ? null : new String(b, StandardCharsets.US_ASCII);
        }
    }

    /** Diagnóstico registrado (para conferir o que vai ao log — e o que não vai). */
    static final class Recorder implements PlatformService.Diagnostics {
        final List<String> lines = new CopyOnWriteArrayList<>();

        @Override
        public void paired(UUID agentId) {
            lines.add("paired " + agentId);
        }

        @Override
        public void pairingRefused(ErrorCode why) {
            lines.add("pairingRefused " + why.code());
        }

        @Override
        public void rotated() {
            lines.add("rotated");
        }

        @Override
        public void rotationRefused(ErrorCode why) {
            lines.add("rotationRefused " + why.code());
        }

        @Override
        public void credentialRefused() {
            lines.add("credentialRefused");
        }

        @Override
        public void disconnected(ErrorCode why) {
            lines.add("disconnected " + why.code());
        }

        @Override
        public void blocked(ExecutionId id, ErrorCode why, Long sequence) {
            lines.add("blocked " + id + " " + why.code() + " " + sequence);
        }

        @Override
        public void delivered(ExecutionId id, long settled) {
            lines.add("delivered " + id + " " + settled);
        }

        @Override
        public void lossDeclared(ExecutionId id, long count) {
            lines.add("lossDeclared " + id + " " + count);
        }

        @Override
        public void responseRejected(ExecutionId id, String why) {
            lines.add("responseRejected " + why);
        }

        @Override
        public void report(ExecutionId id, ExecutionState state, String outcome) {
            lines.add("report " + state + " " + outcome);
        }

        boolean has(String prefix) {
            return lines.stream().anyMatch(l -> l.startsWith(prefix));
        }
    }

    /** Agent com transporte, gateway simulado e execuções gravadas numa pasta temporária. */
    static final class Lab {
        final Path root;
        final RunStore runs;
        final ManualClock clock = new ManualClock(T0);
        final FakeGateway gw = new FakeGateway(clock);
        final MemorySecrets secrets = new MemorySecrets();
        final Recorder diag = new Recorder();
        final Map<ExecutionId, Long> durable = new HashMap<>();
        volatile String url = FakeGateway.ORIGIN;
        PlatformService svc;
        final UUID org = UUID.randomUUID();
        UUID worker;

        Lab(Path root) {
            this.root = root;
            this.runs = new RunStore(root.resolve("execucoes"), AtomicWriter.DEFAULT);
            this.svc = service();
        }

        PlatformService service() {
            PlatformService s = new PlatformService(root.resolve("plataforma"), () -> url, runs,
                    id -> durable.containsKey(id) ? OptionalLong.of(durable.get(id)) : OptionalLong.empty(), secrets, gw, AtomicWriter.DEFAULT, clock, "0.1.0+teste",
                    () -> 0.5);
            s.diagnostics(diag);
            s.load();
            return s;
        }

        /** "Reinício" do Agent: novo serviço lendo o mesmo disco (a memória anterior é descartada). */
        void restart() {
            svc.close();
            svc = service();
        }

        void tick(int n) {
            for (int i = 0; i < n; i++) svc.tick();
        }

        /** Até não haver mais trabalho imediato (o relógio avança o suficiente para qualquer espera). */
        void settle() {
            for (int i = 0; i < 40; i++) {
                clock.advance(Duration.ofMinutes(6));
                svc.tick();
            }
        }

        /** Pareado de verdade com o gateway simulado e com as autorizações baixadas. */
        UUID paired() {
            worker = gw.worker();
            PlatformService.Action a = svc.pair(url, gw.pairingCode(org, worker).toCharArray());
            if (!a.accepted()) throw new AssertionError(a.problem());
            tick(2);
            return gw.agents.keySet().iterator().next();
        }

        ExecutionId execution(ExecutionState state, Instant created) throws Exception {
            ExecutionId id = ExecutionId.random();
            save(id, state, created);
            return id;
        }

        void save(ExecutionId id, ExecutionState state, Instant created) throws Exception {
            boolean started = state != ExecutionState.READY && state != ExecutionState.STARTING && state != ExecutionState.BLOCKED;
            ErrorCode code = switch (state) {
                case OUTCOME_UNKNOWN -> ErrorCode.EXEC_AGENT_RESTARTED;
                case FAILED -> ErrorCode.EXEC_REPORTED_FAILURE;
                case CANCELLED -> ErrorCode.EXEC_CANCELLED;
                default -> null;
            };
            runs.save(new ExecutionRecord(id, ExecutionPackage.Kind.STUDIO_PYTHON, "pacote 5664f1a56556", "sha256:" + FP, state, code, created,
                    started ? created.plusMillis(900) : null, state.terminal() ? created.plusSeconds(9) : null, null, state.terminal() ? 0 : null,
                    state == ExecutionState.SUCCEEDED ? Map.of("SUCCEEDED", 5L) : Map.of(), 0, 0, false, 0));
        }

        /** Autorização e vínculo da execução como o app faz (intenção antes do início, vínculo depois). */
        void link(ExecutionId id, UUID authorization) {
            PlatformService.Action a = svc.prepare(authorization, "sha256:" + FP);
            if (!a.accepted()) throw new AssertionError(a.problem());
            svc.link(id);
        }

        Path activity(ExecutionId id) {
            return runs.dir(id).resolve(EventReader.FILE);
        }

        /** Acrescenta eventos com as sequências dadas (como a coleta grava: um por linha). */
        void events(ExecutionId id, long from, long to) throws IOException {
            List<String> lines = new ArrayList<>();
            for (long s = from; s <= to; s++) lines.add(line(id, s, "STEP_COMPLETED"));
            append(id, lines);
        }

        void append(ExecutionId id, List<String> lines) throws IOException {
            Files.createDirectories(runs.dir(id));
            StringBuilder b = new StringBuilder();
            for (String l : lines) b.append(l).append('\n');
            Files.writeString(activity(id), b, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        String line(ExecutionId id, long seq, String code) {
            return ActivityCodec.encode(ActivityEvent.of(id.value(), clock.instant(), ActivityEvent.Source.AGENT, code, ActivityEvent.Level.INFO,
                    ActivityEvent.Category.ACTIVITY).status("OK").build().withSequence(seq));
        }

        String gap009(ExecutionId id, long seq, long lost) {
            return ActivityCodec.encode(ActivityEvent.of(id.value(), clock.instant(), ActivityEvent.Source.COLLECTOR, "EVENT_GAP", ActivityEvent.Level.WARNING,
                    ActivityEvent.Category.TELEMETRY).error(ErrorCode.OBS_STORE_FAILED.code()).status("ACTIVITY_STORE").count("lost", lost).build()
                    .withSequence(seq));
        }

        String closing(ExecutionId id, long seq) {
            return ActivityCodec.encode(ActivityEvent.of(id.value(), clock.instant(), ActivityEvent.Source.COLLECTOR, "COLLECTION_COMPLETED",
                    ActivityEvent.Level.INFO, ActivityEvent.Category.TELEMETRY).build().withSequence(seq));
        }

        /** Tudo o que o Agent gravou em disco (para procurar segredos). */
        String allFiles() throws IOException {
            StringBuilder b = new StringBuilder();
            try (var s = Files.walk(root)) {
                for (Path p : s.filter(Files::isRegularFile).toList()) b.append(p).append('\n').append(Files.readString(p, StandardCharsets.ISO_8859_1)).append('\n');
            }
            return b.toString();
        }
    }
}
