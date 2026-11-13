package com.nekkora.agent.exec;

import java.io.IOException;
import java.nio.file.Path;

/** AGENT-PACOTES-001: acesso dos testes de outros pacotes às fixtures de pacote do Studio e de laboratório (as mesmas). */
public final class ExecFixturesAccess {
    private ExecFixturesAccess() {}

    public static Path studioPackage(Path dir) throws IOException {
        return ExecFixtures.studioPackage(dir, "single", "\"output\": \"--output\"", "");
    }

    public static Path labPackage(Path dir) throws IOException {
        return ExecFixtures.labPackage(dir, "lab-teste", "lab_sucesso.py");
    }
}
