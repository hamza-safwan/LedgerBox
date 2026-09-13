package dev.ledgerbank.customer;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class PiiCrypto {
    private final SecretKey key;
    private final String keyVersion;
    private final SecureRandom random = new SecureRandom();
    PiiCrypto(@Value("${ledgerbank.pii-key}") String encoded,
              @Value("${ledgerbank.pii-key-version:v1}") String keyVersion) {
        try {
            byte[] material = Base64.getDecoder().decode(encoded);
            key = new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(material), "AES");
            this.keyVersion = keyVersion;
        } catch (GeneralSecurityException | IllegalArgumentException e) { throw new IllegalStateException("Invalid PII key", e); }
    }
    String encrypt(String clear) {
        try {
            byte[] iv = new byte[12]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(clear.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv,0,combined,0,iv.length); System.arraycopy(encrypted,0,combined,iv.length,encrypted.length);
            return keyVersion + ":" + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("PII encryption failed", e); }
    }
    String decrypt(String encoded) {
        try {
            String[] versioned = encoded.split(":", 2);
            if (versioned.length != 2 || !versioned[0].equals(keyVersion)) throw new IllegalStateException("Unsupported PII key version");
            byte[] combined=Base64.getDecoder().decode(versioned[1]), iv=java.util.Arrays.copyOfRange(combined,0,12);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,iv));
            return new String(cipher.doFinal(java.util.Arrays.copyOfRange(combined,12,combined.length)),StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("PII decryption failed", e); }
    }
}
