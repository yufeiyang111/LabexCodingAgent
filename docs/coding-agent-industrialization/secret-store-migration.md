# Secret Store Migration

## Scope

This migration moves `t_agent_model_config.api_key` and `t_agent_mcp_server.auth_header` into AES-GCM envelope columns. New writes use `api_key_encrypted` and `auth_header_encrypted`; legacy plaintext columns are cleared after successful encryption.

## Upgrade

1. Confirm the database is MySQL 8.0.29 or later, then back it up before deploying the new backend. The additive `ADD COLUMN IF NOT EXISTS` migration is not compatible with earlier 8.0 releases.
2. Set `LABEX_AGENT_SECRET_STORE_MASTER_KEY` to a Base64-encoded random 32-byte key in production. Keep the same key available to every backend instance.
3. Deploy the backend. `schema.sql` adds the new nullable columns idempotently, then `SecretCredentialMigrationRunner` encrypts existing credentials at startup.
4. Verify model and MCP connections after the migration. Do not log or export the credential values during verification.

## Rollback

The previous backend cannot read encrypted credential columns. To roll back safely:

1. Stop the new backend before changing versions.
2. Restore the database backup created before the migration.
3. Deploy the previous backend version.

Do not copy encrypted values into legacy plaintext columns. If no pre-migration backup exists, keep the new backend deployed and re-enter affected credentials through the UI only after the correct master key is restored.
