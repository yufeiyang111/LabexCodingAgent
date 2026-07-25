package com.labex.labexagent.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class LocalEnvelopeSecretStoreTest {

    private static final String MASTER_KEY = Base64.getEncoder().encodeToString(
            "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));

    @Test
    void encryptsCredentialsAndOnlyOpensThemForTheirDeclaredScope() {
        LocalEnvelopeSecretStore store = new LocalEnvelopeSecretStore(MASTER_KEY, false);
        SecretStore.StoredSecret stored = store.store(SecretStore.SecretScope.MODEL_API_KEY, "sk-test-secret");

        assertNotEquals("sk-test-secret", stored.ciphertext());
        try (SecretStore.SecretLease lease = store.open(SecretStore.SecretScope.MODEL_API_KEY, stored.ciphertext())) {
            assertEquals("sk-test-secret", lease.value());
        }
        assertThrows(SecretStore.SecretStoreException.class,
                () -> store.open(SecretStore.SecretScope.MCP_AUTH_HEADER, stored.ciphertext()));
    }

    @Test
    void requiresAnExplicitMasterKeyInProduction() {
        assertThrows(SecretStore.SecretStoreException.class,
                () -> new LocalEnvelopeSecretStore("", true));
    }
}
