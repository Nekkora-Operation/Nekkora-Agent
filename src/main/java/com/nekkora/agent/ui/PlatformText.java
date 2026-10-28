package com.nekkora.agent.ui;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.platform.DeliveryView;
import com.nekkora.agent.platform.PlatformService;
import com.nekkora.agent.platform.PlatformStatus;
import com.nekkora.ui.core.Status;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Textos do canal com a Platform (sem Swing: testáveis sem tela). Comunicação, vínculo e entrega têm palavras próprias e
 * nunca usam o vocabulário do desfecho de uma automação ("concluída", "falhou"): "automação concluída" e "envio
 * concluído" são coisas diferentes.
 */
final class PlatformText {
    private PlatformText() {}

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM HH:mm:ss", Locale.forLanguageTag("pt-BR"));

    static String link(PlatformStatus.LinkState s) {
        return switch (s) {
            case NOT_PAIRED -> "Não pareado";
            case PAIRING -> "Pareamento em andamento";
            case ROTATING -> "Pareado · troca de credencial em andamento";
            case PAIRED -> "Pareado";
            case REFUSED -> "Credencial recusada pela Platform";
            case DESTINATION_CHANGED -> "Endereço da Platform alterado";
            case CONTRACT_REFUSED -> "Versão do Agent recusada pela Platform";
        };
    }

    static Status linkStatus(PlatformStatus.LinkState s) {
        return switch (s) {
            case NOT_PAIRED -> Status.DISABLED;
            case PAIRING, ROTATING -> Status.PENDING;
            case PAIRED -> Status.SUCCESS;
            case REFUSED, CONTRACT_REFUSED -> Status.FAILED;
            case DESTINATION_CHANGED -> Status.WARNING;
        };
    }

    /** Só comunicação: nunca diz nada sobre automações. */
    static String connectivity(PlatformStatus st) {
        if (!st.paired()) return "Sem vínculo";
        return switch (st.connectivity()) {
            case CONNECTED -> "Conectado" + (st.lastContact() == null ? "" : " · último contato " + time(st.lastContact()));
            case DISCONNECTED -> "Sem comunicação com a Platform" + (st.lastContact() == null ? "" : " · último contato " + time(st.lastContact()));
            case UNKNOWN -> st.lastContact() == null ? "Ainda sem contato" : "Sem contato recente · último " + time(st.lastContact());
        };
    }

    static Status connectivityStatus(PlatformStatus st) {
        if (!st.paired()) return Status.DISABLED;
        return switch (st.connectivity()) {
            case CONNECTED -> Status.SUCCESS;
            case DISCONNECTED -> Status.WARNING;
            case UNKNOWN -> Status.PENDING;
        };
    }

    static String deliveries(PlatformStatus st) {
        if (st.pendingRuns() == 0) return "Nenhum envio pendente";
        return st.pendingRuns() + (st.pendingRuns() == 1 ? " execução" : " execuções") + " com envio pendente"
                + (st.pendingEvents() > 0 ? " (" + st.pendingEvents() + " eventos)" : "");
    }

    static String lastSync(PlatformStatus st) {
        return st.lastSync() == null ? "Nenhum nesta sessão" : time(st.lastSync());
    }

    /** Identificação lógica (os identificadores da Platform não são segredo; mostrados curtos). */
    static String identity(PlatformStatus st) {
        if (st.agentId() == null) return "—";
        return "Agent " + shortId(st.agentId()) + (st.workerId() == null ? "" : " · Worker " + shortId(st.workerId()));
    }

    static String problem(ErrorCode c) {
        return c == null ? null : c.description() + " (" + c.code() + ")";
    }

    /** Envio de UMA execução, separado do desfecho dela. */
    static String delivery(DeliveryView v) {
        return switch (v.state()) {
            case NOT_LINKED -> "Só local: não registrada na Platform";
            case WAITING_REGISTRATION -> "Aguardando registro na Platform";
            case PENDING -> "Envio pendente · confirmados " + v.settledThrough() + " eventos";
            case DELIVERED -> "Envio concluído · " + v.settledThrough() + " eventos confirmados"
                    + (v.declared() > 0 ? " · histórico incompleto (" + v.declared() + " perdidos localmente)" : "");
            case BLOCKED -> "Envio parado: " + (v.blocked() == null ? "decisão necessária" : v.blocked().description())
                    + (v.blockedSequence() == null ? "" : " (evento " + v.blockedSequence() + ")");
            case OTHER_LINK -> "De um vínculo anterior desta máquina: não é enviada pelo atual";
        } + (v.reportConflict() ? " · relatório contraditório registrado pela Platform" : "");
    }

    static Status deliveryStatus(DeliveryView v) {
        return switch (v.state()) {
            case NOT_LINKED, OTHER_LINK -> Status.DISABLED;
            case WAITING_REGISTRATION, PENDING -> Status.PENDING;
            case DELIVERED -> v.declared() > 0 || v.reportConflict() ? Status.WARNING : Status.SUCCESS;
            case BLOCKED -> Status.FAILED;
        };
    }

    /** Uma autorização na lista de escolha do início. */
    static String choice(PlatformService.AuthorizationChoice c) {
        String what = "Versão " + shortId(String.valueOf(c.automationVersionId())) + " · até " + time(c.validUntil());
        if (c.usable()) return "Registrar na Platform: " + what;
        return what + " · " + ("VENCIDA".equals(c.reason()) ? "vencida" : "regra de impressão desconhecida");
    }

    static String shortId(String uuid) {
        return uuid == null ? "—" : uuid.length() > 8 ? uuid.substring(0, 8) : uuid;
    }

    static String time(Instant i) {
        return TIME.format(i.atZone(ZoneId.systemDefault()));
    }
}
