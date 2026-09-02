package com.company.mcp.service;

import com.company.mcp.model.Incident;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers the questions the executor agent cannot answer for itself: which machine, how to
 * reach it, and what operating system is on it.
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

    /**
     * A store written into the ticket: "store 0042", "Store #42", "store-0042-pos-01".
     *
     * Digits only, up to six, because the number is compared against other incidents'
     * store numbers to decide whether a tool has already been proven here. A loose match
     * that captured "store" plus the next word would make two unrelated stores compare
     * equal, which is an autonomy decision made on a typo.
     */
    private static final Pattern STORE = Pattern.compile(
            "\\bstore\\s*(?:number|no\\.?|#)?\\s*[:=#-]?\\s*(\\d{1,6})\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Host shapes an admin authored on the Skills page, tried after the built-in patterns.
     *
     * Additive on purpose. The estate this was written against uses store-0042-pos-01 and
     * pos01.store42.local, but a workspace whose tills are named 0042-TILL-03 has no label
     * word and no second dot, so neither built-in pattern can see it. An authored row makes
     * that host extractable without a redeploy — and because the built-ins run first, a bad
     * row can only fail to add a host, never stop one being found.
     *
     * Every candidate from an authored pattern still goes through {@link #usable} and
     * {@link #HOST}, so the trust boundary is unchanged: this widens what is looked for, not
     * what is accepted.
     *
     * ponytail: static and process-global, published once at boot by {@code SkillService}
     * and refreshed when an extraction skill is saved. This class is a static utility with
     * ~15 call sites, several with no request context (intake, external sync). The ceiling is
     * one estate's host conventions. Make this an injected bean if a second estate ever needs
     * different ones.
     */
    private static volatile List<Pattern> authoredHostPatterns = List.of();

    private IncidentTarget() {}

    /** Replaces the authored patterns. Called by {@code SkillService}; a copy is stored. */
    public static void authoredHostPatterns(List<Pattern> patterns) {
        authoredHostPatterns = patterns == null ? List.of() : List.copyOf(patterns);
    }

    /**
     * The host named in the ticket text, or "" when it names none.
     *
     * The same extractor {@link #resolve} uses, exposed so the UI can offer what the ticket
     * already says instead of asking a person to retype it. One extraction path on purpose:
     * a second copy of these patterns in the frontend would drift from the one that actually
     * decides where a script runs, and the operator would be shown a host the planner will
     * not use.
     *
     * Read-only — a value from here is a suggestion for a human to confirm, never an answer.
     * {@link #resolve} still prefers the typed field over this for anything dispatched.
     */
    public static String hostInText(String subject, String description) {
        return extract(trim(subject) + " " + trim(description)).toLowerCase(Locale.ROOT);
    }

    /** The store number written into the ticket text, or "" when none is. */
    public static String storeInText(String subject, String description) {
        Matcher matcher = STORE.matcher(trim(subject) + " " + trim(description));
        return matcher.find() ? matcher.group(1) : "";
    }

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

        String found = hostInText(incident.getSubject(), incident.getDescription());
        return found.isBlank()
                ? new Target("", "NONE", "TARGET_HOST_UNKNOWN")
                : new Target(found, "DESCRIPTION", "");
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

    /**
     * Which operating system the script is going to land on.
     *
     * This used to be a segment of the SOP's action key, which meant whoever authored the
     * procedure decided the OS of every machine it would ever be applied to. A Windows till
     * matched to SOP-TOMCAT-01 was handed {@code systemctl restart 'tomcat'} — a valid,
     * correctly guarded, approved script for the wrong operating system.
     *
     * Resolved per incident instead, in descending order of authority:
     *
     *   OPERATOR_DECLARED an operator picked the OS on the incident. Top of the ladder for the
     *                     same reason a typed host beats one read out of prose: it is a
     *                     person's answer to this exact question, not an inference about it.
     *                     It is also the only override available when detection is wrong —
     *                     without it, correcting a mis-detected till means editing an SOP
     *                     that every other store shares.
     *   OPERATOR_OVERRODE_HOST
     *                     the operator declared one OS and the probe reported a different
     *                     one. The operator still wins, but the disagreement is carried in
     *                     the source so the reviewer sees it on the plan they are approving.
     *                     Silently discarding the machine's own answer is the one thing this
     *                     ladder must not do.
     *   HOST_REPORTED     the executor agent named the platform in its probe reply. It runs
     *                     on or beside the machine, so this is measurement, not inference,
     *                     and it outranks anything a person guessed in advance.
     *   CONNECTION_METHOD the operator chose WINRM. That protocol exists only on Windows, so
     *                     choosing it is a statement about the host — and it is the lever an
     *                     operator already has on screen today.
     *   SOP_ACTION_KEY    the platform segment of the approved procedure. Still honoured,
     *                     because an operator authored it, but now the default rather than
     *                     the decision.
     *   DEFAULT           linux.
     *
     * SSH is deliberately not evidence of anything: it serves Linux, macOS and Windows alike.
     *
     * @param reportedPlatform what the probe reply said, or "" / null when nothing did
     * @param authoredHint     the action key's platform segment, from
     *                         {@link RemediationToolRegistry.ParsedAction#platformHint()}
     */
    public static Platform platform(Incident incident, String reportedPlatform, String authoredHint) {
        String reported = normalisePlatform(reportedPlatform);
        String declared = normalisePlatform(incident == null ? null : incident.getTargetPlatform());
        if (!declared.isBlank()) {
            boolean contradicted = !reported.isBlank() && !reported.equals(declared);
            return new Platform(declared, contradicted ? "OPERATOR_OVERRODE_HOST" : "OPERATOR_DECLARED");
        }
        if (!reported.isBlank()) return new Platform(reported, "HOST_REPORTED");
        if ("WINRM".equals(connection(incident))) return new Platform("windows", "CONNECTION_METHOD");
        String authored = normalisePlatform(authoredHint);
        if (!authored.isBlank()) return new Platform(authored, "SOP_ACTION_KEY");
        return new Platform("linux", "DEFAULT");
    }

    /**
     * A platform name this codebase has templates for, or "" for anything it does not
     * recognise.
     *
     * Blank rather than a best guess on purpose: an unrecognised token falls through to the
     * next rung of the ladder instead of overriding an authored default with a shrug. An
     * executor answering {@code platform=solaris} must not silently turn a Windows procedure
     * into bash.
     */
    private static String normalisePlatform(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (v.startsWith("win")) return "windows";
        if (v.startsWith("darwin") || v.startsWith("mac") || v.startsWith("osx")) return "darwin";
        if (v.startsWith("linux") || v.startsWith("ubuntu") || v.startsWith("debian")
                || v.startsWith("rhel") || v.startsWith("centos") || v.startsWith("suse")) return "linux";
        return "";
    }

    /**
     * @param name   windows | linux | darwin
     * @param source OPERATOR_DECLARED | OPERATOR_OVERRODE_HOST | HOST_REPORTED |
     *               CONNECTION_METHOD | SOP_ACTION_KEY | DEFAULT — shown to the reviewer,
     *               because "the host told us", "a person told us" and "we assumed" are not
     *               the same claim about a script they are about to approve
     */
    public record Platform(String name, String source) {
        public boolean windows() { return "windows".equals(name); }

        /** The interpreter the executor is asked to run this script under. */
        public String language() { return windows() ? "powershell" : "bash"; }
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
        // Authored last: an admin's pattern adds host shapes the built-ins cannot see, and
        // must not be able to shadow the ones they can.
        for (Pattern pattern : authoredHostPatterns) {
            Matcher authored = pattern.matcher(text);
            while (authored.find()) {
                if (authored.groupCount() < 1) break;
                String candidate = strip(authored.group(1));
                if (usable(candidate)) return candidate;
            }
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
