package com.nekkora.agent.pack;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Regras de nome dos itens de um ZIP importado (dado NÃO confiável). Três níveis, que não se confundem:
 * <ul>
 *   <li>{@link #unsafe}: o nome escaparia da pasta ou é ambíguo (absoluto, {@code ..}, unidade, caminho de rede, barra
 *       invertida, dois-pontos, controle) → o ZIP inteiro é recusado;</li>
 *   <li>{@link #windowsInvalid}: o Windows não grava o nome (reservado, caractere proibido, terminado em ponto ou espaço) →
 *       recusado;</li>
 *   <li>{@link #contractSafe}: o nome pode entrar num pacote (contrato do Agent e da Platform: só ASCII sem espaço). Um nome
 *       com espaço ou acento é LIDO normalmente, mas não pode ir para o pacote (a pessoa decide: renomear ou deixar fora).</li>
 * </ul>
 */
public final class ZipNames {
    private ZipNames() {}

    /** O mesmo padrão do {@code ExecutionPackage} e do {@code PackageInspector} da Platform. */
    static final Pattern CONTRACT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_./-]{0,199}");
    private static final Set<String> RESERVED = Set.of("con", "prn", "aux", "nul", "conin$", "conout$", "clock$");
    private static final Pattern DEVICE = Pattern.compile("(com|lpt)[0-9¹²³]");

    /** Nome que escapa da raiz ou é ambíguo como caminho. {@code name} sem a barra final de pasta. */
    static boolean unsafe(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":") || name.contains("//")) return true;
        if (name.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) return true;
        for (String seg : name.split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) return true;
        }
        return false;
    }

    /** Segmento que o Windows não aceita como nome de arquivo ou pasta. */
    static boolean windowsInvalid(String name) {
        for (String seg : name.split("/")) {
            if (seg.endsWith(".") || seg.endsWith(" ")) return true;
            for (char c : seg.toCharArray()) if ("<>\"|?*".indexOf(c) >= 0) return true;
            String base = seg.toLowerCase(Locale.ROOT);
            int dot = base.indexOf('.');
            if (dot >= 0) base = base.substring(0, dot);
            base = base.stripTrailing();
            if (RESERVED.contains(base) || DEVICE.matcher(base).matches()) return true;
        }
        return false;
    }

    /** Profundidade em segmentos. */
    static int depth(String name) {
        return name.split("/").length;
    }

    /** Chave de colisão: o Windows compara sem diferença de caixa; formas Unicode equivalentes são o mesmo nome. */
    static String collisionKey(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    /** O nome pode entrar num pacote (sem espaços, sem acentos, até 200 caracteres). */
    public static boolean contractSafe(String rel) {
        return CONTRACT.matcher(rel).matches() && !rel.contains("..") && !rel.contains("//") && !rel.startsWith("/");
    }

    /** Texto exibível de um nome não confiável: sem controle nem bidi, curto. Nunca para log. */
    public static String display(String name) {
        if (name == null) return "";
        StringBuilder b = new StringBuilder();
        name.codePoints().forEach(cp -> {
            int t = Character.getType(cp);
            boolean bidi = cp >= 0x202A && cp <= 0x202E || cp >= 0x2066 && cp <= 0x2069 || cp == 0x200E || cp == 0x200F;
            b.appendCodePoint(Character.isISOControl(cp) || bidi || t == Character.FORMAT ? '?' : cp);
        });
        String s = b.toString();
        return s.length() > 120 ? s.substring(0, 60) + "…" + s.substring(s.length() - 59) : s;
    }
}
