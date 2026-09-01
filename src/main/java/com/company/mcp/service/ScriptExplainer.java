package com.company.mcp.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Says, in plain language, what a script does and how it does it.
 *
 * The reason this exists: a reviewer is shown twenty lines of bash and a button that runs it.
 * Anyone fluent in bash does not need this; the person approving a restart at 2am for a store
 * that is losing sales often is not, and "I could not read it but the badge was green" is the
 * failure mode a human-in-the-loop gate is supposed to prevent. An approval nobody understood
 * is a rubber stamp with an audit trail.
 *
 * Two halves, deliberately from two sources:
 *
 *   what — from the action key and the tool's own description. That is the intent somebody
 *          authorised.
 *   how  — read off the script text itself, line by line. That is what will actually run. If
 *          the two disagree, the reviewer sees the disagreement, which is the entire point of
 *          not deriving both from the same field.
 *
 * ponytail: a phrase table over the commands this platform's own generator emits, not a shell
 * parser. It is honest about its ceiling — an unrecognised line is reported verbatim as "runs:
 * <line>" rather than silently dropped, so the explanation can never claim a script is smaller
 * than it is. The guardrail scan, not this, is what decides whether a script is safe; this only
 * decides whether a human can read it. Replace with a real parser only if scripts start coming
 * from somewhere other than the generator and the SOP templates.
 */
public final class ScriptExplainer {

    private ScriptExplainer() {}

    /**
     * @param actionKey        the approved action key, or "" for an ungrounded script
     * @param toolDescription  the tool row's own description, or "" when the key names no tool
     * @param script           the exact text that will run
     * @param language         bash | powershell
     * @param target           the host it will run on
     */
    public static Explanation explain(String actionKey, String toolDescription, String script,
                                      String language, String target) {
        String host = blank(target) ? "the target host" : target.trim();
        String what = what(actionKey, toolDescription, script, host);
        return new Explanation(what, how(script, language), lineCount(script));
    }

    private static String what(String actionKey, String toolDescription, String script, String host) {
        if (blank(script)) {
            return "This plan carries no script. The tool runs directly against " + host + ".";
        }
        if (blank(actionKey)) {
            // The LLM_KNOWLEDGE path. Saying so here matters more than a tidy sentence: no
            // approved procedure stands behind this text, and the reviewer is the only gate.
            return "A one-off script for " + host + ", written for this incident with no approved "
                    + "procedure behind it. Read every line — nothing else has.";
        }
        String[] parts = actionKey.trim().split(":", -1);
        String tool = parts[0].toUpperCase(Locale.ROOT);
        String detail = switch (tool) {
            case "RESTART_SERVICE" -> parts.length > 1
                    ? "Stops and starts the '" + parts[1] + "' service on " + host
                            + ". Existing sessions on that service drop; nothing else on the host is touched."
                    : "Restarts one named service on " + host + ".";
            case "CHECK_URL" -> "Asks " + host + " for one web address and compares the status code it "
                    + "returns. Read-only: it changes nothing.";
            case "CLEAR_CACHE" -> parts.length > 1
                    ? "Empties the '" + parts[1] + "' cache on " + host
                            + ". The next few requests are slower while it refills; stored data is not deleted."
                    : "Flushes one cache tier on " + host + ".";
            case "RERUN_JOB" -> "Re-triggers one batch job on " + host
                    + ". If the job is not safe to run twice, that is the risk to weigh here.";
            default -> blank(toolDescription)
                    ? "Runs the " + tool + " action on " + host + "."
                    : toolDescription.trim() + " Target: " + host + ".";
        };
        return detail;
    }

    /**
     * The script's steps in order, one plain sentence each.
     *
     * Comments, blank lines and shell bookkeeping are dropped — they are noise to the person
     * this is written for. Everything else produces a line, recognised or not.
     */
    private static List<String> how(String script, String language) {
        List<String> steps = new ArrayList<>();
        if (blank(script)) return steps;
        boolean powershell = "powershell".equalsIgnoreCase(language);
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : script.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("<#") || line.startsWith("//")) continue;
            if (skippable(line, powershell)) continue;
            String step = describe(line);
            // A generated script often repeats the same status check either side of the action.
            // Saying it twice reads as two different things happening.
            if (seen.add(step)) steps.add(step);
            if (steps.size() >= 12) {
                steps.add("… plus further lines. The full script is shown above; read it before approving.");
                break;
            }
        }
        return steps;
    }

    /** Shell bookkeeping that changes nothing an operator would recognise as a step. */
    private static boolean skippable(String line, boolean powershell) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.startsWith("set -") || lower.startsWith("$erroractionpreference")) return true;
        if (lower.equals("fi") || lower.equals("done") || lower.equals("else") || lower.equals("}")
                || lower.equals("{") || lower.startsWith("then")) return true;
        if (lower.startsWith("exit ")) return true;
        return powershell && lower.startsWith("param(");
    }

    private static String describe(String line) {
        String lower = line.toLowerCase(Locale.ROOT);

        // Reading state. Named first because a well-formed remediation is mostly this.
        if (lower.contains("is-active") || lower.contains("systemctl status")
                || lower.contains("get-service") || lower.contains("launchctl print")) {
            return "Checks whether the service is currently running.";
        }
        if (lower.startsWith("journalctl") || lower.startsWith("tail ") || lower.contains("get-eventlog")
                || lower.contains("get-content")) {
            return "Reads the last lines of the log so the outcome is visible afterwards.";
        }
        // curl and redis-cli usually arrive wrapped in an assignment, so these match anywhere
        // in the line rather than at its start.
        if (lower.contains("curl ") || lower.startsWith("wget") || lower.contains("invoke-webrequest")
                || lower.contains("invoke-restmethod") || lower.contains("test-netconnection")) {
            return "Calls a web address and checks what it answers. Reads only.";
        }
        if (lower.contains("redis-cli") && lower.contains("ping")) {
            return "Checks the cache server is answering before touching it.";
        }
        if (lower.startsWith("ps ") || lower.startsWith("pgrep") || lower.contains("get-process")) {
            return "Looks up the process to confirm it is there.";
        }
        if (lower.startsWith("df ") || lower.startsWith("du ") || lower.contains("get-psdrive")) {
            return "Checks how much disk space is free.";
        }

        // Changing state. These are why the plan needs an approval at all.
        if (lower.contains("systemctl restart") || lower.contains("restart-service")
                || lower.contains("launchctl kickstart")
                || lower.contains("service ") && lower.contains("restart")) {
            return "Restarts the service. Anything connected to it is disconnected for a moment.";
        }
        if (lower.contains("systemctl stop") || lower.contains("stop-service")) {
            return "Stops the service. It stays down until something starts it again.";
        }
        if (lower.contains("systemctl start") || lower.contains("start-service")) {
            return "Starts the service.";
        }
        if (lower.contains("flushall") || lower.contains("flushdb") || lower.contains("clear-cache")) {
            return "Empties the cache. Nothing stored permanently is lost, but the next requests are slower.";
        }
        if (lower.startsWith("rm ") || lower.contains("remove-item") || lower.startsWith("del ")) {
            return "Deletes files. This is the line to read twice — deletion is not undone by a rollback.";
        }
        if (lower.contains("kill ") || lower.contains("stop-process")) {
            return "Forcibly ends a process. Work it had not finished is lost.";
        }
        if (lower.startsWith("mv ") || lower.startsWith("cp ") || lower.contains("copy-item")
                || lower.contains("move-item")) {
            return "Moves or copies files on the host.";
        }
        if (lower.startsWith("sleep") || lower.startsWith("start-sleep")) {
            return "Waits a moment before checking again.";
        }

        // The verification tail. Worth its own sentence: it is the difference between a script
        // that restarts something and one that restarts it and confirms it came back.
        if (lower.startsWith("test ") || (lower.startsWith("if ") && lower.contains("exit 1"))) {
            return "Fails the run if the check above did not come back as expected, so a "
                    + "half-finished fix is reported as a failure rather than a success.";
        }
        if (lower.startsWith("echo") || lower.startsWith("write-host") || lower.startsWith("write-output")) {
            return "Prints a progress line into the run log.";
        }

        // Unrecognised. Reported, never dropped: an explanation that hides a line is worse than
        // no explanation, because the reviewer stops reading the script itself.
        return "Runs: " + clip(line);
    }

    private static int lineCount(String script) {
        return blank(script) ? 0 : script.split("\\R", -1).length;
    }

    private static String clip(String value) {
        return value.length() <= 120 ? value : value.substring(0, 120) + "…";
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    /**
     * @param what  one sentence naming the effect, from the authorised action
     * @param how   the steps, read off the script that will actually run
     * @param lines the script's length, so "how" being shorter is visibly a summary
     */
    public record Explanation(String what, List<String> how, int lines) {}
}
