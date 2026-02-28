package com.company.mcp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Credentials used by {@link com.company.mcp.service.RemoteExecutionService}
 * to connect to a remote server via SSH (Linux or Windows with OpenSSH).
 *
 * <p>Instances are produced by {@link com.company.mcp.service.VaultCredentialService}
 * — either from HashiCorp Vault KV v2 or from fallback environment variables.</p>
 *
 * <h3>Authentication priority</h3>
 * <ol>
 *   <li>Private key + optional passphrase  (preferred — keyless auth)</li>
 *   <li>Username + password               (fallback)</li>
 * </ol>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServerCredentials {

    // ── Connection ───────────────────────────────────────────────────────────

    /** Hostname or IP address of the target server. */
    private String host;

    /** SSH port — default 22 for Linux, also 22 for Windows OpenSSH. */
    @Builder.Default
    private int sshPort = 22;

    /** Target OS: {@code "linux"} or {@code "windows"}. */
    @Builder.Default
    private String os = "linux";

    // ── Authentication ───────────────────────────────────────────────────────

    /** SSH username (e.g. {@code mcpagent}, {@code Administrator}). */
    private String username;

    /**
     * SSH password — used when {@link #privateKeyPem} is null.
     * Prefer key-based auth; only use passwords as a fallback.
     */
    private String password;

    /**
     * PEM-encoded private key content (the full text including
     * {@code -----BEGIN ... PRIVATE KEY-----} header/footer).
     * When non-null, key-based auth is used and {@link #password} is ignored.
     */
    private String privateKeyPem;

    /**
     * Optional passphrase that protects {@link #privateKeyPem}.
     * Leave null for unencrypted keys.
     */
    private String privateKeyPassphrase;

    /**
     * Optional sudo password for privilege escalation on Linux.
     * When set, scripts are prefixed with {@code sudo -S} and this value
     * is piped into stdin.
     */
    private String sudoPassword;

    // ── Vault metadata ───────────────────────────────────────────────────────

    /**
     * Full KV path in HashiCorp Vault from which these credentials were loaded.
     * Example: {@code secret/mcp/servers/app-server-01}
     * Null when credentials came from env-var fallback.
     */
    private String vaultPath;

    /**
     * Human-readable source for log messages.
     * E.g. {@code "vault:secret/mcp/servers/app-server-01"} or {@code "env-fallback"}.
     */
    private String credentialSource;

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Returns true when key-based authentication should be used. */
    public boolean isKeyBased() {
        return privateKeyPem != null && !privateKeyPem.isBlank();
    }

    /** Returns true when this is a Windows target. */
    public boolean isWindows() {
        return "windows".equalsIgnoreCase(os);
    }

    /** Safe toString — never prints credentials. */
    @Override
    public String toString() {
        return "ServerCredentials{host='" + host + "', port=" + sshPort
                + ", os='" + os + "', user='" + username + "'"
                + ", auth=" + (isKeyBased() ? "key" : "password")
                + ", source='" + credentialSource + "'}";
    }
}
