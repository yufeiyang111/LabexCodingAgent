package com.labex.labexagent.secret;

/**
 * Encrypts durable secret values and exposes them only through short-lived leases.
 */
public interface SecretStore {
    StoredSecret store(SecretScope scope, String plaintext);

    SecretLease open(SecretScope scope, String ciphertext);

    enum SecretScope {
        MODEL_API_KEY,
        MCP_AUTH_HEADER,
        WORKER_ENV
    }

    record StoredSecret(String ciphertext, String keyVersion) {
    }

    interface SecretLease extends AutoCloseable {
        String value();

        @Override
        void close();
    }

    final class SecretStoreException extends IllegalStateException {
        public SecretStoreException(String message) {
            super(message);
        }

        public SecretStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
