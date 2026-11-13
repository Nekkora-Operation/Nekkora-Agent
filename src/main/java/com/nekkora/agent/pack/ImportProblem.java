package com.nekkora.agent.pack;

import java.util.Optional;

/**
 * AGENT-PACOTES-001: recusa da importação ou do empacotamento, com motivo de vocabulário FECHADO e texto para a pessoa.
 * O {@code detail} é um nome de arquivo ou parâmetro já saneado ({@link ZipNames#display}); ele aparece só na tela e nunca
 * vai para o log (pode ser nome de um cliente).
 */
public final class ImportProblem extends Exception {

    /** Motivos. O código ({@code AGT-PAC-xxx}) pode ir para o log; o texto vai para a tela. */
    public enum Code {
        // arquivo de origem
        SOURCE_NOT_FOUND("AGT-PAC-001", "O arquivo escolhido não existe ou não pôde ser aberto."),
        SOURCE_LOCKED("AGT-PAC-002", "O arquivo está em uso por outro programa e não pôde ser lido. Feche o programa e tente de novo."),
        SOURCE_CHANGED("AGT-PAC-003", "O arquivo mudou enquanto era lido ou depois da leitura. Importe o ZIP de novo."),
        EMPTY_FILE("AGT-PAC-004", "O arquivo está vazio."),
        NOT_ZIP("AGT-PAC-005", "O arquivo não é um ZIP válido: o conteúdo não é de um arquivo compactado, mesmo que o nome termine em .zip."),
        TRUNCATED("AGT-PAC-006", "O ZIP está incompleto ou corrompido (por exemplo, um download interrompido). Gere ou baixe o ZIP de novo."),
        CORRUPT_ENTRY("AGT-PAC-007", "Um arquivo dentro do ZIP está corrompido: o conteúdo não confere com o que o ZIP registra."),
        ENCRYPTED("AGT-PAC-008", "O ZIP está protegido por senha. Gere o ZIP sem senha."),
        UNSUPPORTED_METHOD("AGT-PAC-009", "O ZIP usa um método de compressão não suportado. Gere de novo com a compressão padrão (Deflate) do Windows ou do seu compactador."),
        UNSUPPORTED_FORMAT("AGT-PAC-010", "O ZIP usa um formato estendido (ZIP64 ou dividido em partes), que não é suportado."),
        ZIP_TOO_LARGE("AGT-PAC-011", "O ZIP passa do limite de 50 MiB."),
        TOO_MANY_ENTRIES("AGT-PAC-012", "O ZIP tem mais de 1000 itens."),
        CONTENT_TOO_LARGE("AGT-PAC-013", "O conteúdo descompactado passa do limite de 100 MiB."),
        FILE_TOO_LARGE("AGT-PAC-014", "Um arquivo do ZIP passa do limite de 50 MiB."),
        EXPANSION("AGT-PAC-015", "Um arquivo do ZIP se expande demais ao descompactar (possível bomba de descompactação)."),
        MORE_THAN_DECLARED("AGT-PAC-016", "Um arquivo do ZIP tem mais conteúdo do que o ZIP declara (cabeçalho enganoso)."),
        OVERLAPPING("AGT-PAC-017", "O ZIP tem itens sobrepostos ou ambíguos."),
        NO_FILES("AGT-PAC-018", "O ZIP não tem nenhum arquivo."),
        // nomes e tipos de item
        UNSAFE_PATH("AGT-PAC-020", "O ZIP tem um caminho que sai da pasta do projeto (caminho absoluto, “..”, unidade do Windows ou caminho de rede)."),
        SPECIAL_ENTRY("AGT-PAC-021", "O ZIP tem uma ligação simbólica ou outro tipo especial de item, que não é aceito."),
        DUPLICATE("AGT-PAC-022", "O ZIP tem o mesmo caminho mais de uma vez (inclusive só com maiúsculas e minúsculas diferentes, que no Windows são o mesmo)."),
        FILE_DIR_CONFLICT("AGT-PAC-023", "O ZIP tem um arquivo e uma pasta com o mesmo caminho."),
        WINDOWS_NAME("AGT-PAC-024", "O ZIP tem um nome que o Windows não aceita (nome reservado, caractere inválido ou terminado em ponto ou espaço)."),
        TOO_DEEP("AGT-PAC-025", "O ZIP tem pastas aninhadas demais (mais de 16 níveis)."),
        UNDECLARED_ENCODING("AGT-PAC-026", "O ZIP tem nomes com acentos sem declarar a codificação (UTF-8); o nome não pode ser lido com segurança. Gere o ZIP de novo com um compactador atual."),
        // projeto e pacote
        DESCRIPTOR_CONFLICT("AGT-PAC-030", "O ZIP traz descritores de formatos diferentes na raiz (pacote Python externo e pacote do Studio ou de laboratório). Um pacote tem um único formato."),
        EXTERNAL_INVALID("AGT-PAC-031", "O ZIP tem um nekkora-external.json, mas o pacote não é válido."),
        STUDIO_INVALID("AGT-PAC-032", "O ZIP tem o manifesto de um pacote do Studio (ou o descritor de laboratório), mas o pacote não é válido."),
        NO_PYTHON_FILES("AGT-PAC-033", "O ZIP não tem nenhum arquivo .py para servir de entrada."),
        ENTRY_INVALID("AGT-PAC-034", "Escolha um arquivo .py do projeto como entrada."),
        NAME_INVALID("AGT-PAC-035", "O nome precisa começar com letra ou número e ter só letras sem acento, números, ponto, hífen ou sublinhado (até 80)."),
        VERSION_INVALID("AGT-PAC-036", "A versão precisa ter o formato x.y.z, só com números (por exemplo, 1.0.0)."),
        PYTHON_INVALID("AGT-PAC-037", "A versão de Python precisa ter um formato aceito pelo pacote, por exemplo >=3.10,<3.14 ou ==3.12.*."),
        DEPENDENCY_INVALID("AGT-PAC-038", "Cada dependência precisa ter versão exata (nome==1.2.3), sem repetir."),
        NAME_OUTSIDE_CONTRACT("AGT-PAC-039", "Há arquivos com nome que o formato de pacote não aceita (espaços, acentos ou caracteres especiais). Renomeie no projeto ou deixe-os fora do pacote."),
        PACKAGE_LIMIT("AGT-PAC-040", "O pacote passaria dos limites do formato (500 arquivos ou 50 MiB de conteúdo)."),
        FORBIDDEN_INCLUDED("AGT-PAC-041", "Ambiente virtual, cache, controle de versão e credenciais nunca entram no pacote."),
        NOT_CONVERTIBLE("AGT-PAC-043", "Pacotes do Studio e de laboratório não são convertidos em pacote Python externo: eles seguem para a preparação da execução como estão."),
        ALREADY_PACKAGE("AGT-PAC-044", "Este ZIP já é um pacote Python externo válido. Use-o como está ou escolha preparar de novo para mudar a entrada ou os requisitos."),
        RESERVED_ROOT_NAME("AGT-PAC-045", "A raiz do projeto tem um manifest.json ou nekkora-agent-lab.json que não é de um pacote reconhecido. O formato do pacote reserva esses nomes na raiz: deixe o arquivo fora do pacote, renomeie ou mova-o para uma subpasta."),
        DEPENDENCIES_UNCONFIRMED("AGT-PAC-042", "Confirme as dependências do projeto: o Agent não as deduz pelos imports."),
        // gravação
        TARGET_EXISTS("AGT-PAC-050", "Já existe um arquivo com esse nome na pasta escolhida."),
        SAME_AS_SOURCE("AGT-PAC-056", "O pacote não pode ser gravado sobre o ZIP de origem. Escolha outra pasta ou outro nome."),
        NO_SPACE("AGT-PAC-051", "Não há espaço suficiente no disco para gravar o pacote. Nada foi deixado pela metade."),
        WRITE_FAILED("AGT-PAC-052", "Não foi possível gravar o pacote na pasta escolhida (sem permissão ou arquivo em uso). Nada foi deixado pela metade."),
        VERIFY_FAILED("AGT-PAC-053", "O pacote gravado não passou na conferência final e foi descartado."),
        WORK_FAILED("AGT-PAC-054", "O Agent não conseguiu usar a pasta de trabalho temporária dele."),
        CANCELLED("AGT-PAC-055", "Operação cancelada. Nada foi gerado e o ZIP original não foi alterado.");

        private final String code;
        private final String text;

        Code(String code, String text) {
            this.code = code;
            this.text = text;
        }

        public String code() {
            return code;
        }

        public String text() {
            return text;
        }
    }

    private final Code code;
    private final String detail;

    public ImportProblem(Code code) {
        this(code, null);
    }

    public ImportProblem(Code code, String detail) {
        super(code.code(), null, false, false);
        this.code = code;
        this.detail = detail;
    }

    public Code code() {
        return code;
    }

    /** Nome (saneado) do item ou o valor que causou a recusa, quando ajuda a pessoa; nunca para log. */
    public Optional<String> detail() {
        return Optional.ofNullable(detail);
    }

    /** Texto completo para a tela. */
    public String message() {
        return code.text() + (detail == null ? "" : " (" + detail + ")") + " [" + code.code() + "]";
    }
}
