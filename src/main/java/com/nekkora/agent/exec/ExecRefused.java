package com.nekkora.agent.exec;

import com.nekkora.agent.core.ErrorCode;
import java.util.Objects;

/** Pré-condição de execução recusada, com um código do catálogo (nunca caminho, valor ou mensagem de exceção). */
public final class ExecRefused extends Exception {
    private static final long serialVersionUID = 1L;
    private final transient ErrorCode code;

    public ExecRefused(ErrorCode code) {
        super(code.code());
        this.code = Objects.requireNonNull(code);
    }

    public ErrorCode code() {
        return code;
    }
}
