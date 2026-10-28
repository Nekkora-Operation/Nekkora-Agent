package com.nekkora.agent.app;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.diagnostics.AgentLog;
import com.nekkora.agent.diagnostics.AgentLog.Field;
import com.nekkora.agent.diagnostics.LogEvent;
import com.nekkora.agent.exec.ExecutionId;
import com.nekkora.agent.exec.ExecutionState;
import com.nekkora.agent.platform.PlatformService;
import java.util.UUID;

/**
 * Diagnóstico do transporte no log do Agent: só transições, contagens, códigos do catálogo e identificadores (execução
 * local, Agent na Platform). Nunca credencial, código de pareamento, conteúdo de evento nem endereço completo.
 */
final class PlatformLog implements PlatformService.Diagnostics {
    private final AgentLog log;

    PlatformLog(AgentLog log) {
        this.log = log;
    }

    @Override
    public void pairingStarted() {
        log.info(LogEvent.PLATFORM_PAIRING_STARTED);
    }

    @Override
    public void paired(UUID agentId) {
        log.info(LogEvent.PLATFORM_PAIRED, Field.of("agent", agentId.toString()));
    }

    @Override
    public void pairingRefused(ErrorCode why) {
        log.warn(LogEvent.PLATFORM_PAIRING_REFUSED, why);
    }

    @Override
    public void rotationStarted() {
        log.info(LogEvent.PLATFORM_ROTATION_STARTED);
    }

    @Override
    public void rotated() {
        log.info(LogEvent.PLATFORM_ROTATED);
    }

    @Override
    public void rotationRefused(ErrorCode why) {
        log.warn(LogEvent.PLATFORM_ROTATION_REFUSED, why);
    }

    @Override
    public void credentialRefused() {
        log.warn(LogEvent.PLATFORM_CREDENTIAL_REFUSED, ErrorCode.PLT_CREDENTIAL_REFUSED);
    }

    @Override
    public void linkRemoved() {
        log.info(LogEvent.PLATFORM_LINK_REMOVED);
    }

    @Override
    public void connected() {
        log.info(LogEvent.PLATFORM_CONNECTED);
    }

    @Override
    public void disconnected(ErrorCode why) {
        log.warn(LogEvent.PLATFORM_DISCONNECTED, why);
    }

    @Override
    public void authorizations(int count, int rejected) {
        log.info(LogEvent.PLATFORM_AUTHORIZATIONS, Field.of("count", count), Field.of("rejected", rejected));
    }

    @Override
    public void runLinked(ExecutionId id) {
        log.info(LogEvent.PLATFORM_RUN_LINKED, Field.of("execution", id.value()));
    }

    @Override
    public void runRegistered(ExecutionId id, boolean created) {
        log.info(LogEvent.PLATFORM_RUN_REGISTERED, Field.of("execution", id.value()), Field.of("created", created));
    }

    @Override
    public void report(ExecutionId id, ExecutionState state, String outcome) {
        log.info(LogEvent.PLATFORM_REPORT, Field.of("execution", id.value()), Field.of("state", state), Field.of("outcome", outcome));
    }

    @Override
    public void blocked(ExecutionId id, ErrorCode why, Long sequence) {
        log.warn(LogEvent.PLATFORM_DELIVERY_BLOCKED, why, Field.of("execution", id.value()), Field.of("sequence", sequence == null ? "none" : sequence));
    }

    @Override
    public void delivered(ExecutionId id, long settledThrough) {
        log.info(LogEvent.PLATFORM_DELIVERY_COMPLETED, Field.of("execution", id.value()), Field.of("settled", settledThrough));
    }

    @Override
    public void lossDeclared(ExecutionId id, long count) {
        log.warn(LogEvent.PLATFORM_LOSS_DECLARED, ErrorCode.PLT_LOSS_DECLARED, Field.of("execution", id.value()), Field.of("count", count));
    }

    @Override
    public void responseRejected(ExecutionId id, String why) {
        log.warn(LogEvent.PLATFORM_RESPONSE_REJECTED, ErrorCode.PLT_RESPONSE_INVALID, Field.of("execution", id == null ? "none" : id.value()),
                Field.of("why", why));
    }

    @Override
    public void storageFailed(ErrorCode why) {
        log.warn(LogEvent.PLATFORM_STORAGE_FAILED, why);
    }
}
