package com.labex.auth;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AuthSchemaTest {
    @Test
    void schemaContainsOptionalEmailAndOAuthBindingContract() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/sql/schema.sql"));

        assertTrue(schema.contains("email VARCHAR(254) DEFAULT NULL"));
        assertTrue(schema.contains("UNIQUE KEY uk_user_email (email)"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS t_user_oauth_binding"));
        assertTrue(schema.contains("UNIQUE KEY uk_user_oauth_provider_subject (provider, subject)"));
        assertTrue(schema.contains("UNIQUE KEY uk_user_oauth_user_provider (user_id, provider)"));
    }
}

