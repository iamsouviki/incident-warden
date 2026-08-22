package com.company.mcp.service;

import com.company.mcp.model.Incident;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers the one question the executor agent cannot answer for itself: which machine.
 *
 * Before this class the "target" sent with every approved script was the incident's own
 * ticket number. Every lane computed it the same wrong way, in three places, each with a
 * comment promising a real mapping later. This is that mapping, and it is deliberately
 * dull: read the field an operator filled in, or read a host out of the ticket text, or
 * admit that nobody has said which machine and stop.
 *
 * The order matters. A typed field always beats text extraction, because the field is a
 * person's answer to this exact question and the text is a guess about somebody's prose.
 *
 * ponytail: pattern matching, not an NER model or a CMDB lookup. Two things make that
 * safe rather than merely cheap — an extracted host is shown to a reviewer inside the
 * hashed plan before anything runs, and it is probed for reachability first, so a
 * mis-read token becomes a blocked plan with a visible reason rather than a command on
 * the wrong box. Replace the extractor with a CMDB query when one exists; keep both of
 * those guarantees when you do.
 */
public final class IncidentTarget {

    /**
     * The shape of a value we are willing to hand to the executor: a DNS name, a short
     * hostname, or an IP literal. No whitespace, no quotes, no shell metacharacters, no
     * traversal. Applied to typed input as well as extracted text — this is the trust
     * boundary between "something a user typed" and "the machine a script will run on".
     */
    private static final Pattern HOST = Pattern.compile(
            "^[A-Za-z0-9](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9])?)*$");

    /**
     * A host named after a label: "server: pos-01", "hostname=store-0042-pos-01",
     * "restart iis on host WIN-STORE-0042".
     *
     * Longest alternatives first, so "hostname" is not consumed as "host".
     */
    private static final Pattern LABELLED = Pattern.compile(
            "(?:hostname|servername|server|host|node|machine|device)\\s*(?:name)?\\s*[:=]?\\s*"
                    + "([A-Za-z0-9][A-Za-z0-9._-]{2,80})",
            Pattern.CASE_INSENSITIVE);

    /**
     * A bare fully-qualified name mentioned without a label: {@code pos01.store42.local}.
     *
     * Two dots minimum. One dot would collect "node.js", "web.config" and "app.log" out of
     * ordinary incident prose, and a filename silently promoted to a hostname is worse
     * than asking. A single-dot host is exactly the case where a person should be asked.
     */
    private static final Pattern BARE_FQDN = Pattern.compile(
            "\\b([A-Za-z0-9][A-Za-z0-9_-]*(?:\\.[A-Za-z0-9][A-Za-z0-9_-]*)+\\.[A-Za-z]{2,24})\\b");

    /** Connection methods the executor agent is expected to understand. */
    private static final Set<String> METHODS = Set.of("SSH", "WINRM", "AGENT");

    private IncidentTarget() {}

    /**
     * The machine this incident is about, or a refusal that names why.
     *
     * Never throws and never guesses. Callers that mutate a system must check
     * {@link Target#known()} and stop when it is false; the reason is written for an
     * operator to read in the UI.
     */
    public static Target resolve(Incident incident) {
        if (incident == null) return new Target("", "NONE", "TARGET_HOST_UNKNOWN");

        String typed = trim(incident.getTargetHost());
        if (!typed.isBlank()) {
            // An operator answered the question. Validated all the same: the answer still
            // has to be a hostname before it becomes part of a dispatched job.
            return HOST.matcher(typed).matches() && !typed.contains("..")
                    ? new Target(typed.toLowerCase(Locale.ROOT), "FIELD", "")
                    : new Target("", "NONE", "TARGET_HOST_INVALID:" + clip(typed));
        }

        String found = extract(trim(incident.getSubject()) + " " + trim(incident.getDescription()));
        return found.isBlank()
                ? new Target("", "NONE", "TARGET_HOST_UNKNOWN")
                : new Target(found.toLowerCase(Locale.ROOT), "DESCRIPTION", "");
    }

    /**
     * The resolved host, falling back to the ticket number when no machine is named.
     *
     * For labelling only — guardrail evaluation, plan display, audit lines — where a blank
     * would read as "no target" rather than "not established yet". Anything that dispatches
     * work must use {@link #resolve} and refuse when the host is unknown.
     */
    public static String hostOrTicket(Incident incident) {
        Target target = resolve(incident);
        if (target.known()) return target.host();
        if (incident == null) return "incident-unknown";
        String external = trim(incident.getExternalId());
        return external.isBlank() ? "incident-" + incident.getId() : external;
    }

    /**
     * How the executor should connect, or "" meaning "use your own default path".
     *
     * Empty is the first thing tried on every incident: reach the host with the credentials
     * the executor already holds, before asking a human for anything. Only when that fails
     * does an operator name a method, and only the method — never a secret.
     */
    public static String connection(Incident incident) {
        String value = incident == null ? "" : trim(incident.getConnectionMethod()).toUpperCase(Locale.ROOT);
        return METHODS.contains(value) ? value : "";
    }

    /** The store number as a comparison key: trimmed, never null. */
    public static String store(Incident incident) {
        return incident == null ? "" : trim(incident.getStoreNumber());
    }

    private static String extract(String text) {
        Matcher labelled = LABELLED.matcher(text);
        while (labelled.find()) {
            String candidate = strip(labelled.group(1));
            if (usable(candidate)) return candidate;
        }
        Matcher bare = BARE_FQDN.matcher(text);
        while (bare.find()) {
            String candidate = strip(bare.group(1));
            if (usable(candidate)) return candidate;
        }
        return "";
    }

    /**
     * Whether an extracted token is host-like enough to act on.
     *
     * The digit-or-dot-or-dash rule is what stops the labelled pattern from harvesting
     * ordinary prose: "restart the server please" offers "please", which is a valid
     * hostname by shape and obviously not one in fact. Real hosts in this estate are
     * store-0042-pos-01 and pos01.store42.local — they carry a number, a dot or a dash.
     * A ticket whose only clue is a bare English word gets the operator asked instead.
     */
    private static boolean usable(String candidate) {
        if (candidate.length() < 3 || candidate.length() > 253) return false;
        if (candidate.contains("..") || !HOST.matcher(candidate).matches()) return false;
        boolean hostLike = candidate.chars().anyMatch(c -> Character.isDigit(c) || c == '.' || c == '-');
        boolean allDigits = candidate.chars().allMatch(c -> Character.isDigit(c) || c == '.');
        return hostLike && !allDigits;
    }

    /** Trailing sentence punctuation is prose, not part of the name. */
    private static String strip(String value) {
        String result = value;
        while (!result.isEmpty() && ".,;:-_".indexOf(result.charAt(result.length() - 1)) >= 0) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }

    /** Rejected input is echoed back to the operator, so it is bounded and single-line. */
    private static String clip(String value) {
        String flat = value.replaceAll("\\s+", " ");
        return flat.length() <= 40 ? flat : flat.substring(0, 40) + "…";
    }

    /**
     * @param host   the machine, lower-cased, or "" when nobody has said which
     * @param source FIELD | DESCRIPTION | NONE — a reviewer is shown whether a person named
     *               this host or a pattern read it out of the ticket text
     * @param reason TARGET_HOST_UNKNOWN | TARGET_HOST_INVALID:… when the host is unusable
     */
    public record Target(String host, String source, String reason) {
        public boolean known() { return !host.isBlank(); }

        /** What the UI tells the operator to do about it. */
        public String prompt() {
            return reason.startsWith("TARGET_HOST_INVALID")
                    ? "The server name on this incident is not a valid hostname. Correct it before planning."
                    : "No server is named on this incident or in its description. Enter the server this "
                        + "affects, then create the plan again.";
        }
    }
}
