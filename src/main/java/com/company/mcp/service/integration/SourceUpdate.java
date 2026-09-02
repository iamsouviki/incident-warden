package com.company.mcp.service.integration;

/**
 * Outcome of writing back to the ticket the incident came from.
 *
 * <p>Three states, not a boolean: "the vendor is not configured" and "the vendor rejected the
 * write" are different facts, and reporting either one as success is how an operator ends up
 * believing a ticket was updated when it was not.
 */
public enum SourceUpdate {
    /** The originating system acknowledged the write. */
    UPDATED,
    /** The originating system was reachable-or-not but did not accept the write. */
    FAILED,
    /** No integration is configured for this incident's source; nothing was attempted. */
    NOT_CONFIGURED
}
