package com.nekkora.agent.ui;

import com.nekkora.agent.platform.PlatformService.LinkCheck;
import com.nekkora.agent.platform.PlatformService.LinkCheckOutcome;
import com.nekkora.agent.platform.PlatformService.LinkRef;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** AGENT-PACOTES-001: textos do assistente de importação (português; sem caminho completo nem segredo). */
final class ImportText {
    private ImportText() {}

    static final String TITLE = "Importar projeto (.zip)";
    static final String BUTTON = "Importar projeto (.zip)";
    static final String HELP = "Selecione um arquivo ZIP contendo o código e os arquivos necessários à automação.";
    static final String NOT_PAIRED = "Vincule esta máquina a uma organização para importar um projeto.";
    static final String OFFLINE = "Conecte o Agent à Platform para confirmar o vínculo e importar o projeto.";
    static final String NOTHING_RUNS = "Escolher o ZIP, conferir e gerar o pacote não executa nenhum código do projeto e não envia nada à Platform.";

    /** Por que o assistente não pode seguir (cada falha com o seu texto: permissão não é rede, rede não é revogação). */
    static String blocked(LinkCheck c) {
        LinkCheckOutcome o = c == null ? LinkCheckOutcome.UNREACHABLE : c.outcome();
        return switch (o) {
            case CONFIRMED -> "";
            case NOT_PAIRED -> NOT_PAIRED;
            case PAIRING_PENDING -> "O pareamento desta máquina ainda está sendo confirmado pela Platform. Aguarde a confirmação e tente de novo.";
            case UNLINK_PENDING -> "Há um desvínculo desta máquina em andamento. Nenhuma importação nova começa até ele terminar.";
            case UNREACHABLE, BUSY -> OFFLINE;
            case CREDENTIAL_REFUSED -> "A Platform recusou a credencial desta máquina: o vínculo foi revogado ou encerrado. Vincule a máquina de novo para importar.";
            case FORBIDDEN -> "A Platform negou esta consulta à credencial da máquina (sem permissão). Não é falta de conexão nem revogação: peça a quem "
                    + "administra a organização para conferir o Worker desta máquina.";
            case RATE_LIMITED -> "A Platform pediu para esperar antes de novas consultas (limite de requisições)"
                    + c.retryAfterSeconds().map(s -> ": tente de novo em " + s + " s.").orElse(". Tente de novo em instantes.");
            case SERVER_ERROR -> "A Platform está com um problema no servidor agora. Tente de novo em instantes; o vínculo desta máquina não mudou.";
            case CONTRACT_REFUSED -> "A Platform não aceita esta versão do Agent. Atualize o Agent para importar.";
            case DESTINATION_CHANGED -> "O endereço da Platform nas configurações não é o do vínculo. Volte ao endereço do vínculo ou vincule de novo.";
            case INVALID_RESPONSE -> "A Platform respondeu fora do contrato. Nada foi aceito da resposta; tente de novo.";
            case LOCAL_FAILURE -> "A credencial guardada nesta máquina não pôde ser lida. Veja o Diagnóstico.";
        };
    }

    /** A organização com a identificação honesta disponível: o contrato da máquina não traz o nome. */
    static String organization(LinkRef r) {
        return r == null ? "—" : "Organização " + shortId(r.organizationId()) + " · " + host(r.origin());
    }

    static final String ORGANIZATION_NOTE = "A Platform não informa o nome da organização à máquina; o identificador completo está nos detalhes técnicos.";

    static String confirmed(LinkCheck c) {
        String at = c.serverTime() == null ? "" : " às " + DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(c.serverTime());
        return "Vínculo confirmado pela Platform" + at + ". Uma revogação feita depois disso só é percebida na próxima consulta: a conclusão confirma "
                + "de novo antes de gravar, e a Platform confere cada autorização quando uma execução é registrada.";
    }

    static String catalogNote(LinkCheck c) {
        if (c == null || !c.catalog().available()) {
            return "A lista de automações não pôde ser consultada agora. Isso não significa que o projeto seja novo.";
        }
        return "Só aparecem as automações com versão autorizada a ESTA máquina, pelo identificador: a credencial da máquina não lista os projetos da "
                + "organização nem traz nomes.";
    }

    static String shortId(UUID id) {
        return id == null ? "—" : id.toString().substring(0, 8) + "…";
    }

    static String host(String origin) {
        try {
            return URI.create(origin).getHost();
        } catch (RuntimeException e) {
            return "—";
        }
    }

    static String time(Instant at) {
        return at == null ? "—" : DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(at);
    }
}
