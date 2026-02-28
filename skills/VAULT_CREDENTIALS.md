# Skill Reference: VAULT_CREDENTIALS

This document describes how to provision and manage SSH credentials for remote servers used by the [REMOTE_EXEC](REMOTE_EXEC.md) skill. Credentials are retrieved at runtime from **HashiCorp Vault KV v2** or from environment variables (fallback for development/testing).

---

## Overview

When `REMOTE_EXEC` runs, it calls `VaultCredentialService.getCredentials(hostname)` before any SSH connection is attempted:

```
1. Build Vault path: <mount>/data/<prefix>/<hostname>
2. HTTP GET to Vault with X-Vault-Token header
3. Parse KV v2 response → return ServerCredentials
4. If Vault unavailable → try env-var fallback
5. if env-var not set → throw CredentialNotFoundException → execution aborted
```

Credentials are **never** written to disk, never logged, and exist only in memory for the duration of the SSH session.

---

## Vault KV v2 Secret Structure

Store one secret per server at the path:

```
secret/mcp/servers/<hostname>
```

The secret must contain these keys:

```json
{
  "username": "mcp-bot",
  "auth_type": "key",
  "private_key": "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC...\n-----END OPENSSH PRIVATE KEY-----",
  "passphrase": "",
  "password": ""
}
```

| Key | Required | Description |
|-----|----------|-------------|
| `username` | Yes | SSH login username |
| `auth_type` | Yes | `key` (SSH private key) or `password` |
| `private_key` | If `auth_type=key` | PEM-encoded OpenSSH private key (RSA/Ed25519) |
| `passphrase` | Optional | Passphrase protecting the private key |
| `password` | If `auth_type=password` | SSH password (less secure — prefer key auth) |

---

## Setting Up Secrets in Vault

### Prerequisites

```bash
# Enable KV v2 engine at 'secret' mount (if not already enabled)
vault secrets enable -path=secret kv-v2
```

### Provision a key-based credential

```bash
# Step 1: Generate a dedicated SSH key for MCP
ssh-keygen -t ed25519 -C "mcp-bot@mcp-automation" -f mcp-bot-ed25519 -N ""

# Step 2: Add public key to the target server's authorized_keys
ssh-copy-id -i mcp-bot-ed25519.pub mcp-bot@app-server-01

# Step 3: Store private key in Vault
vault kv put secret/mcp/servers/app-server-01 \
  username="mcp-bot" \
  auth_type="key" \
  private_key="$(cat mcp-bot-ed25519)" \
  passphrase=""

# Step 4: Verify the secret was stored
vault kv get secret/mcp/servers/app-server-01
```

### Provision a password-based credential (not recommended for production)

```bash
vault kv put secret/mcp/servers/dev-server-01 \
  username="admin" \
  auth_type="password" \
  password="s3cur3-p@ssword"
```

---

## Vault Access Policy

The MCP application needs a Vault token with this policy:

```hcl
# mcp-credential-policy.hcl
path "secret/data/mcp/servers/*" {
  capabilities = ["read"]
}

path "secret/metadata/mcp/servers/*" {
  capabilities = ["list"]
}
```

Apply it:
```bash
vault policy write mcp-credential-policy mcp-credential-policy.hcl
```

Generate a periodic token (recommended) or AppRole:
```bash
# Periodic token
vault token create \
  -policy="mcp-credential-policy" \
  -period=720h \
  -display-name="mcp-automation"

# AppRole (production-recommended)
vault auth enable approle
vault write auth/approle/role/mcp-automation \
  token_policies="mcp-credential-policy" \
  token_ttl=1h \
  token_max_ttl=4h
```

---

## MCP Configuration

```yaml
mcp:
  vault:
    # Vault server address
    url: http://vault.internal:8200

    # Vault token — use AppRole token in production
    token: ${VAULT_TOKEN:}

    # KV v2 mount point
    mount: secret

    # Path prefix under the mount
    # Full path: <mount>/data/<prefix>/<hostname>
    prefix: mcp/servers

    # HTTP connection timeout (milliseconds)
    timeout-ms: 5000
```

Set the token via environment variable for security:
```bash
export VAULT_TOKEN="s.Abc123xyz"
```

---

## Environment Variable Fallback

If Vault is unavailable or `VAULT_TOKEN` is not set, `VaultCredentialService` falls back to environment variables. This is useful for local development and CI/CD pipelines.

### Naming Convention

```
MCP_SSH_<HOSTNAME_UPPERCASE_WITH_DASHES_AS_UNDERSCORES>_USER
MCP_SSH_<HOSTNAME_UPPERCASE_WITH_DASHES_AS_UNDERSCORES>_KEY
MCP_SSH_<HOSTNAME_UPPERCASE_WITH_DASHES_AS_UNDERSCORES>_PASSWORD
MCP_SSH_<HOSTNAME_UPPERCASE_WITH_DASHES_AS_UNDERSCORES>_PASSPHRASE
```

### Examples

For hostname `app-server-01`:
```bash
export MCP_SSH_APP_SERVER_01_USER="mcp-bot"
export MCP_SSH_APP_SERVER_01_KEY="$(cat ~/.ssh/mcp-bot-ed25519)"
```

For hostname `win-app-01`:
```bash
export MCP_SSH_WIN_APP_01_USER="Administrator"
export MCP_SSH_WIN_APP_01_PASSWORD="W1nd0ws-P@ss!"
```

---

## server_inventory Table

Every remote target must also be registered in the `server_inventory` database table so the MCP engine knows it exists:

```sql
INSERT INTO server_inventory (
  hostname, ip_address, os_type, ssh_port, vault_path, environment, tags, enabled
) VALUES (
  'app-server-01',
  '10.0.1.10',
  'LINUX',
  22,
  'secret/mcp/servers/app-server-01',  -- matches Vault path
  'production',
  '["tomcat","application"]',
  true
);
```

| Column | Description |
|--------|-------------|
| `hostname` | Must exactly match the action key param in REMOTE_EXEC |
| `ip_address` | Used as fallback if DNS resolution of hostname fails |
| `os_type` | `LINUX` or `WINDOWS` |
| `ssh_port` | 22 for Linux; 22 or 5985 for Windows (WinRM) |
| `vault_path` | Doc reference — the actual path is computed by `VaultCredentialService` from config |
| `environment` | `production`, `staging`, `dev` |
| `tags` | JSON array of labels for searching and filtering |
| `enabled` | Set to `false` to prevent REMOTE_EXEC from connecting to this server |

---

## Credential Rotation

1. Generate new SSH key pair on the control machine.
2. Add new public key to the server's `authorized_keys`.
3. Update Vault secret: `vault kv put secret/mcp/servers/<hostname> private_key="<new-key>" ...`
4. Remove old public key from `authorized_keys`.
5. `VaultCredentialService` fetches fresh credentials on each invocation — no restart needed.

---

## Security Notes

- **Never** store `private_key` in `application.yml` or any version-controlled file.
- **Never** store `VAULT_TOKEN` in application configuration — always use `${VAULT_TOKEN:}` env-var substitution.
- Use **key-based auth** over password auth in all production environments.
- `RemoteExecutionService` loads the private key into memory and never writes it to disk.
- `JSch.setKnownHosts()` is not used (StrictHostKeyChecking=no) — for production, add known hosts verification.
- Limit the `mcp-bot` user on remote servers:
  - Add to a restricted group
  - Use `sudoers` with `NOPASSWD` for only the required commands
  - Do not grant login shell (`/bin/false` or `/bin/rbash`)
