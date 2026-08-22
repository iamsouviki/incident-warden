package com.company.mcp.service;

import com.company.mcp.model.Incident;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The extractor decides which machine an approved script is sent to, so the failure that
 * matters is not "found nothing" — it is "confidently found the wrong token". Every test
 * below that expects an unknown host is protecting a case where a plausible-looking word
 * would otherwise have been dispatched as a hostname.
 */
class IncidentTargetTest {

    @Test
    void aTypedFieldBeatsTheTicketText() {
        Incident incident = incident("Till down", "server: guessed-from-prose-01");
        incident.setTargetHost("STORE-0042-POS-01");

        IncidentTarget.Target target = IncidentTarget.resolve(incident);

        assertEquals("store-0042-pos-01", target.host());   // lower-cased for comparison
        assertEquals("FIELD", target.source());
        assertTrue(target.known());
    }

    /** A typed answer is still input at a trust boundary: shape-checked like any other. */
    @Test
    void aTypedFieldThatIsNotAHostnameIsRefusedRatherThanDispatched() {
        Incident incident = incident("Till down", "");
        incident.setTargetHost("pos-01; rm -rf /");

        IncidentTarget.Target target = IncidentTarget.resolve(incident);

        assertFalse(target.known());
        assertTrue(target.reason().startsWith("TARGET_HOST_INVALID"));
        assertTrue(target.prompt().contains("not a valid hostname"));
    }

    @Test
    void aLabelledHostIsReadOutOfTheDescription() {
        IncidentTarget.Target target = IncidentTarget.resolve(
                incident("IIS stopped", "Restart IIS on hostname store-0042-pos-01, the till is dead."));

        assertEquals("store-0042-pos-01", target.host());
        assertEquals("DESCRIPTION", target.source());
    }

    @Test
    void aBareFullyQualifiedNameIsRecognisedWithoutALabel() {
        assertEquals("pos01.store42.local", IncidentTarget.resolve(
                incident("Till offline", "pos01.store42.local stopped answering at 09:14")).host());
    }

    /** "server" followed by an English word is prose, and prose is not a machine. */
    @Test
    void anOrdinaryWordAfterALabelIsNotTreatedAsAHost() {
        IncidentTarget.Target target = IncidentTarget.resolve(
                incident("Printer stuck", "Please restart the server urgently, nothing prints."));

        assertFalse(target.known());
        assertEquals("TARGET_HOST_UNKNOWN", target.reason());
        assertTrue(target.prompt().contains("Enter the server"));
    }

    /** One dot is a filename far more often than a host, so it asks instead of guessing. */
    @Test
    void aSingleDottedFilenameIsNeverPromotedToAHost() {
        assertFalse(IncidentTarget.resolve(
                incident("App down", "web.config is corrupt and node.js will not start")).known());
    }

    @Test
    void onlyMethodsTheExecutorUnderstandsSurvive() {
        Incident incident = incident("Till down", "");
        incident.setConnectionMethod(" winrm ");
        assertEquals("WINRM", IncidentTarget.connection(incident));

        incident.setConnectionMethod("telnet");
        assertEquals("", IncidentTarget.connection(incident));

        incident.setConnectionMethod(null);
        assertEquals("", IncidentTarget.connection(incident));   // "" = try the default path first
    }

    @Test
    void theStoreKeyIsTrimmedAndNeverNull() {
        Incident incident = incident("Till down", "");
        incident.setStoreNumber("  0042 ");
        assertEquals("0042", IncidentTarget.store(incident));

        incident.setStoreNumber(null);
        assertEquals("", IncidentTarget.store(incident));
    }

    /** Labels fall back to the ticket number; only dispatch requires a real host. */
    @Test
    void labellingFallsBackToTheTicketNumber() {
        assertEquals("FS-1001", IncidentTarget.hostOrTicket(incident("Printer stuck", "Nothing prints")));
    }

    private Incident incident(String subject, String description) {
        return Incident.builder().id(UUID.randomUUID()).tenantId("tenant-a")
                .subject(subject).description(description)
                .priority("P3").status("New").externalId("FS-1001").build();
    }
}
