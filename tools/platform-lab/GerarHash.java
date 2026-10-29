import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * AGENT-PLATFORM-001 (laboratório): hash Argon2id no formato PHC que a Platform verifica
 * ({@code $argon2id$v=19$m=65536,t=3,p=1$<sal>$<hash>}, base64 sem preenchimento), para a identidade de LABORATÓRIO
 * criada como fixture declarada (o mesmo que o AgentChannelE2E da API faz). A senha vem da entrada padrão, nunca de
 * argumento de processo. Uso: java -cp bcprov-jdk18on.jar GerarHash.java < senha
 */
public class GerarHash {
    public static void main(String[] args) throws Exception {
        String senha = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
        byte[] sal = new byte[16];
        new SecureRandom().nextBytes(sal);
        Argon2BytesGenerator g = new Argon2BytesGenerator();
        g.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13).withSalt(sal)
                .withMemoryAsKB(65536).withIterations(3).withParallelism(1).build());
        byte[] h = new byte[32];
        g.generateBytes(senha.getBytes(StandardCharsets.UTF_8), h);
        Base64.Encoder b = Base64.getEncoder().withoutPadding();
        System.out.println("$argon2id$v=19$m=65536,t=3,p=1$" + b.encodeToString(sal) + "$" + b.encodeToString(h));
    }
}
