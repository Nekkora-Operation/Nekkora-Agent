package com.nekkora.agent.ui;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.platform.DeliveryView;
import com.nekkora.agent.platform.PlatformService;
import com.nekkora.agent.platform.PlatformStatus;
import com.nekkora.ui.core.Severity;
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

    // ----- T06: desvínculo -----

    static final String UNLINK_CONSEQUENCES = "Ao desvincular:\n"
            + "• esta máquina para de enviar e de receber pelo vínculo atual;\n"
            + "• a credencial guardada nesta máquina é apagada da proteção do Windows;\n"
            + "• as autorizações guardadas deste vínculo deixam de valer nesta máquina;\n"
            + "• o histórico das execuções e as automações desta máquina são mantidos;\n"
            + "• o Worker e as autorizações dele na Platform não são alterados por esta máquina. Para liberar o Worker na Platform, "
            + "uma pessoa com permissão precisa revogar o Agent na Platform (Workers).\n"
            + "Cancelar não muda nada.";

    static final String UNLINK_ROTATION_PENDING = "Uma renovação de credencial ainda aguarda a resposta da Platform. Desvincular abandona essa "
            + "tentativa: o código de renovação pode já ter sido usado na Platform, e esta máquina fica sem nenhuma credencial.";

    static final String UNLINK_RUNNING = "Há uma execução em andamento nesta máquina. O desvínculo só pode ser concluído depois que ela terminar; "
            + "nada foi alterado. Aguarde o fim da execução e tente de novo.";

    /** O que a Platform respondeu à credencial, consultada antes do desvínculo. Nunca diz que o Worker está livre. */
    static String server(PlatformService.UnlinkPreview p) {
        return switch (p.server()) {
            case ACCEPTED -> "A Platform ainda aceita esta máquina: o vínculo continua lá até uma pessoa autorizada revogar o Agent em Workers.";
            case REFUSED -> "A Platform recusou a credencial desta máquina (revogada ou vencida). Confira em Workers, na Platform, se o Agent "
                    + "já foi revogado por uma pessoa autorizada.";
            case UNREACHABLE -> "A Platform não pôde ser consultada agora. " + finishOnPlatform(p.host(), p.organizationId());
            case NOT_CONSULTED -> "A Platform não foi consultada (" + notConsultedWhy(p) + "). " + finishOnPlatform(p.host(), p.organizationId());
        };
    }

    private static String notConsultedWhy(PlatformService.UnlinkPreview p) {
        if (p.rotationPending()) return "renovação de credencial em andamento";
        return switch (p.state()) {
            case DESTINATION_CHANGED -> "o endereço configurado não é o do pareamento";
            case CONTRACT_REFUSED -> "a Platform não aceita esta versão do Agent";
            case REFUSED -> "a credencial já foi recusada";
            default -> "sem credencial utilizável nesta máquina";
        };
    }

    /** Orientação para concluir na Platform de origem (host e organização do vínculo). */
    static String finishOnPlatform(String host, String organizationId) {
        return "Para concluir, uma pessoa com permissão deve revogar o Agent em Workers, na Platform de origem (" + (host == null ? "endereço desconhecido" : host)
                + (organizationId == null ? "" : ", organização " + organizationId) + ").";
    }

    static Severity serverSeverity(PlatformService.ServerView s) {
        return switch (s) {
            case ACCEPTED, REFUSED -> Severity.INFO;
            case UNREACHABLE, NOT_CONSULTED -> Severity.WARNING;
        };
    }

    static String unlinkPending(int runs, long events) {
        return runs + (runs == 1 ? " execução ainda não foi entregue" : " execuções ainda não foram entregues") + " à Platform"
                + (events > 0 ? " (" + events + " eventos)" : "") + ". “Aguardar envios” mantém o vínculo e o envio continua. “Desvincular mesmo assim” "
                + "guarda esses registros nesta máquina, identificados como não entregues; eles nunca são enviados por outro vínculo.";
    }

    /** Depois do desvínculo concluído. */
    static String unlinked(PlatformService.UnlinkResult r, String host, String organizationId) {
        StringBuilder b = new StringBuilder("A credencial e as autorizações deste vínculo foram apagadas desta máquina. ");
        if (r.undeliveredRuns() > 0) {
            b.append(r.undeliveredRuns()).append(r.undeliveredRuns() == 1 ? " execução ficou guardada como não entregue. "
                    : " execuções ficaram guardadas como não entregues. ");
        }
        b.append("Na Platform, o vínculo só termina quando uma pessoa com permissão revogar o Agent em Workers (")
                .append(host == null ? "endereço desconhecido" : host).append(organizationId == null ? "" : ", organização " + shortId(organizationId)).append(").");
        if (r.problem() != null) b.append(' ').append(problem(r.problem()));
        return b.toString();
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
            case UNLINKED_UNDELIVERED -> "Não entregue (vínculo removido): guardada nesta máquina e nunca enviada por outro vínculo · confirmados "
                    + v.settledThrough() + " eventos";
        } + (v.reportConflict() ? " · relatório contraditório registrado pela Platform" : "");
    }

    static Status deliveryStatus(DeliveryView v) {
        return switch (v.state()) {
            case NOT_LINKED, OTHER_LINK -> Status.DISABLED;
            case UNLINKED_UNDELIVERED -> Status.WARNING;
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
