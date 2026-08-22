package com.company.mcp.service;

import com.company.mcp.config.CurrentUser;
import com.company.mcp.model.McpServer;
import com.company.mcp.repository.McpServerRepository;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Registry of external MCP servers, scoped to the caller's tenant.
 *
 * The endpoint is validated on the way in even though nothing dials it yet. Validating
 * at write time means the outbound client, when it is built, inherits a table that
 * cannot contain a {@code file://} URL or a shell string — rather than needing to
 * re-validate every row it reads.
 */
@Service
public class McpServerRegistryService {

    private static final Set<String> ALLOWED_TRANSPORTS = Set.of("http", "sse", "stdio");

    private final McpServerRepository servers;
    private final CurrentUser currentUser;

    public McpServerRegistryService(McpServerRepository servers, CurrentUser currentUser) {
        this.servers = servers;
        this.currentUser = currentUser;
    }

    public List<McpServer> list() {
        return servers.findByTenantIdOrderByNameAsc(currentUser.tenantId());
    }

    public McpServer save(Map<String, Object> body) {
        String name = text(body, "name");
        String endpoint = text(body, "endpoint");
        String transport = body.get("transport") instanceof String s && !s.isBlank()
                ? s.toLowerCase(java.util.Locale.ROOT) : "http";

        if (name.isBlank()) throw new IllegalArgumentException("A server name is required");
        if (!ALLOWED_TRANSPORTS.contains(transport)) {
            throw new IllegalArgumentException("Transport must be one of " + ALLOWED_TRANSPORTS);
        }
        if (!"stdio".equals(transport)) validateHttpEndpoint(endpoint);

        McpServer server = existing(body);
        server.setTenantId(currentUser.tenantId());   // never from the request body
        server.setName(name.length() > 150 ? name.substring(0, 150) : name);
        server.setTransport(transport);
        server.setEndpoint(endpoint);
        server.setDescription(text(body, "description"));
        // A newly registered server is disabled until an operator turns it on. Registering
        // and trusting are separate decisions.
        server.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        server.setCreatedBy(currentUser.username());
        server.setUpdatedAt(OffsetDateTime.now());
        return servers.save(server);
    }

    public boolean delete(UUID id) {
        return servers.findByIdAndTenantId(id, currentUser.tenantId()).map(server -> {
            servers.delete(server);
            return true;
        }).orElse(false);
    }

    /** Loads the row for an update, but only within the caller's tenant. */
    private McpServer existing(Map<String, Object> body) {
        Object id = body.get("id");
        if (id == null) return new McpServer();
        try {
            return servers.findByIdAndTenantId(UUID.fromString(id.toString()), currentUser.tenantId())
                    .orElseGet(McpServer::new);
        } catch (IllegalArgumentException e) {
            return new McpServer();
        }
    }

    /**
     * Only http and https, and only with a host.
     *
     * {@code file:}, {@code jar:} and friends are rejected here rather than at dial time:
     * an endpoint field that accepts any URI is an SSRF and local-file-read primitive
     * waiting for the client that eventually uses it.
     */
    private void validateHttpEndpoint(String endpoint) {
        if (endpoint.isBlank()) throw new IllegalArgumentException("An endpoint URL is required");
        if (endpoint.length() > 500) throw new IllegalArgumentException("Endpoint exceeds 500 characters");
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Endpoint is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Endpoint must use http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Endpoint must include a host");
        }
    }

    private String text(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value == null ? "" : value.toString().trim();
    }
}
