package com.nekkora.agent.platform;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.exec.ExecutionId;
import java.time.Instant;
import java.util.List;

/**
 * Retrato do canal para a tela e o diagnóstico (sem segredo, sem código de pareamento, sem conteúdo de execução). As
 * dimensões ficam separadas: vínculo, conectividade e entrega nunca dizem nada sobre o resultado de uma automação.
 *
 * @param link situação do vínculo
 * @param connectivity resultado do último contato (só comunicação)
 * @param host host da Platform do vínculo (ou do pareamento em andamento)
 * @param organizationId identificador da organização do vínculo (o contrato não traz o nome), ou {@code null}
 * @param agentId identificador do Agent na Platform (lógico, não é segredo), ou {@code null}
 * @param workerId identificador do Worker (o contrato não traz o nome), ou {@code null}
 * @param lastContact última resposta recebida da Platform
 * @param lastSync último avanço de entrega confirmado por ACK
 * @param pendingRuns execuções vinculadas ainda não totalmente entregues
 * @param pendingEvents eventos gravados localmente e ainda não liquidados (estimativa da última leitura)
 * @param blocked entregas paradas à espera de decisão
 * @param incompleteRuns execuções com perda declarada (histórico incompleto na Platform)
 * @param problem último problema de comunicação ou do vínculo, ou {@code null}
 * @param unlink T13: desvínculo pedido pela pessoa e o que a Platform respondeu (nunca {@code null})
 */
public record PlatformStatus(LinkState link, Connectivity connectivity, String host, String organizationId, String agentId, String workerId, Instant lastContact, Instant lastSync,
                             Instant credentialExpiresAt, int pendingRuns, long pendingEvents, List<Blocked> blocked, int incompleteRuns, int authorizations,
                             Instant authorizationsAt, ErrorCode problem, boolean secretStoreAvailable, Unlink unlink) {

    public PlatformStatus {
        blocked = List.copyOf(blocked);
    }

    /** PLATFORM-AGENT-V2-004 T13: como terminou (na Platform) o desvínculo confirmado pela pessoa. */
    public enum RemoteOutcome {
        /** A Platform confirmou: o Worker ficou livre lá. */
        CONFIRMED,
        /** O vínculo já tinha sido encerrado pela administração (Revogar vínculo). */
        ENDED_BY_ADMINISTRATION,
        /** O vínculo já tinha sido encerrado por outra operação desta máquina. */
        ENDED_BY_MACHINE,
        /** A Platform não tem a rota (versão anterior): desvínculo só nesta máquina. */
        UNSUPPORTED
    }

    /**
     * Desvínculo. {@code pending}: confirmado pela pessoa, ainda sem resposta conclusiva da Platform (nada novo começa pelo
     * vínculo antigo). {@code lastResult}: o último resultado NÃO conclusivo (rede, credencial recusada…), ou {@code null}.
     * {@code concluded}: o último desfecho nesta sessão, ou {@code null}.
     */
    public record Unlink(boolean pending, Instant confirmedAt, int attempts, Instant lastAttemptAt, String lastResult, RemoteOutcome concluded,
                         Instant concludedAt) {
        public static final Unlink NONE = new Unlink(false, null, 0, null, null, null, null);
    }

    public enum LinkState {
        /** Sem vínculo. */
        NOT_PAIRED,
        /** Pareamento pedido pela pessoa, resposta da Platform ainda não confirmada. */
        PAIRING,
        /** Rotação pedida, resposta ainda não confirmada (a credencial anterior continua guardada). */
        ROTATING,
        PAIRED,
        /** A Platform recusou a credencial: nenhuma chamada autenticada até a pessoa decidir. */
        REFUSED,
        /** O endereço configurado não é o do pareamento: nada é enviado. */
        DESTINATION_CHANGED,
        /** A Platform não aceita esta versão do contrato. */
        CONTRACT_REFUSED,
        /** T13: desvínculo confirmado pela pessoa, aguardando a confirmação da Platform (só a operação de desvínculo sai). */
        UNLINKING
    }

    /** Só comunicação: nunca é resultado de execução. */
    public enum Connectivity { CONNECTED, DISCONNECTED, UNKNOWN }

    /** Entrega parada: execução, motivo e, quando é um evento, a sequência. */
    public record Blocked(ExecutionId executionId, ErrorCode code, Long sequence) {}

    public boolean paired() {
        return link == LinkState.PAIRED || link == LinkState.ROTATING || link == LinkState.REFUSED || link == LinkState.DESTINATION_CHANGED
                || link == LinkState.CONTRACT_REFUSED;
    }
}
