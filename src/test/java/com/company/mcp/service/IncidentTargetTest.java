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

    /**
     * The platform ladder, top rung down. Whoever authored the SOP action key was guessing
     * about machines they never saw; the machine that answered the probe was not.
     */
    @Test
    void whatTheHostReportedOutranksWhatTheSopAuthorAssumed() {
        IncidentTarget.Platform platform =
                IncidentTarget.platform(incident("Till down", ""), "Windows_NT", "linux");

        assertEquals("windows", platform.name());
        assertEquals("HOST_REPORTED", platform.source());
        assertEquals("powershell", platform.language());
    }

    /**
     * A person who picked the OS on the incident outranks the probe — it is the only override
     * available when detection is wrong — but the disagreement is carried in the source rather
     * than swallowed, because the reviewer is being asked to approve PowerShell for a host
     * that said "linux".
     */
    @Test
    void anOperatorsAnswerBeatsTheProbeAndTheDisagreementIsRecorded() {
        Incident declaredWindows = incident("Till down", "");
        declaredWindows.setTargetPlatform("windows");

        IncidentTarget.Platform contradicted = IncidentTarget.platform(declaredWindows, "linux", "linux");
        assertEquals("windows", contradicted.name());
        assertEquals("OPERATOR_OVERRODE_HOST", contradicted.source());
        assertEquals("powershell", contradicted.language());

        // Same declaration, and this time the machine agrees — an Ubuntu box answering a
        // declaration of "linux" is agreement, not a conflict to flag at a reviewer.
        Incident declaredLinux = incident("Till down", "");
        declaredLinux.setTargetPlatform("linux");
        assertEquals("OPERATOR_DECLARED", IncidentTarget.platform(declaredLinux, "Ubuntu 22.04", "windows").source());

        // And with nothing detected at all, the declaration still stands on its own.
        assertEquals("OPERATOR_DECLARED", IncidentTarget.platform(declaredLinux, "", "windows").source());
    }

    /**
     * A declaration this process cannot act on must not be able to hand a Windows till a bash
     * script: it is discarded and the rungs below it decide. Prefix matching is deliberately
     * forgiving about near-misses in the other direction — "windwos" is still Windows, because
     * the alternative is silently ignoring an operator who answered the question correctly and
     * typed it badly.
     */
    @Test
    void anUnrecognisedDeclarationIsDiscardedButATypoIsStillUnderstood() {
        Incident unknownOs = incident("Till down", "");
        unknownOs.setTargetPlatform("solaris");

        IncidentTarget.Platform fellThrough = IncidentTarget.platform(unknownOs, "Windows_NT", "linux");
        assertEquals("windows", fellThrough.name());
        assertEquals("HOST_REPORTED", fellThrough.source());

        Incident typo = incident("Till down", "");
        typo.setTargetPlatform("windwos");
        assertEquals("windows", IncidentTarget.platform(typo, "linux", "linux").name());
    }

    /**
     * WinRM only talks to Windows, so choosing it is an operator saying "Windows" in the one
     * place the UI already lets them. SSH deliberately implies nothing: it serves Linux,
     * macOS and Windows alike.
     */
    @Test
    void choosingWinRmIsItselfAnAnswerAndChoosingSshIsNot() {
        Incident onWinRm = incident("Till down", "");
        onWinRm.setConnectionMethod("winrm");
        IncidentTarget.Platform inferred = IncidentTarget.platform(onWinRm, "", "");
        assertEquals("windows", inferred.name());
        assertEquals("CONNECTION_METHOD", inferred.source());

        Incident onSsh = incident("Till down", "");
        onSsh.setConnectionMethod("ssh");
        assertEquals("SOP_ACTION_KEY", IncidentTarget.platform(onSsh, "", "darwin").source());
    }

    /**
     * An executor written before the platform field, or one that answers something this
     * process has never heard of, must fall through a rung rather than override the only
     * real signal available with a token nobody can act on.
     */
    @Test
    void anUnrecognisedReportFallsThroughInsteadOfWinning() {
        IncidentTarget.Platform platform =
                IncidentTarget.platform(incident("Till down", ""), "plan9", "windows");

        assertEquals("windows", platform.name());
        assertEquals("SOP_ACTION_KEY", platform.source());
    }

    /** No signal anywhere: bash, because that is what most targets are, and it is recorded as a guess. */
    @Test
    void withNoSignalAtAllTheDefaultIsLabelledAsOne() {
        IncidentTarget.Platform platform = IncidentTarget.platform(incident("Till down", ""), "", "");

        assertEquals("linux", platform.name());
        assertEquals("DEFAULT", platform.source());
        assertEquals("bash", platform.language());
        assertFalse(platform.windows());
    }

    /** Distro names are not platforms; they all run bash and systemctl. */
    @Test
    void distroNamesNormaliseToLinux() {
        assertEquals("linux", IncidentTarget.platform(incident("x", ""), "Ubuntu 22.04", "").name());
        assertEquals("darwin", IncidentTarget.platform(incident("x", ""), "Mac OS X", "").name());
    }

    /**
     * What the UI prefills has to be what the planner would resolve, or the operator
     * confirms one host and a script runs on another.
     */
    @Test
    void whatIsOfferedAsAPrefillIsWhatTheResolverWouldHaveFound() {
        Incident ticket = incident("Tomcat down at Store #0042", "hostname: POS01.store42.local is unreachable");

        assertEquals("pos01.store42.local", IncidentTarget.hostInText(ticket.getSubject(), ticket.getDescription()));
        assertEquals(IncidentTarget.resolve(ticket).host(),
                IncidentTarget.hostInText(ticket.getSubject(), ticket.getDescription()));
        assertEquals("0042", IncidentTarget.storeInText(ticket.getSubject(), ticket.getDescription()));
        // Read straight off the entity, which is the shape the UI actually receives.
        assertEquals("pos01.store42.local", ticket.getDetectedTargetHost());
        assertEquals("0042", ticket.getDetectedStoreNumber());
    }

    /** A ticket that names nothing prefills nothing — blank, never a guess. */
    @Test
    void aTicketThatNamesNoMachineOrStoreOffersNothing() {
        Incident ticket = incident("Printer is jammed", "Please send someone to look at it.");

        assertEquals("", ticket.getDetectedTargetHost());
        assertEquals("", ticket.getDetectedStoreNumber());
        // A store embedded in a hostname still counts; that is where it usually lives.
        assertEquals("0042", IncidentTarget.storeInText("restart iis on store-0042-pos-01", ""));
        assertEquals("", IncidentTarget.storeInText("the store is closed today", ""));
    }

    private Incident incident(String subject, String description) {
        return Incident.builder().id(UUID.randomUUID())
                .subject(subject).description(description)
                .priority("P3").status("New").externalId("FS-1001").build();
    }
}
