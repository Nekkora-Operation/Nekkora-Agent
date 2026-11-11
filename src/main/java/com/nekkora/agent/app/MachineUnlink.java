package com.nekkora.agent.app;

import com.nekkora.agent.core.ErrorCode;
import com.nekkora.agent.exec.ExecutionController;
import com.nekkora.agent.exec.ExecutionPackage;
import com.nekkora.agent.exec.ExecutionRecord;
import com.nekkora.agent.exec.ExternalLaunch;
import com.nekkora.agent.exec.RunStore;
import com.nekkora.agent.platform.PlatformService;
import java.util.function.BooleanSupplier;

/**
 * PLATFORM-AGENT-V2-002 T06: "Desvincular esta máquina", coordenado FORA do transporte (o transporte não conhece o executor).
 * <ol>
 *   <li>{@link #plan()}: há execução em andamento (de qualquer origem)? Se não, o retrato do vínculo, os envios pendentes e a
 *       resposta da Platform à credencial (consultada antes, quando o vínculo está em uso);</li>
 *   <li>{@link #confirm}: só depois da confirmação da pessoa, e conferindo de novo que nenhuma execução começou. Com envios
 *       pendentes, só segue com a escolha explícita "Desvincular mesmo assim" (registrados como não entregues).</li>
 * </ol>
 * Cancelar entre os dois passos não muda nada.
 */
public final class MachineUnlink {
    /** O que a confirmação mostra. {@code running}: o desvínculo não pode ser concluído agora. */
    public record Plan(boolean running, PlatformService.UnlinkPreview preview) {}

    private final PlatformService platform;
    private final BooleanSupplier running;

    public MachineUnlink(PlatformService platform, BooleanSupplier running) {
        this.platform = platform;
        this.running = running;
    }

    /** Fora da EDT (pode consultar a Platform). */
    public Plan plan() {
        boolean r = running.getAsBoolean();
        return new Plan(r, platform.previewUnlink(!r));
    }

    /**
     * A pessoa confirmou o que viu em {@code plan}. {@code evenWithPending}: escolheu "Desvincular mesmo assim" diante dos
     * envios pendentes mostrados. Fora da EDT.
     */
    public PlatformService.UnlinkResult confirm(Plan plan, boolean evenWithPending) {
        if (running.getAsBoolean()) return new PlatformService.UnlinkResult(false, false, ErrorCode.PLT_UNLINK_RUNNING, 0, 0);
        return platform.unlink(evenWithPending, plan.preview().server());
    }

    /** Execução em andamento nesta máquina: a da janela ou de um pedido da Platform (o executor) e a do lançador externo. */
    static boolean executionInProgress(ExecutionController controller) {
        if (controller.running()) return true;
        RunStore store = controller.store();
        for (ExecutionRecord r : store.all()) {
            if (r.kind() != ExecutionPackage.Kind.EXTERNAL_MONITORED || r.state().terminal()) continue;
            if (ExternalLaunch.launcherOf(store.dir(r.id())).map(o -> o.alive()).orElse(false)) return true;
        }
        return false;
    }
}
