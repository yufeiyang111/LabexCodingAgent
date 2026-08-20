package com.labex.labexagent.secret;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One-shot tool: re-encrypts existing secret-store ciphertext with a new 32-byte master key.
 *
 * <p>Old ciphertext was produced with the local development key (empty master key). The tool
 * opens each envelope with that development key and re-encrypts it with the supplied new key,
 * inside a single transaction. A backup table is created before the migration.
 *
 * <p>Run manually (does not start Spring):
 * <pre>
 *   java -cp target/classes;&lt;deps&gt; com.labex.labexagent.secret.MasterKeyMigrationTool \
 *     --db-url jdbc:mysql://localhost:3306/labex_agent... \
 *     --db-user root --db-password &lt;pw&gt; --new-key &lt;base64-32-bytes&gt;
 * </pre>
 */
public final class MasterKeyMigrationTool {

    private static final String BACKUP_TABLE = "t_agent_model_config_masterkey_bak";

    private record TableJob(String table, String idColumn, String cipherColumn, String versionColumn,
                            SecretStore.SecretScope scope) {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        String dbUrl = required(opts, "db-url");
        String dbUser = required(opts, "db-user");
        String dbPassword = required(opts, "db-password");
        String newKey = required(opts, "new-key");

        LocalEnvelopeSecretStore oldStore = new LocalEnvelopeSecretStore("", false);
        LocalEnvelopeSecretStore newStore = new LocalEnvelopeSecretStore(newKey, false);
        System.out.println("[OK] Both key stores initialized");

        List<TableJob> jobs = List.of(
                new TableJob("t_agent_model_config", "config_id", "api_key_encrypted",
                        "api_key_key_version", SecretStore.SecretScope.MODEL_API_KEY),
                new TableJob("t_agent_mcp_server", "server_id", "auth_header_encrypted",
                        "auth_header_key_version", SecretStore.SecretScope.MCP_AUTH_HEADER),
                new TableJob("t_agent_project_secret_binding", "binding_id", "encrypted_value",
                        "key_version", SecretStore.SecretScope.WORKER_ENV));

        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            for (TableJob job : jobs) {
                migrateTable(conn, job, oldStore, newStore);
            }
        }
        System.out.println("Migration finished.");
    }

    private static void migrateTable(Connection conn, TableJob job,
                                     LocalEnvelopeSecretStore oldStore,
                                     LocalEnvelopeSecretStore newStore) throws Exception {
        List<Long> ids = new ArrayList<>();
        List<String> ciphertexts = new ArrayList<>();
        String selectSql = "SELECT " + job.idColumn() + ", " + job.cipherColumn() + " FROM " + job.table()
                + " WHERE " + job.cipherColumn() + " IS NOT NULL AND " + job.cipherColumn() + " <> ''";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(selectSql)) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
                ciphertexts.add(rs.getString(2));
            }
        }
        System.out.println("[" + job.table() + "] rows with encrypted payload: " + ids.size());
        if (ids.isEmpty()) {
            return;
        }

        if ("t_agent_model_config".equals(job.table())) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DROP TABLE IF EXISTS " + BACKUP_TABLE);
                st.executeUpdate("CREATE TABLE " + BACKUP_TABLE + " AS SELECT * FROM " + job.table());
            }
            System.out.println("[OK] Backup table created: " + BACKUP_TABLE);
        }

        List<Long> failed = new ArrayList<>();
        conn.setAutoCommit(false);
        try {
            String updateSql = "UPDATE " + job.table() + " SET " + job.cipherColumn() + " = ?, "
                    + job.versionColumn() + " = ? WHERE " + job.idColumn() + " = ?";
            try (PreparedStatement upd = conn.prepareStatement(updateSql)) {
                for (int i = 0; i < ids.size(); i++) {
                    long id = ids.get(i);
                    String ciphertext = ciphertexts.get(i);
                    try (SecretStore.SecretLease lease = oldStore.open(job.scope(), ciphertext)) {
                        SecretStore.StoredSecret reEncrypted = newStore.store(job.scope(), lease.value());
                        try (SecretStore.SecretLease verify = newStore.open(job.scope(), reEncrypted.ciphertext())) {
                            if (!verify.value().equals(lease.value())) {
                                throw new SecretStore.SecretStoreException("round-trip mismatch");
                            }
                        }
                        upd.setString(1, reEncrypted.ciphertext());
                        upd.setString(2, reEncrypted.keyVersion());
                        upd.setLong(3, id);
                        upd.addBatch();
                    } catch (Exception e) {
                        failed.add(id);
                        System.err.println("[WARN] " + job.table() + " id=" + id
                                + " could not be migrated: " + e.getMessage());
                    }
                }
                upd.executeBatch();
            }
            conn.commit();
        } catch (Exception e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
        System.out.println("[" + job.table() + "] migrated=" + (ids.size() - failed.size())
                + ", failed=" + failed.size());
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--")) {
                String[] kv = arg.substring(2).split("=", 2);
                opts.put(kv[0], kv.length > 1 ? kv[1] : "");
            }
        }
        return opts;
    }

    private static String required(Map<String, String> opts, String key) {
        String value = opts.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required option --" + key);
        }
        return value;
    }

    static {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("MySQL driver not on classpath", e);
        }
    }
}
