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
            case UNLINKING -> "Desvínculo aguardando a Platform";
        };
    }

    static Status linkStatus(PlatformStatus.LinkState s) {
        return switch (s) {
            case NOT_PAIRED -> Status.DISABLED;
            case PAIRING, ROTATING -> Status.PENDING;
            case PAIRED -> Status.SUCCESS;
            case REFUSED, CONTRACT_REFUSED -> Status.FAILED;
            case DESTINATION_CHANGED -> Status.WARNING;
            case UNLINKING -> Status.PENDING;
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

    /** T13 (PLATFORM-AGENT-V2-004): o desvínculo também é pedido à Platform. */
    static final String UNLINK_CONSEQUENCES = "Ao desvincular:\n"
            + "• esta máquina pede à Platform o encerramento deste vínculo. Quando a Platform confirma, o Worker fica sem máquina vinculada "
            + "e pode ser vinculado de novo; ele continua com os workspaces e o histórico;\n"
            + "• a partir da confirmação aqui, nada novo começa por este vínculo nesta máquina;\n"
            + "• sem conexão, o pedido fica guardado e é enviado quando o Agent estiver aberto e a conexão voltar. Até lá, a Platform pode "
            + "mostrar a máquina como Vinculada e Offline, e a administração pode revogar o vínculo em Workers;\n"
            + "• a credencial é apagada desta máquina quando a Platform responder;\n"
            + "• as autorizações guardadas e as aprovações locais de pacotes deixam de valer nesta máquina;\n"
            + "• o histórico das execuções e as automações desta máquina são mantidos.\n"
            + "Cancelar não muda nada.";

    /** T13: a renovação em voo termina antes (a credencial antiga pode já ter sido trocada na Platform). */
    static final String UNLINK_ROTATION_PENDING = "Uma renovação de credencial ainda aguarda a resposta da Platform. Ao desvincular, o Agent termina "
            + "essa renovação primeiro e pede o desvínculo com a credencial que valer; até lá, nada novo começa por este vínculo.";

    static final String UNLINK_RUNNING = "Há uma execução em andamento nesta máquina. O desvínculo só pode ser concluído depois que ela terminar; "
            + "nada foi alterado. Aguarde o fim da execução e tente de novo.";

    /** O que a Platform respondeu à credencial, consultada antes do desvínculo. Nunca diz que o Worker está livre. */
    static String server(PlatformService.UnlinkPreview p) {
        return switch (p.server()) {
            case ACCEPTED -> "A Platform aceita esta máquina agora. Ao confirmar, o pedido de desvínculo é enviado em seguida, e a resposta da "
                    + "Platform aparece aqui.";
            case REFUSED -> "A Platform recusou a credencial desta máquina (revogada ou vencida). Ao confirmar, o Agent pergunta à Platform se o vínculo "
                    + "já terminou; sem resposta conclusiva, o pedido fica guardado e o Agent tenta de novo.";
            case UNREACHABLE -> "A Platform não pôde ser consultada agora. Ao confirmar, o pedido fica guardado e é enviado quando houver conexão, "
                    + "com o Agent aberto. " + finishOnPlatform(p.host(), p.organizationId());
            case NOT_CONSULTED -> "A Platform não foi consultada (" + notConsultedWhy(p) + "). Ao confirmar, o pedido fica guardado e é enviado "
                    + "quando for possível. " + finishOnPlatform(p.host(), p.organizationId());
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
        return "Se o Worker precisar ser vinculado antes disso, uma pessoa com permissão pode revogar o vínculo em Workers, na Platform de origem ("
                + (host == null ? "endereço desconhecido" : host)
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

    /** T13: título do desfecho (concluído ou pendente). */
    static String unlinkTitle(PlatformService.UnlinkResult r) {
        if (r.pending()) return "Desvínculo aguardando a Platform";
        if (r.remote() == PlatformStatus.RemoteOutcome.UNSUPPORTED) return "Desvinculada só nesta máquina";
        return "Máquina desvinculada";
    }

    /** T13: o desfecho do desvínculo, sem afirmar o que a Platform não confirmou. */
    static String unlinkOutcome(PlatformService.UnlinkResult r, String host, String organizationId) {
        if (r.pending()) {
            return ErrorCode.PLT_UNLINK_REMOTE_PENDING.description() + " Nada novo começa por este vínculo nesta máquina, e um novo pareamento só "
                    + "pode ser feito depois da confirmação." + undelivered(r);
        }
        if (r.remote() == null) return unlinked(r, host, organizationId);
        StringBuilder b = new StringBuilder(remoteOutcome(r.remote()));
        b.append(" A credencial e as autorizações deste vínculo foram apagadas desta máquina.").append(undelivered(r));
        if (r.problem() != null && r.problem() != ErrorCode.PLT_UNLINK_UNSUPPORTED) b.append(' ').append(problem(r.problem()));
        return b.toString();
    }

    /** O que a Platform respondeu, de forma conclusiva. */
    static String remoteOutcome(PlatformStatus.RemoteOutcome o) {
        return switch (o) {
            case CONFIRMED -> "A Platform confirmou o desvínculo: o Worker ficou sem máquina vinculada e pode ser vinculado de novo.";
            case ENDED_BY_ADMINISTRATION -> "O vínculo já tinha sido revogado na Platform pela administração: o Worker já estava sem esta máquina.";
            case ENDED_BY_MACHINE -> "O vínculo já tinha sido encerrado na Platform por esta máquina.";
            case UNSUPPORTED -> ErrorCode.PLT_UNLINK_UNSUPPORTED.description();
        };
    }

    private static String undelivered(PlatformService.UnlinkResult r) {
        if (r.undeliveredRuns() <= 0) return "";
        return " " + r.undeliveredRuns() + (r.undeliveredRuns() == 1 ? " execução ficou guardada como não entregue." : " execuções ficaram guardadas como não entregues.");
    }

    /** O último resultado NÃO conclusivo de uma tentativa (nunca é prova de desvínculo). */
    static String unlinkAttempt(String code) {
        if (code == null) return "o pedido ainda não foi enviado";
        return switch (code) {
            case "SEM_RESPOSTA" -> "a Platform não respondeu (sem conexão ou sem resposta)";
            case "CREDENCIAL_RECUSADA" -> "a Platform recusou a credencial; isso não confirma o desvínculo";
            case "LIMITE" -> "a Platform pediu para esperar";
            case "INDISPONIVEL" -> "a Platform estava indisponível";
            case "RESPOSTA_INVALIDA" -> "a resposta da Platform veio fora do contrato";
            case "SEM_CREDENCIAL" -> "a credencial guardada não pôde ser lida nesta máquina";
            case "PEDIDO_ILEGIVEL" -> "o pedido guardado não pôde ser lido; nada é enviado até a revisão desta máquina";
            default -> "a Platform recusou o pedido (" + code + ")";
        };
    }

    /** Aviso do card enquanto o desvínculo aguarda a Platform. */
    static String unlinkPendingNotice(PlatformStatus.Unlink u) {
        String last = u.attempts() == 0 ? "Ainda não enviado." : "Tentativas: " + u.attempts()
                + (u.lastAttemptAt() == null ? "" : " · última às " + time(u.lastAttemptAt())) + " · " + unlinkAttempt(u.lastResult()) + ".";
        return ErrorCode.PLT_UNLINK_REMOTE_PENDING.description() + " Nada novo começa por este vínculo nesta máquina. " + last;
    }

    /** Depois do desvínculo concluído (Platform sem resposta conclusiva no modelo anterior; mantido para o registro). */
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
