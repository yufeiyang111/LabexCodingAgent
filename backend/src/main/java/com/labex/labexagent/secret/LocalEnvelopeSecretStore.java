package com.labex.labexagent.secret;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Development envelope implementation. Production requires an explicit 256-bit master key.
 */
@Component
public class LocalEnvelopeSecretStore implements SecretStore {
    private static final String KEY_VERSION = "local-v1";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String DEVELOPMENT_KEY_MATERIAL =
            "LabexAgent local development secret store key. Do not use in production.";

    private final SecretKey masterKey;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public LocalEnvelopeSecretStore(
            @Value("${labex-agent.secret-store.master-key:${LABEX_AGENT_SECRET_STORE_MASTER_KEY:}}") String encodedMasterKey,
            Environment environment) {
        this(encodedMasterKey, environment != null
                && (environment.matchesProfiles("prod") || environment.matchesProfiles("production")));
    }

    public LocalEnvelopeSecretStore(String encodedMasterKey, boolean production) {
        this.masterKey = new SecretKeySpec(resolveKey(encodedMasterKey, production), "AES");
    }

    @Override
    public StoredSecret store(SecretScope scope, String plaintext) {
        if (scope == null || plaintext == null || plaintext.isBlank()) {
            throw new SecretStoreException("Secret value is required");
        }
        byte[] value = plaintext.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(scope.name().getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(value);
            return new StoredSecret(KEY_VERSION + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(iv) + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted), KEY_VERSION);
        } catch (Exception e) {
            throw new SecretStoreException("Secret encryption failed", e);
        } finally {
            Arrays.fill(value, (byte) 0);
        }
    }

    @Override
    public SecretLease open(SecretScope scope, String ciphertext) {
        if (scope == null || ciphertext == null || ciphertext.isBlank()) {
            throw new SecretStoreException("Encrypted secret is required");
        }
        String[] parts = ciphertext.split("\\.", -1);
        if (parts.length != 3 || !KEY_VERSION.equals(parts[0])) {
            throw new SecretStoreException("Encrypted secret uses an unsupported key version");
        }
        byte[] plaintext = null;
        try {
            byte[] iv = Base64.getUrlDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getUrlDecoder().decode(parts[2]);
            if (iv.length != IV_BYTES) {
                throw new SecretStoreException("Encrypted secret is invalid");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(scope.name().getBytes(StandardCharsets.UTF_8));
            plaintext = cipher.doFinal(encrypted);
            return new Lease(new String(plaintext, StandardCharsets.UTF_8).toCharArray());
        } catch (SecretStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new SecretStoreException("Encrypted secret cannot be opened for this scope", e);
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private byte[] resolveKey(String encodedMasterKey, boolean production) {
        if (encodedMasterKey == null || encodedMasterKey.isBlank()) {
            if (production) {
                throw new SecretStoreException("Production requires LABEX_AGENT_SECRET_STORE_MASTER_KEY");
            }
            return sha256(DEVELOPMENT_KEY_MATERIAL.getBytes(StandardCharsets.UTF_8));
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encodedMasterKey.trim());
            if (decoded.length != KEY_BYTES) {
                throw new SecretStoreException("Secret store master key must be base64-encoded 32 bytes");
            }
            return decoded;
        } catch (SecretStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new SecretStoreException("Secret store master key must be base64-encoded 32 bytes", e);
        }
    }

    private byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (Exception e) {
            throw new SecretStoreException("Unable to initialize local secret store", e);
        }
    }

    private static final class Lease implements SecretLease {
        private char[] value;

        private Lease(char[] value) {
            this.value = value;
        }

        @Override
        public String value() {
            if (value == null) {
                throw new SecretStoreException("Secret lease is closed");
            }
            return new String(value);
        }

        @Override
        public void close() {
            if (value != null) {
                Arrays.fill(value, '\0');
                value = null;
            }
        }
    }
}
