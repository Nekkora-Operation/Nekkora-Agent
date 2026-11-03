package com.nekkora.agent;

import com.nekkora.agent.app.ExternalCli;
import java.time.Clock;

/**
 * AGENT-PYTHON-001: ponto de entrada do executável {@code nekkora-lancar.exe} (console), o que a tarefa agendada do cliente
 * chama no lugar do Python: {@code nekkora-lancar --vinculo <nome> -- <python> <script> [argumentos]}. Devolve ao agendador o
 * código de saída do script (ou 120/121 quando ele não rodou). Sem janela e sem a trava de instância única.
 */
public final class LancarMain {
    private LancarMain() {}

    public static void main(String[] args) {
        String[] all = new String[args.length + 1];
        all[0] = "lancar";
        System.arraycopy(args, 0, all, 1, args.length);
        System.exit(ExternalCli.run(all, System.out, System.err, System.in, Clock.systemUTC()));
    }
}
