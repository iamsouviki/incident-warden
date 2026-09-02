package com.company.warden.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies an OIDC provider ID token (Okta, Azure AD, Google, Keycloak) against
 * the provider's published JWKS before this application will trust any identity
 * it asserts.
 *
 * Why this exists: /api/auth/sso previously minted a first-party session from an
 * unauthenticated request body, so anyone who could reach the endpoint could name
 * any email and receive a valid token. Identity now comes only from a
 * signature this service checked.
 *
 * Fail-closed: if SSO is not fully configured, {@link #enabled()} is false and the
 * endpoint refuses the exchange rather than falling back to trusting the body.
 */
@Service
public class OidcTokenValidator {
    private static final Logger log = LoggerFactory.getLogger(OidcTokenValidator.class);

    private final boolean ssoEnabled;
    private final String jwkSetUri;
    private final String issuer;
    private final String audience;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    // ponytail: keys are cached until a token arrives with an unknown kid, which is
    // also exactly when a provider has rotated. No TTL sweeper needed.
    private final Map<String, PublicKey> keyCache = new ConcurrentHashMap<>();

    public OidcTokenValidator(ObjectMapper json,
                              @Value("${mcp.sso.enabled:false}") boolean ssoEnabled,
                              @Value("${mcp.sso.jwk-set-uri:}") String jwkSetUri,
                              @Value("${mcp.sso.issuer:}") String issuer,
                              @Value("${mcp.sso.audience:}") String audience) {
        this.json = json;
        this.ssoEnabled = ssoEnabled;
        this.jwkSetUri = jwkSetUri == null ? "" : jwkSetUri.trim();
        this.issuer = issuer == null ? "" : issuer.trim();
        this.audience = audience == null ? "" : audience.trim();
    }

    public boolean enabled() {
        return ssoEnabled && !jwkSetUri.isBlank() && !issuer.isBlank() && !audience.isBlank();
    }

    /**
     * @return the verified claims of the provider ID token.
     * @throws IllegalStateException when SSO is unconfigured or the token fails any check.
     */
    public Claims verify(String idToken) {
        if (!enabled()) throw new IllegalStateException("SSO is not configured on this deployment");
        if (idToken == null || idToken.isBlank()) throw new IllegalStateException("idToken is required");

        String kid = keyIdOf(idToken);
        PublicKey key = keyCache.get(kid);
        if (key == null) {
            refreshKeys();
            key = keyCache.get(kid);
        }
        if (key == null) throw new IllegalStateException("Provider signing key is unknown");

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .requireAudience(audience)
                    .build()
                    .parseSignedClaims(idToken)
                    .getPayload();
            if (claims.getSubject() == null || claims.getSubject().isBlank()) {
                throw new IllegalStateException("Provider token has no subject");
            }
            return claims;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Provider token rejected: " + e.getClass().getSimpleName());
        }
    }

    private String keyIdOf(String idToken) {
        try {
            String headerJson = new String(Base64.getUrlDecoder().decode(idToken.split("\\.")[0]), StandardCharsets.UTF_8);
            String kid = json.readTree(headerJson).path("kid").asText("");
            if (kid.isBlank()) throw new IllegalStateException("Provider token has no kid header");
            return kid;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Provider token header is not readable");
        }
    }

    private void refreshKeys() {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(jwkSetUri)).timeout(Duration.ofSeconds(5)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("JWKS endpoint returned HTTP " + response.statusCode());
            }
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            for (JsonNode jwk : json.readTree(response.body()).path("keys")) {
                if (!"RSA".equals(jwk.path("kty").asText())) continue;  // HMAC/EC provider keys are not accepted
                String kid = jwk.path("kid").asText("");
                if (kid.isBlank()) continue;
                BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("n").asText()));
                BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("e").asText()));
                keyCache.put(kid, rsa.generatePublic(new RSAPublicKeySpec(modulus, exponent)));
            }
        } catch (Exception e) {
            log.warn("[SSO] Could not refresh provider JWKS from {}: {}", jwkSetUri, e.getMessage());
            throw new IllegalStateException("Provider signing keys are unavailable");
        }
    }
}
