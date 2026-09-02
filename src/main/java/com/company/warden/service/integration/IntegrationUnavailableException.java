package com.company.warden.service.integration;

/**
 * A configured provider could not be reached or refused the request.
 *
 * <p>Thrown rather than swallowed so a sync cannot record "0 incidents imported, SUCCESS" when the
 * truth is "we never got an answer". The vendor's own message stays on the cause, which is logged;
 * it is never put in an API response.
 */
public class IntegrationUnavailableException extends RuntimeException {
    private final String provider;

    public IntegrationUnavailableException(String provider, Throwable cause) {
        super(provider + " is unavailable", cause);
        this.provider = provider;
    }

    public String provider() { return provider; }
}
