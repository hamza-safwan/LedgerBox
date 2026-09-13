package dev.ledgerbank.identity;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.JOSEException;
import java.io.*;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class JwtKeyProvider {
    private final RSAKey rsaKey;

    JwtKeyProvider(@Value("${ledgerbank.jwt-key-path:}") String configuredPath) {
        try {
            Path path = configuredPath.isBlank() ? null : Path.of(configuredPath);
            if (path != null && Files.exists(path)) {
                rsaKey = load(path);
                return;
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            rsaKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString()).build();
            if (path != null) save(path, rsaKey);
        } catch (GeneralSecurityException | IOException | JOSEException e) {
            throw new IllegalStateException("Cannot initialize JWT signing key", e);
        }
    }

    private RSAKey load(Path path) throws IOException, GeneralSecurityException {
        Properties stored = new Properties();
        try (InputStream input = Files.newInputStream(path)) { stored.load(input); }
        KeyFactory factory = KeyFactory.getInstance("RSA");
        RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(stored.getProperty("publicKey"))));
        RSAPrivateKey privateKey = (RSAPrivateKey) factory.generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(stored.getProperty("privateKey"))));
        return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(stored.getProperty("keyId")).build();
    }

    private void save(Path path, RSAKey key) throws IOException, JOSEException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Properties stored = new Properties();
        stored.setProperty("keyId", key.getKeyID());
        stored.setProperty("publicKey", Base64.getEncoder().encodeToString(key.toRSAPublicKey().getEncoded()));
        stored.setProperty("privateKey", Base64.getEncoder().encodeToString(key.toRSAPrivateKey().getEncoded()));
        Path temporary = Files.createTempFile(parent, "ledgerbank-jwt-", ".tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) { stored.store(output, "LedgerBank local signing key"); }
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    RSAKey privateKey() { return rsaKey; }
    RSAKey publicKey() { return rsaKey.toPublicJWK(); }
}
