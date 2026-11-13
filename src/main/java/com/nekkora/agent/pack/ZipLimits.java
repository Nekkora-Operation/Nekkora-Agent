package com.nekkora.agent.pack;

/**
 * Limites EXPLÍCITOS da leitura do ZIP importado, aplicados durante a extração (bytes efetivamente produzidos), nunca só
 * pelos tamanhos declarados no cabeçalho. Coerentes com o contrato de upload da Platform ({@code PackageInspector}: ZIP de
 * até 50 MiB, até 1000 itens, 50 MiB de conteúdo no pacote). O projeto de origem pode ter mais conteúdo que o pacote (dados
 * e ambiente ficam fora), então o total descompactado da ORIGEM tem teto próprio, maior.
 *
 * @param zipBytes tamanho máximo do ZIP de origem
 * @param entries itens no diretório central (arquivos e pastas)
 * @param totalBytes total descompactado da origem
 * @param fileBytes um arquivo descompactado
 * @param depth níveis de pasta
 * @param ratio expansão máxima de um arquivo acima de {@code ratioFloor} bytes
 * @param ratioFloor abaixo disto a razão não é conferida (arquivos pequenos e muito repetitivos são normais)
 */
public record ZipLimits(long zipBytes, int entries, long totalBytes, long fileBytes, int depth, int ratio, long ratioFloor) {

    public static final ZipLimits DEFAULT = new ZipLimits(50L * 1024 * 1024, 1000, 100L * 1024 * 1024, 50L * 1024 * 1024, 16, 200, 1024 * 1024);

    /** Limites do PACOTE gerado (contrato do Agent e da Platform). */
    public static final int PACKAGE_FILES = 500;
    public static final long PACKAGE_BYTES = 50L * 1024 * 1024;
}
