package com.nekkora.agent.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.agent.core.ErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** DESKTOP-001: linha de comando pelo contrato do pacote, classificação pelo protocolo e sonda do Python. */
class LaunchTest {
    @TempDir
    Path tmp;

    @Test
    @DisplayName("Studio (lista): só as opções DECLARADAS, argumentos separados, planilha e saída na pasta da execução, sem shell")
    void linhaDeComandoStudio() throws Exception {
        Path dir = ExecFixtures.studioPackage(tmp.resolve("s"), "list", "\"input\": \"--input\", \"spreadsheet\": \"--spreadsheet\", \"output\": \"--output\"", "");
        ExecutionPackage p = ExecutionPackage.inspect(dir);
        Path py = tmp.resolve("venv").resolve("python.exe");
        Path in = tmp.resolve("lista com espaço; & %PATH% ^.csv");
        Path out = tmp.resolve("saida").resolve("ex-1");
        Launch.Spec s = Launch.spec(p, py, tmp.resolve("copia"), in, out);
        assertEquals(List.of(py.toString(), "-E", "-s", "-B", "-X", "utf8", "main.py", "--input", in.toString(), "--spreadsheet",
                out.resolve("planilha.xlsx").toString(), "--output", out.toString(), "--channel", "chrome"), s.command());
        assertEquals(tmp.resolve("copia"), s.workDir());
        assertFalse(s.command().stream().anyMatch(a -> a.equalsIgnoreCase("cmd") || a.equalsIgnoreCase("/c")), "sem shell");
        Path single = ExecFixtures.studioPackage(tmp.resolve("t"), "tabular", "\"output\": \"--output\"", "");
        Launch.Spec t = Launch.spec(ExecutionPackage.inspect(single), py, tmp, null, out);
        assertEquals(List.of(py.toString(), "-E", "-s", "-B", "-X", "utf8", "main.py", "--output", out.toString(), "--channel", "chrome"), t.command());
    }

    @Test
    @DisplayName("classificação: SUCCEEDED só com protocolo que confirma (planilha consolidada na lista); código 0 sozinho não basta")
    void classificacao() throws Exception {
        ExecutionPackage list = ExecutionPackage.inspect(ExecFixtures.studioPackage(tmp.resolve("s"), "list",
                "\"input\": \"--input\", \"spreadsheet\": \"--spreadsheet\", \"output\": \"--output\"", ""));
        Launch.Reported ok = new Launch.Reported("COMPLETED", Map.of(), "COMMITTED");
        Launch.Reported notCommitted = new Launch.Reported("COMPLETED", Map.of(), "SKIPPED");
        Launch.Reported partial = new Launch.Reported("COMPLETED_WITH_FAILURES", Map.of(), "SKIPPED");
        assertEquals(ExecutionState.SUCCEEDED, Launch.classify(list, Optional.of(ok), 0).state());
        assertEquals(ExecutionState.FAILED, Launch.classify(list, Optional.of(notCommitted), 0).state(), "lista sem planilha consolidada");
        assertEquals(ExecutionState.FAILED, Launch.classify(list, Optional.of(partial), 1).state());
        assertEquals(ExecutionState.FAILED, Launch.classify(list, Optional.of(ok), 3).state(), "protocolo e código discordam");
        assertEquals(ExecutionState.OUTCOME_UNKNOWN, Launch.classify(list, Optional.empty(), 0).state());
        assertEquals(ErrorCode.EXEC_EXIT_NONZERO, Launch.classify(list, Optional.empty(), 2).code());
    }

    @Test
    @DisplayName("resumo publicado: só estado e contagens são lidos; documento fora do contrato é ignorado")
    void resumo() throws Exception {
        Path out = Files.createDirectories(tmp.resolve("o").resolve("execucao"));
        Files.writeString(out.resolve("summary.json"), "{\"status\": \"COMPLETED\", \"counts\": {\"SUCCEEDED\": 4}, \"output\": {\"status\": \"COMMITTED\", "
                + "\"rows\": 4, \"detail\": \"qualquer coisa\"}, \"execution_id\": \"x\"}");
        Launch.Reported r = Launch.reported(ExecutionPackage.Kind.STUDIO_PYTHON, out.getParent()).orElseThrow();
        assertEquals("COMPLETED", r.status());
        assertEquals(Map.of("SUCCEEDED", 4L), r.counts());
        assertEquals("COMMITTED", r.outputStatus());
        Files.writeString(out.resolve("summary.json"), "{\"status\": \"concluído com valor\"}");
        assertTrue(Launch.reported(ExecutionPackage.Kind.STUDIO_PYTHON, out.getParent()).isEmpty(), "estado fora do vocabulário");
        Files.writeString(tmp.resolve(Launch.LAB_RESULT), "{\"status\": \"SUCCEEDED\"}");
        assertTrue(Launch.reported(ExecutionPackage.Kind.LAB_GENERIC, tmp).isEmpty(), "sem schema/versão do laboratório");
    }

    @Test
    @DisplayName("[Python real] sonda: versão do Python e do nekkora_rpa; pacote que exige outra versão é incompatível; nome inválido recusado")
    void sonda() throws Exception {
        Path py = ExecFixtures.python();
        Assumptions.assumeTrue(py != null, "sem Python neste ambiente");
        PythonRuntime.Probe probe = PythonRuntime.probe(py, tmp.resolve("sonda"));
        assertTrue(probe.pythonVersion().matches("3\\.1[2-9]\\.[0-9]+"), probe.pythonVersion());
        ExecutionPackage lab = ExecutionPackage.inspect(ExecFixtures.labPackage(tmp.resolve("lab"), "Lab", "lab_sucesso.py"));
        PythonRuntime.requireCompatible(probe, lab);
        Path studio = ExecFixtures.studioPackage(tmp.resolve("s"), "single", "\"output\": \"--output\"", "");
        Files.writeString(studio.resolve("manifest.json"), Files.readString(studio.resolve("manifest.json")).replace("==0.3.0", "==9.9.9"));
        ExecutionPackage future = ExecutionPackage.inspect(studio);
        assertEquals(ErrorCode.EXEC_PYTHON_INCOMPATIBLE, assertThrows(ExecRefused.class, () -> PythonRuntime.requireCompatible(probe, future)).code());
        Path notPython = Files.writeString(tmp.resolve("calc.exe"), "x");
        assertEquals(ErrorCode.EXEC_PYTHON_INVALID, assertThrows(ExecRefused.class, () -> PythonRuntime.validate(notPython)).code());
        assertEquals(ErrorCode.EXEC_PYTHON_INVALID, assertThrows(ExecRefused.class, () -> PythonRuntime.validate(Path.of("python.exe"))).code(), "relativo");
    }
}
