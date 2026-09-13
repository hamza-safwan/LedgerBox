package dev.ledgerbank.customer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class PiiCryptoTest {
    @Test
    void ciphertextIsVersionedAuthenticatedAndRandomized() {
        String key = Base64.getEncoder().encodeToString("test-only-key-material".getBytes());
        PiiCrypto crypto = new PiiCrypto(key, "v7");
        String first = crypto.encrypt("Synthetic Customer");
        String second = crypto.encrypt("Synthetic Customer");
        assertTrue(first.startsWith("v7:"));
        assertNotEquals(first, second);
        assertEquals("Synthetic Customer", crypto.decrypt(first));
        assertThrows(IllegalStateException.class, () -> new PiiCrypto(key, "v8").decrypt(first));
    }
}
