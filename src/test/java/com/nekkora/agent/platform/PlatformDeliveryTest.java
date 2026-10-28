package com.nekkora.agent.platform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.exec.ExecutionState;
import com.nekkora.agent.platform.FakeGateway.Fault;
import com.nekkora.agent.platform.PlatformFixtures.Lab;
import com.nekkora.agent.platform.PlatformStatus.LinkState;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Registro, relatórios do supervisor e entrega durável dos eventos (contrato sec. 6.3, 7, 8 e 12.8), contra o
 * TRANSPORTE SIMULADO ({@link FakeGateway}); quedas e respostas adulteradas são falhas INJETADAS.
 */
class PlatformDeliveryTest {
    @TempDir
    Path dir;

    /** Pareado, com uma autorização para a impressão do pacote já baixada. */
    private Lab ready() {
        Lab lab = new Lab(dir);
        lab.paired();
        lab.gw.authorize(lab.org, lab.worker, PlatformFixtures.FP, PlatformFixtures.T0.plus(Duration.ofDays(30)));
        lab.svc.refreshNow();
        lab.tick(1);
        return lab;
    }

    private UUID authorization(Lab lab) {
        return lab.gw.authorizations.keySet().iterator().next();
    }

    private static List<Long> sequencesSent(String body) {
        List<Long> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"sequence\":(\\d+)").matcher(body);
        while (m.find()) out.add(Long.parseLong(m.group(1)));
        return out;
    }

    private List<String> eventBodies(Lab lab) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lab.gw.requests.size(); i++) if (lab.gw.requests.get(i).uri().getPath().endsWith("/events")) out.add(lab.gw.bodies.get(i));
        return out;
    }

    @Test
    @DisplayName("D1: execução registrada sob a autorização, com eventos em ordem, desfecho do supervisor e envio concluído")
    void registeredWithEventsOutcomeAndCompletion() throws Exception {
        Lab lab = ready();
        ExecutionId id = lab.execution(ExecutionState.RUNNING, lab.clock.instant());
        lab.events(id, 1, 3);
        lab.link(id, authorization(lab));
        lab.tick(1);
        FakeGateway.Run run = lab.gw.run(id.value());
        assertNotNull(run, "registrada");
        assertEquals("RUNNING", run.state);
        assertEquals(authorization(lab), run.authorization);
        assertEquals(PlatformFixtures.FP, run.fingerprint, "impressão do Agent, sem o prefixo, nunca o hash de um artefato");
        assertEquals(3, run.settled);
        assertEquals(DeliveryView.State.PENDING, lab.svc.delivery(id).state(), "automação em andamento: envio pendente");
        // o supervisor termina; a coleta fecha
        lab.save(id, ExecutionState.SUCCEEDED, lab.clock.instant());
        lab.events(id, 4, 5);
        lab.append(id, List.of(lab.closing(id, 6)));
        lab.tick(2);
        assertEquals("SUCCEEDED", run.state);
        assertEquals(List.of("APPLIED", "APPLIED"), run.outcomes);
        assertEquals(6, run.stored);
        assertEquals(6, run.settled);
        assertEquals(DeliveryView.State.DELIVERED, lab.svc.delivery(id).state());
        assertFalse(lab.svc.retains(id), "entregue: a retenção local pode agir");
        long runCalls = lab.gw.requests.stream().filter(r -> r.uri().getPath().contains(id.value())).count();
        lab.settle();
        assertEquals(runCalls, lab.gw.requests.stream().filter(r -> r.uri().getPath().contains(id.value())).count(), "nada mais é reenviado da execução entregue");
    }

    @Test
    @DisplayName("D2: rede indisponível durante a execução: nada muda nos fatos locais; a entrega retoma sem duplicar")
    void networkDownDuringExecution() throws Exception {
        Lab lab = ready();
        ExecutionId id = lab.execution(ExecutionState.RUNNING, lab.clock.instant());
        lab.events(id, 1, 4);
        lab.link(id, authorization(lab));
        for (int i = 0; i < 6; i++) lab.gw.fault("/agent/api/v1", Fault.DROP_BEFORE);
        byte[] estado = Files.readAllBytes(lab.runs.dir(id).resolve("estado.json"));
        byte[] atividade = Files.readAllBytes(lab.activity(id));
        for (int i = 0; i < 6; i++) {
            lab.clock.advance(Duration.ofMinutes(6));
            lab.tick(1);
        }
        assertEquals(PlatformStatus.Connectivity.DISCONNECTED, lab.svc.status().connectivity());
        assertArrayEquals(estado, Files.readAllBytes(lab.runs.dir(id).resolve("estado.json")), "o transporte não toca no registro do supervisor");
        assertArrayEquals(atividade, Files.readAllBytes(lab.activity(id)), "nem no histórico");
        assertTrue(lab.svc.retains(id));
        // a execução termina com a rede ainda fora; depois volta
        lab.save(id, ExecutionState.SUCCEEDED, lab.clock.instant());
        lab.append(id, List.of(lab.closing(id, 5)));
        lab.settle();
        FakeGateway.Run run = lab.gw.run(id.value());
        assertEquals("SUCCEEDED", run.state);
        assertEquals(5, run.events.size());
        assertEquals(0, run.conflicts);
        assertEquals(DeliveryView.State.DELIVERED, lab.svc.delivery(id).state());
    }

    @Test
    @DisplayName("D3: lote gravado no servidor e ACK perdido: o reenvio devolve DUPLICATE, sem duplicar nem avançar antes do ACK")
    void lostAckIsResentAsDuplicate() throws Exception {
        Lab lab = ready();
        ExecutionId id = lab.execution(ExecutionState.RUNNING, lab.clock.instant());
        lab.events(id, 1, 3);
        lab.link(id, authorization(lab));
        lab.gw.fault("/events", Fault.DROP_AFTER_COMMIT);
        lab.tick(1);
        FakeGateway.Run run = lab.gw.run(id.value());
        assertEquals(3, run.settled, "o servidor gravou");
        assertEquals(0, lab.svc.delivery(id).settledThrough(), "o Agent não avançou sem ACK");
        assertTrue(lab.svc.retains(id), "nada liberado localmente");
        lab.clock.advance(Duration.ofSeconds(3));
        lab.tick(1);
        assertEquals(3, lab.svc.delivery(id).settledThrough());
        assertEquals(3, run.events.size());
        List<String> bodies = eventBodies(lab);
        assertEquals(sequencesSent(bodies.get(0)), sequencesSent(bodies.get(1)), "o mesmo lote, os mesmos fatos");
    }

    @Test
    @DisplayName("D4: reinício antes de persistir o ACK reenvia o lote; depois de persistir, retoma de settledThrough + 1")
    void restartBeforeAndAfterPersistingTheAck() throws Exception {
        Lab lab = ready();
        ExecutionId id = lab.execution(ExecutionState.RUNNING, lab.clock.instant());
        lab.events(id, 1, 3);
        lab.link(id, authorization(lab));
        lab.gw.fault("/events", Fault.DROP_AFTER_COMMIT);
        lab.tick(1);
        lab.restart(); // antes de persistir: nada avançou
        assertEquals(0, lab.svc.delivery(id).settledThrough());
        lab.tick(1);
        assertEquals(3, lab.svc.delivery(id).settledThrough());
        lab.restart(); // depois de persistir
        lab.events(id, 4, 5);
        lab.tick(1);
        List<String> bodies = eventBodies(lab);
        assertEquals(List.of(4L, 5L), sequencesSent(bodies.get(bodies.size() - 1)), "retoma de settledThrough + 1");
        assertEquals(5, lab.gw.run(id.value()).events.size());
    }

    @Test
    @DisplayName("D5: conteúdo diferente (o original do servidor prevalece e conta) e evento INVALID (para nele, sem laço, até a decisão)")
    void contentConflictAndInvalidEvent() throws Exception {
        Lab lab = ready();
        ExecutionId id = lab.execution(ExecutionState.RUNNING, lab.clock.instant());
        lab.events(id, 1, 2);
        lab.link(id, authorization(lab));
        lab.tick(1);
        // a cópia local da sequência 2 muda (defeito local) e é reenviada depois de um reinício que perdeu o progresso
        Path f = dir.resolve("plataforma").resolve("entregas").resolve(id.value() + ".json");
        String d = Files.readString(f).replaceAll("\"storedThrough\":2", "\"storedThrough\":0").replaceAll("\"settledThrough\":2", "\"settledThrough\":0")
                .replaceAll("\"readOffset\":\\d+", "\"readOffset\":0").replaceAll("\"readSequence\":\\d+", "\"readSequence\":0");
        Files.writeString(f, d);
        List<String> lines = Files.readAllLines(lab.activity(id));
        lines.set(1, lab.line(id, 2, "STEP_FAILED"));
        Files.write(lab.activity(id), lines);
        lab.restart();
        lab.tick(1);
        assertEquals(2, lab.svc.delivery(id).settledThrough(), "CONFLICT conteudo_diferente conta nos cursores");
        assertFalse(lab.svc.delivery(id).state() == DeliveryView.State.BLOCKED);
        assertTrue(lab.diag.has("blocked " + id + " " + ErrorCode.PLT_EVENT_CONFLICT.code()));
        // INVALID: o servidor recusa a sequência 4 (divergência de versão, injetada)
        lab.gw.invalidSequences.add(4L);
        lab.events(id, 3, 6);
        lab.tick(1);
        DeliveryView v = lab.svc.delivery(id);
        assertEquals(DeliveryView.State.BLOCKED, v.state());
        assertEquals(4L, v.blockedSequence());
        assertEquals(3, v.settledThrough(), "não passa do INVALID");
        int n = lab.gw.requests.size();
        lab.settle();
        assertTrue(lab.gw.requests.stream().skip(n).noneMatch(r -> r.uri().getPath().endsWith("/events")), "parado no INVALID");
        assertTrue(lab.svc.retains(id), "a cópia local fica");
        // a pessoa desiste do número: passa a perda definitiva declarada, e a entrega segue
        assertTrue(lab.svc.abandonBlocked(id).accepted());
        lab.tick(1);
        assertEquals(6, lab.svc.delivery(id).settledThrough());
        assertEquals(1, lab.svc.delivery(id).declared());
        FakeGateway.Run run = lab.gw.run(id.value());
        assertEquals(1, run.declared.size());
        assertEquals(4, run.declared.get(0)[0]);
        assertFalse(run.events.containsKey(4L), "o número desistido nunca é apresentado como recebido");
    }

    @Test
    @DisplayName("D6: linha incompleta e gravação não sincronizada não são perda; perda registrada vira faixa exata, gravada antes do envio")
    void partialLinePendingWriteAndDefinitiveLoss() throws Exception {
        Lab lab = ready();
        ExecutionId id = lab.execution(ExecutionState.RUNNING, lab.clock.instant());
        lab.events(id, 1, 4);
        lab.durable.put(id, 2L); // 3 e 4 visíveis no arquivo, sincronização ainda não confirmada
        lab.link(id, authorization(lab));
        lab.tick(1);
        assertEquals(2, lab.svc.delivery(id).settledThrough());
        lab.durable.remove(id);
        // linha final sem quebra: gravação em andamento
        Files.writeString(lab.activity(id), lab.line(id, 5, "STEP_COMPLETED").substring(0, 40), StandardOpenOption.APPEND);
        lab.tick(1);
        assertEquals(4, lab.svc.delivery(id).settledThrough());
        assertEquals(0, lab.svc.delivery(id).declared(), "nada declarado por linha incompleta");
        // a coleta completa a linha; depois perde 6..8 e grava o aviso AGT-OBS-009 em 9
        Files.writeString(lab.activity(id), lab.line(id, 5, "STEP_COMPLETED").substring(40) + "\n", StandardOpenOption.APPEND);
        lab.append(id, List.of(lab.gap009(id, 9, 3)));
        lab.gw.fault("/events", Fault.DROP_BEFORE);
        lab.tick(1);
        Path f = dir.resolve("plataforma").resolve("entregas").resolve(id.value() + ".json");
        assertTrue(Files.readString(f).contains("{\"from\":6,\"to\":8}"), "faixa gravada ANTES do envio");
        lab.restart();
        lab.tick(1);
        FakeGateway.Run run = lab.gw.run(id.value());
        assertEquals(1, run.declared.size());
        assertEquals(6, run.declared.get(0)[0]);
        assertEquals(8, run.declared.get(0)[1]);
        assertEquals(9, run.settled);
        assertEquals(5, run.stored, "declarado ausente nunca conta como recebido");
        assertEquals(3, lab.svc.delivery(id).declared());
        List<String> bodies = eventBodies(lab);
        assertTrue(bodies.get(bodies.size() - 1).contains("\"declaredMissing\":[{\"from\":6,\"to\":8}]"), "a mesma faixa no reenvio");
        // lacuna SEM aviso: para e espera decisão (nada é declarado por suposição)
        lab.append(id, List.of(lab.line(id, 12, "STEP_COMPLETED")));
        lab.tick(1);
        DeliveryView v = lab.svc.delivery(id);
        assertEquals(DeliveryView.State.BLOCKED, v.state());
        assertEquals(ErrorCode.PLT_HISTORY_GAP, v.blocked());
        assertEquals(10L, v.blockedSequence());
        assertEquals(1, run.declared.size(), "nenhuma declaração da lacuna sem explicação");
    }

    @Test
    @DisplayName("D7: ACK malformado, de outra execução, que recua ou vai além do enviado: recusado, nada avança, nada é liberado")
    void invalidAcksNeverAdvance() throws Exception {
        Lab lab = ready();
        ExecutionId id = lab.execution(ExecutionState.RUNNING, lab.clock.instant());
        lab.events(id, 1, 3);
        lab.link(id, authorization(lab));
        lab.gw.fault("/events", Fault.MALFORMED);
        lab.tick(1);
        assertEquals(0, lab.svc.delivery(id).settledThrough());
        for (java.util.function.UnaryOperator<String> tamper : List.<java.util.function.UnaryOperator<String>>of(
                a -> a.replaceFirst("\"settledThrough\":3", "\"settledThrough\":99").replaceFirst("\"storedThrough\":3", "\"storedThrough\":99"),
                a -> a.replaceFirst("\"runId\":\"[^\"]+\"", "\"runId\":\"" + UUID.randomUUID() + "\""),
                a -> a.replaceFirst("\"settledThrough\":3", "\"settledThrough\":1").replaceFirst("\"storedThrough\":3", "\"storedThrough\":1"),
                a -> a.replaceFirst("\\{\"sequence\":3,[^}]*}", "{\"sequence\":7,\"status\":\"STORED\",\"reason\":null}"))) {
            lab.gw.ackTamper = tamper;
            lab.clock.advance(Duration.ofMinutes(6));
            lab.tick(1);
            assertEquals(0, lab.svc.delivery(id).settledThrough(), "ACK recusado");
            assertTrue(lab.svc.retains(id));
        }
        assertTrue(lab.diag.lines.stream().filter(l -> l.startsWith("responseRejected")).count() >= 4);
        lab.clock.advance(Duration.ofMinutes(6));
        lab.tick(1);
        assertEquals(3, lab.svc.delivery(id).settledThrough(), "o ACK correto avança");
        assertEquals(3, lab.gw.run(id.value()).events.size());
    }

    @Test
    @DisplayName("D8: credencial revogada: o transporte inteiro para (sem insistir) e os fatos locais seguem intactos e guardados")
    void revokedCredentialStopsTheTransport() throws Exception {
        Lab lab = ready();
        ExecutionId id = lab.execution(ExecutionState.RUNNING, lab.clock.instant());
        lab.events(id, 1, 3);
        lab.link(id, authorization(lab));
        lab.tick(1);
        lab.gw.revokeAgent(lab.gw.agents.keySet().iterator().next());
        lab.save(id, ExecutionState.SUCCEEDED, lab.clock.instant());
        lab.events(id, 4, 6);
        lab.tick(1);
        assertEquals(LinkState.REFUSED, lab.svc.status().link());
        int n = lab.gw.requests.size();
        int refused = lab.gw.unauthenticated;
        lab.settle();
        assertEquals(n, lab.gw.requests.size());
        assertEquals(1, refused, "uma recusa só");
        assertTrue(lab.svc.retains(id), "a cópia local não entregue continua protegida");
        assertEquals(ExecutionState.SUCCEEDED, lab.runs.record(id).orElseThrow().state(), "a execução local não é afetada");
    }

    @Test
    @DisplayName("D9: isolamento: autorização que deixou de ser do Worker é recusada (403) e só esta entrega para; vínculo novo não envia o do anterior")
    void isolation() throws Exception {
        Lab lab = ready();
        UUID auth = authorization(lab);
        ExecutionId id = lab.execution(ExecutionState.RUNNING, lab.clock.instant());
        lab.events(id, 1, 2);
        lab.link(id, auth);
        lab.gw.authorizations.get(auth).worker = UUID.randomUUID(); // outro Worker no servidor
        lab.tick(1);
        assertEquals(DeliveryView.State.BLOCKED, lab.svc.delivery(id).state());
        assertEquals(ErrorCode.PLT_AUTHORIZATION_REFUSED, lab.svc.delivery(id).blocked());
        assertTrue(lab.svc.prepare(UUID.randomUUID(), "sha256:" + PlatformFixtures.FP).problem() == ErrorCode.PLT_AUTHORIZATION_REFUSED,
                "autorização desconhecida (de outra organização) nunca é aceita");
        // vínculo removido e novo pareamento em OUTRA organização: a entrega do anterior não vai pela credencial nova
        ExecutionId pending = lab.execution(ExecutionState.SUCCEEDED, lab.clock.instant());
        lab.events(pending, 1, 2);
        lab.gw.authorizations.get(auth).worker = lab.worker;
        lab.link(pending, auth);
        lab.gw.revokeAgent(lab.gw.agents.keySet().iterator().next());
        lab.svc.removeLocalLink();
        UUID otherOrg = UUID.randomUUID();
        UUID w2 = lab.gw.worker();
        lab.svc.pair(lab.url, lab.gw.pairingCode(otherOrg, w2).toCharArray());
        lab.settle();
        assertEquals(LinkState.PAIRED, lab.svc.status().link());
        assertEquals(DeliveryView.State.OTHER_LINK, lab.svc.delivery(pending).state());
        assertTrue(lab.gw.run(pending.value()) == null, "nada registrado com a credencial do outro vínculo");
    }

    @Test
    @DisplayName("D10: relatório atrasado (STALE), resultado desconhecido que continua desconhecido e contradição (CONFLICT, sem laço)")
    void lateUnknownAndContradictoryReports() throws Exception {
        Lab lab = ready();
        UUID auth = authorization(lab);
        // OUTCOME_UNKNOWN: terminal, desconhecido, nunca vira falha nem sucesso
        ExecutionId u = lab.execution(ExecutionState.OUTCOME_UNKNOWN, lab.clock.instant());
        lab.append(u, List.of(lab.closing(u, 1)));
        lab.link(u, auth);
        lab.settle();
        assertEquals("OUTCOME_UNKNOWN", lab.gw.run(u.value()).state);
        assertEquals(DeliveryView.State.DELIVERED, lab.svc.delivery(u).state(), "envio concluído não muda o resultado desconhecido");
        // caminho real: registrada em READY, o supervisor chega a SUCCEEDED; a Platform recebe STARTING e RUNNING antes
        ExecutionId c = lab.execution(ExecutionState.READY, lab.clock.instant());
        lab.link(c, auth);
        lab.tick(1);
        lab.save(c, ExecutionState.SUCCEEDED, lab.clock.instant());
        lab.tick(1);
        FakeGateway.Run rc = lab.gw.run(c.value());
        assertEquals(List.of("APPLIED", "APPLIED", "APPLIED", "APPLIED"), rc.outcomes);
        assertEquals("SUCCEEDED", rc.state);
        // contradição: o servidor tem outro terminal; o relatório é recusado uma vez e nunca repetido em laço
        ExecutionId k = lab.execution(ExecutionState.RUNNING, lab.clock.instant());
        lab.link(k, auth);
        lab.tick(1);
        FakeGateway.Run rk = lab.gw.run(k.value());
        rk.state = "FAILED"; // (o servidor recebeu outro desfecho)
        rk.details = "x";
        lab.save(k, ExecutionState.SUCCEEDED, lab.clock.instant());
        lab.tick(1);
        assertTrue(rk.outcomes.contains("CONFLICT"));
        assertTrue(lab.svc.delivery(k).reportConflict());
        long reports = lab.gw.count("POST", k.value() + "/supervisor");
        lab.settle();
        assertEquals(reports, lab.gw.count("POST", k.value() + "/supervisor"), "sem reenvio do relatório contraditório");
        assertEquals("FAILED", rk.state, "o estado da Platform não muda");
        // atrasado: a Platform já tinha um estado posterior; o mais antigo é STALE e nada muda
        ExecutionId s = lab.execution(ExecutionState.RUNNING, lab.clock.instant());
        lab.link(s, auth);
        lab.tick(1);
        FakeGateway.Run rs = lab.gw.run(s.value());
        rs.state = "SUCCEEDED";
        lab.save(s, ExecutionState.RUNNING, lab.clock.instant().plusSeconds(1)); // mesmo estado com outros dados
        lab.tick(1);
        assertTrue(rs.outcomes.contains("STALE"));
        assertEquals("SUCCEEDED", rs.state);
    }

    @Test
    @DisplayName("D11: limites: lotes de até 500 eventos e abaixo de 2 MiB; espera crescente com aleatoriedade e Retry-After respeitado")
    void batchLimitsAndRetries() throws Exception {
        Lab lab = ready();
        ExecutionId id = lab.execution(ExecutionState.RUNNING, lab.clock.instant());
        lab.events(id, 1, 1200);
        lab.link(id, authorization(lab));
        lab.tick(1);
        for (String b : eventBodies(lab)) {
            assertTrue(sequencesSent(b).size() <= 500, "até 500 por lote");
            assertTrue(b.getBytes(StandardCharsets.UTF_8).length < 2 * 1024 * 1024, "abaixo de 2 MiB");
        }
        assertEquals(1200, lab.svc.delivery(id).settledThrough());
        // 503 com Retry-After 7 s: nada antes disso, mesmo que a espera calculada seja menor
        lab.events(id, 1201, 1210);
        lab.gw.fault("/events", Fault.UNAVAILABLE);
        lab.tick(1);
        long sent = lab.gw.count("POST", "/events");
        lab.clock.advance(Duration.ofSeconds(5));
        lab.tick(1);
        assertEquals(sent, lab.gw.count("POST", "/events"), "Retry-After respeitado");
        lab.clock.advance(Duration.ofSeconds(3));
        lab.tick(1);
        assertEquals(sent + 1, lab.gw.count("POST", "/events"));
        assertEquals(1210, lab.svc.delivery(id).settledThrough());
        // quedas seguidas: espera dobrando (com a aleatoriedade fixa em 0,5 → 75% da base), limitada a 5 min
        Backoff b = new Backoff(() -> 0.5);
        java.time.Instant t = PlatformFixtures.T0;
        List<Long> waits = new ArrayList<>();
        for (int i = 0; i < 10; i++) waits.add(b.failed(t, java.util.Optional.empty()).toMillis());
        assertEquals(List.of(1500L, 3000L, 6000L, 12000L, 24000L, 48000L, 96000L, 192000L, 225000L, 225000L), waits);
        assertTrue(new Backoff(() -> 0.5).failed(t, java.util.Optional.of(30L)).compareTo(Duration.ofSeconds(30)) >= 0, "Retry-After maior que a espera vale");
        assertEquals(Duration.ofHours(1), new Backoff(() -> 0).failed(t, java.util.Optional.of(999_999L)), "Retry-After limitado a 1 h");
    }

    @Test
    @DisplayName("D12: o progresso que não pode ser gravado não conta: nada é dado por entregue e o reenvio é seguro")
    void progressWriteFailureDoesNotAdvance() throws Exception {
        Lab lab = ready();
        ExecutionId id = lab.execution(ExecutionState.RUNNING, lab.clock.instant());
        lab.events(id, 1, 3);
        lab.link(id, authorization(lab));
        lab.tick(1);
        Path entregas = dir.resolve("plataforma").resolve("entregas");
        // a pasta vira somente leitura de fato trocando o arquivo por uma pasta (gravação atômica falha)
        Path f = entregas.resolve(id.value() + ".json");
        String before = Files.readString(f);
        lab.events(id, 4, 5);
        Files.delete(f);
        Files.createDirectory(f);
        Files.writeString(f.resolve("ocupado"), "x"); // pasta não vazia: a troca atômica não a substitui
        lab.tick(1);
        assertEquals(ErrorCode.PLT_STORAGE_FAILED, lab.svc.status().problem());
        Files.delete(f.resolve("ocupado"));
        Files.delete(f);
        Files.writeString(f, before);
        lab.restart();
        assertEquals(3, lab.svc.delivery(id).settledThrough(), "o disco manda: o que não foi gravado não avançou");
        lab.clock.advance(Duration.ofSeconds(3));
        lab.tick(1);
        assertEquals(5, lab.svc.delivery(id).settledThrough());
        assertEquals(5, lab.gw.run(id.value()).events.size());
    }
}
