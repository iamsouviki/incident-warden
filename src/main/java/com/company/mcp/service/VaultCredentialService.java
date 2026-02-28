package com.company.mcp.service;

import com.company.mcp.model.ServerCredentials;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * VaultCredentialService — retrieves SSH / WinRM credentials from
 * HashiCorp Vault KV v2, with a transparent env-var fallback for dev/CI.
 *
 * <h3>Vault lookup path</h3>
 * <pre>
 *   GET {vault.url}/v1/{vault.mount}/data/{vault.servers-prefix}/{hostname}
 *   Header: X-Vault-Token: {vault.token}
 * </pre>
 *
 * <h3>Expected Vault secret fields</h3>
 * <table border="1">
 *   <tr><th>Field</th><th>Type</th><th>Description</th></tr>
 *   <tr><td>username</td><td>string</td><td>SSH login user</td></tr>
 *   <tr><td>password</td><td>string</td><td>SSH password (used when no key)</td></tr>
 *   <tr><td>private_key</td><td>string</td><td>PEM-encoded private key</td></tr>
 *   <tr><td>private_key_passphrase</td><td>string</td><td>Key passphrase (optional)</td></tr>
 *   <tr><td>sudo_password</td><td>string</td><td>Sudo pass for Linux privilege escalation</td></tr>
 *   <tr><td>ssh_port</td><td>int</td><td>Default 22</td></tr>
 *   <tr><td>os</td><td>string</td><td>linux | windows</td></tr>
 * </table>
 *
 * <h3>Env-var fallback (when vault.enabled=false)</h3>
 * <pre>
 *   MCP_SSH_USER       → username
 *   MCP_SSH_PASSWORD   → password
 *   MCP_SSH_KEY_PATH   → path to PEM file on local disk
 * </pre>
 *
 * <h3>Retry</h3>
 * The Vault HTTP call is retried up to 3 times with exponential backoff
 * (1s, 2s, 4s) to handle transient network blips.
 */
@Slf4j
@Service
public class VaultCredentialService {

    // ─── Config ──────────────────────────────────────────────────────────────

    @Value("${mcp.vault.enabled:false}")
    private boolean vaultEnabled;

    @Value("${mcp.vault.url:http://localhost:8200}")
    private String vaultUrl;

    @Value("${mcp.vault.token:dev-root-token}")
    private String vaultToken;

    @Value("${mcp.vault.mount:secret}")
    private String vaultMount;

    @Value("${mcp.vault.servers-prefix:mcp/servers}")
    private String serversPrefix;

    // ─── Env-var fallback ────────────────────────────────────────────────────

    @Value("${mcp.vault.fallback-ssh-user:mcpagent}")
    private String fallbackUser;

    @Value("${mcp.vault.fallback-ssh-password:}")
    private String fallbackPassword;

    @Value("${mcp.vault.fallback-ssh-key-path:}")
    private String fallbackKeyPath;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieve credentials for the given hostname.
     *
     * <p>When {@code vault.enabled=true} the request goes to Vault first;
     * if the lookup fails it falls through to the env-var fallback.
     * When {@code vault.enabled=false} only the fallback is used.</p>
     *
     * @param hostname  target server hostname (e.g. {@code app-server-01})
     * @param os        {@code "linux"} or {@code "windows"}
     * @return populated {@link ServerCredentials} — never null
     */
    public ServerCredentials getCredentials(String hostname, String os) {
        if (vaultEnabled) {
            try {
                ServerCredentials creds = fetchFromVault(hostname, os);
                log.info("[Vault] Loaded credentials for host={} from Vault path={}", hostname, creds.getVaultPath());
                return creds;
            } catch (Exception e) {
                log.warn("[Vault] Lookup failed for host={}: {}. Falling back to env-vars.", hostname, e.getMessage());
            }
        }
        return buildFallbackCredentials(hostname, os);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Vault HTTP lookup  (retried up to 3 times)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Executes the Vault KV v2 GET and deserialises the response.
     * Retried automatically by Spring Retry on IOException.
     */
    @Retryable(retryFor = IOException.class, maxAttempts = 3,
               backoff = @Backoff(delay = 1000, multiplier = 2))
    private ServerCredentials fetchFromVault(String hostname, String os) throws IOException {
        // KV v2 path: /v1/<mount>/data/<prefix>/<hostname>
        String path = vaultUrl + "/v1/" + vaultMount + "/data/" + serversPrefix + "/" + hostname;
        log.debug("[Vault] GET {}", path);

        HttpURLConnection conn = (HttpURLConnection) new URL(path).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("X-Vault-Token", vaultToken);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(5_000);

        int status = conn.getResponseCode();
        if (status == 404) {
            throw new IOException("Secret not found in Vault at path: " + path);
        }
        if (status != 200) {
            throw new IOException("Vault returned HTTP " + status + " for path: " + path);
        }

        byte[] body = conn.getInputStream().readAllBytes();
        JsonNode root   = objectMapper.readTree(body);
        JsonNode data   = root.path("data").path("data"); // KV v2 wraps data twice

        String vaultPath = vaultMount + "/data/" + serversPrefix + "/" + hostname;

        return ServerCredentials.builder()
                .host(hostname)
                .os(os != null ? os : nodeText(data, "os", "linux"))
                .sshPort(nodeInt(data, "ssh_port", 22))
                .username(nodeText(data, "username", fallbackUser))
                .password(nodeText(data, "password", null))
                .privateKeyPem(nodeText(data, "private_key", null))
                .privateKeyPassphrase(nodeText(data, "private_key_passphrase", null))
                .sudoPassword(nodeText(data, "sudo_password", null))
                .vaultPath(vaultPath)
                .credentialSource("vault:" + vaultPath)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Env-var fallback
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build credentials from environment variables or application.yml fallback values.
     * Used in dev/test when Vault is not available.
     */
    private ServerCredentials buildFallbackCredentials(String hostname, String os) {
        String keyPem = null;

        // Try to read PEM from disk path if configured
        if (fallbackKeyPath != null && !fallbackKeyPath.isBlank()) {
            try {
                keyPem = Files.readString(Paths.get(fallbackKeyPath));
                log.debug("[Vault-fallback] Loaded SSH key from: {}", fallbackKeyPath);
            } catch (IOException e) {
                log.warn("[Vault-fallback] Could not read SSH key from '{}': {}", fallbackKeyPath, e.getMessage());
            }
        }

        String resolvedPassword = (fallbackPassword != null && !fallbackPassword.isBlank())
                ? fallbackPassword : null;

        log.info("[Vault-fallback] Using env-var credentials for host={} user={} auth={}",
                hostname, fallbackUser, keyPem != null ? "key" : "password");

        return ServerCredentials.builder()
                .host(hostname)
                .os(os != null ? os : "linux")
                .sshPort(22)
                .username(fallbackUser)
                .password(resolvedPassword)
                .privateKeyPem(keyPem)
                .credentialSource("env-fallback")
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String nodeText(JsonNode node, String field, String defaultVal) {
        JsonNode n = node.get(field);
        return (n != null && !n.isNull() && !n.asText().isBlank()) ? n.asText() : defaultVal;
    }

    private int nodeInt(JsonNode node, String field, int defaultVal) {
        JsonNode n = node.get(field);
        return (n != null && n.isNumber()) ? n.asInt() : defaultVal;
    }
}
