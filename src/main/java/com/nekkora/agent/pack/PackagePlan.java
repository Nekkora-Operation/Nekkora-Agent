package com.nekkora.agent.pack;

import java.util.List;
import java.util.Set;

/**
 * As escolhas da pessoa para gerar um pacote Python externo a partir do ZIP importado. Nome e entrada são os campos
 * principais; Python e dependências só são pedidos quando o projeto não os declara no formato aceito.
 *
 * @param name nome do pacote ({@code name} do descritor)
 * @param version versão DECLARADA no pacote ({@code packageVersion}); sugestão local, nunca o número de versão da Platform
 * @param entry o {@code .py} de entrada, escolhido explicitamente pela pessoa
 * @param python versão de Python no formato do descritor
 * @param dependencies dependências com versão exata (a lista que vai para o descritor)
 * @param dependenciesConfirmed a pessoa conferiu a lista (obrigatório quando o projeto não a declara completa)
 * @param includeData arquivos fora por padrão (dados) que a pessoa decidiu incluir
 * @param exclude arquivos incluídos por padrão que a pessoa decidiu deixar fora
 * @param rebuild gerar um pacote NOVO a partir de um pacote externo existente (a pessoa mudou entrada ou requisitos)
 */
public record PackagePlan(String name, String version, String entry, String python, List<String> dependencies, boolean dependenciesConfirmed,
                          Set<String> includeData, Set<String> exclude, boolean rebuild) {

    public PackagePlan {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        includeData = includeData == null ? Set.of() : Set.copyOf(includeData);
        exclude = exclude == null ? Set.of() : Set.copyOf(exclude);
    }

    /** Gerador declarado nos pacotes do assistente (declaração, não prova de origem). */
    public static final String GENERATOR = "nekkora-agent-assistente/1.0.0";
}
