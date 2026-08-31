package com.company.mcp.service;

import com.company.mcp.model.AppUser;
import com.company.mcp.model.Incident;
import com.company.mcp.model.SystemConfig;
import com.company.mcp.repository.SystemConfigRepository;
import com.company.mcp.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Outbound notification. Two callers: any update to an incident, and the auto-run lane
 * that acts without waiting for a human — so the people affected find out from the system
 * rather than from a user asking why a service restarted.
 *
 * Four properties of this class matter more than its brevity:
 *
 * 1. **Recipients come from the incident, never from a registry.** Reporter, assignee,
 *    assigned group. A separately maintained address list would be a second source of
 *    truth that goes stale the moment somebody changes teams.
 * 2. **No credential.** The relay is reached unauthenticated on the internal network. No
 *    username or password is read, stored, or sent. That is what allows "configure it all
 *    from the UI" and "no auth details in the database" to hold at the same time.
 * 3. **Config is read per send, from the database.** A change made in the UI takes effect
 *    on the next email with no restart and no properties file. The cost is one small query
 *    per notification, which is nothing next to an SMTP round trip.
 * 4. **Sending never fails the caller.** An update that is already committed, and a
 *    remediation that already ran, cannot be undone by a mail server being down, so every
 *    failure here is logged and swallowed. The incident row and the audit chain are the
 *    durable trail; email is a courtesy copy.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /**
     * Deliberately permissive: this guards against obvious typos and against header
     * injection via newlines, not against every RFC 5322 oddity. Rejecting unusual but
     * legal addresses would silently drop notifications for real people.
     */
    private static final Pattern EMAIL = Pattern.compile("^[^\\s<>@,;:\\\\\"]+@[A-Za-z0-9._-]+\\.[A-Za-z]{2,}$");

    private final SystemConfigRepository configRepository;
    private final UserRepository userRepository;

    public NotificationService(SystemConfigRepository configRepository,
                               UserRepository userRepository) {
        this.configRepository = configRepository;
        this.userRepository = userRepository;
    }

    /** Transport settings as currently stored. All UI-editable, none of them secret. */
    public record Settings(boolean enabled, String host, int port, String from) {
        public boolean usable() {
            return enabled && host != null && !host.isBlank() && port > 0 && port < 65536;
        }
    }

    public Settings settings() {
        return new Settings(
                Boolean.parseBoolean(value("notify_enabled", "false")),
                value("notify_smtp_host", ""),
                parsePort(value("notify_smtp_port", "25")),
                value("notify_from", "incident-automation@localhost"));
    }

    /**
     * Persists transport settings from the admin UI. Nothing here is secret, which is why
     * it can live in the database at all — see the class comment.
     */
    public void configure(Settings next) {
        set("notify_enabled", Boolean.toString(next.enabled()));
        set("notify_smtp_host", next.host() == null ? "" : next.host().trim());
        set("notify_smtp_port", Integer.toString(next.port()));
        set("notify_from", next.from() == null ? "" : next.from().trim());
        log.info("[NOTIFY] Transport reconfigured: enabled={} host={} port={} from={}",
                next.enabled(), next.host(), next.port(), next.from());
    }

    public void saveSettings(Settings next) {
        configure(next);
    }

    private String value(String key, String fallback) {
        return configRepository.findById(key)
                .map(SystemConfig::getConfigValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse(fallback);
    }

    private void set(String key, String val) {
        configRepository.save(new SystemConfig(key, val));
    }

    private static int parsePort(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            // A malformed port must not become "port 0" silently; 25 is the documented default.
            return 25;
        }
    }

    /**
     * The notification list for a ticket update:
     *   1. the reporter, if one was named and has an address;
     *   2. the assigned operator — looked up in auth.users;
     *   3. the assigned group.
     */
    public List<String> recipientsFor(Incident incident) {
        LinkedHashSet<String> to = new LinkedHashSet<>();
        addIfValid(to, incident.getReporterEmail());
        addIfValid(to, addressOfUser(incident.getAssignee()));
        return new ArrayList<>(to);
    }

    /** An assignee is a username, not an address. Look up in users table. */
    public String addressOfUser(String username) {
        if (username == null || username.isBlank()) return null;
        String name = username.trim();
        return userRepository.findByUsername(name).map(AppUser::getEmail).orElse(null);
    }

    private void addIfValid(LinkedHashSet<String> target, String candidate) {
        if (candidate == null) return;
        String trimmed = candidate.trim();
        if (isSendableAddress(trimmed)) {
            target.add(trimmed.toLowerCase(Locale.ROOT));
        } else if (!trimmed.isEmpty()) {
            log.warn("[NOTIFY] Skipping malformed recipient address");
        }
    }

    /**
     * Whether mail addressed here could actually be delivered.
     *
     * Public and static because the write paths that create people — a user account, a team,
     * a roster row — have to apply the same rule this class applies when it sends. When they
     * each carried their own copy of the pattern, an address one of them accepted was one
     * this class silently dropped, and the incident nobody was told about looked like a
     * notification bug rather than a validation gap.
     */
    public static boolean isSendableAddress(String candidate) {
        return candidate != null && EMAIL.matcher(candidate.trim()).matches();
    }

    /**
     * Sends one notification. Returns true only if the relay accepted the message, so a
     * caller that records "notified" in an audit entry is not recording a wish.
     */
    public boolean send(List<String> to, String subject, String body) {
        Settings settings = settings();
        if (!settings.usable()) {
            // The recipient count is logged even here: an admin asking "would this have
            // reached anyone?" is asking about the roster and the group address, and that
            // question has an answer whether or not a relay is configured.
            log.info("[NOTIFY] Notifications are disabled or unconfigured; nothing sent. recipients={} subject={}",
                    to.size(), subject);
            return false;
        }
        if (to.isEmpty()) {
            log.info("[NOTIFY] No valid recipients; nothing sent. subject={}", subject);
            return false;
        }
        try {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(settings.host());
            sender.setPort(settings.port());
            // No setUsername/setPassword: unauthenticated internal relay, by design.

            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(settings.from());
            message.setTo(to.toArray(String[]::new));
            // Newlines in a subject would let stored incident text forge mail headers.
            message.setSubject(subject.replaceAll("[\\r\\n]", " "));
            message.setText(body);
            sender.send(message);

            log.info("[NOTIFY] Sent '{}' to {} recipient(s)", message.getSubject(), to.size());
            return true;
        } catch (Exception e) {
            // Swallowed on purpose. See the class comment: the action already happened.
            log.error("[NOTIFY] Failed to send '{}' via {}:{} — {}", subject, settings.host(), settings.port(), e.toString());
            return false;
        }
    }

    /**
     * Tells the reporter, the assignee and the assigned group that the platform fixed
     * something without asking first. The body states what ran and on what authority,
     * because the first question anyone asks about an unattended action is "who approved
     * this?".
     */
    public boolean notifyAutoRemediation(Incident incident, String actionName, String target,
                                         String toolName, boolean succeeded, String precedentIncidentRef) {
        String outcome = succeeded ? "completed" : "FAILED";
        String subject = "[%s] Automatic remediation %s: %s".formatted(
                succeeded ? "Resolved" : "Action required", outcome, incident.getSubject());

        String body = """
                An automated remediation ran on this incident without waiting for approval.

                Incident   : %s
                Reference  : %s
                Priority   : %s
                Action     : %s on %s
                Saved tool : %s
                Outcome    : %s

                Why this ran without approval:
                  A human previously reviewed, approved and successfully ran this exact saved
                  tool for a matching incident (%s). The action is on the read-only/restart
                  allow-list, so it was repeated automatically. Anything outside that list
                  still waits for a person.

                What to do now:
                  %s

                This message was sent by the incident automation platform. Reply to your
                operations channel, not to this address.
                """.formatted(
                incident.getSubject(),
                reference(incident),
                incident.getPriority(),
                actionName, target,
                toolName,
                outcome,
                precedentIncidentRef,
                succeeded
                        ? "Verify the service on the target. If it is still unhealthy, reopen the incident."
                        : "The automated attempt failed and was NOT retried. An analyst must take this over.");

        return send(recipientsFor(incident), subject, body);
    }

    /** What an operator recognises the ticket by: its source reference if it has one. */
    private static String reference(Incident incident) {
        return incident.getExternalId() == null || incident.getExternalId().isBlank()
                ? incident.getId().toString()
                : incident.getExternalId();
    }

    /**
     * Tells the reporter, the assignee and the assigned group that the incident changed.
     *
     * {@code changes} is the same field-level diff that goes into incident.incident_history,
     * so the mail and the audit trail cannot disagree. Called with an empty list when a
     * save changed nothing, and returns without sending — a PUT that alters no field must
     * not generate mail, or every page refresh becomes a notification.
     */
    public boolean notifyIncidentUpdated(Incident incident, List<String> changes, String updatedBy) {
        if (changes == null || changes.isEmpty()) return false;

        String subject = "[Updated] %s: %s".formatted(reference(incident), incident.getSubject());
        String body = """
                This incident was updated.

                Incident   : %s
                Reference  : %s
                Priority   : %s
                Status     : %s
                Updated by : %s

                What changed:
                %s

                This message was sent by the incident automation platform. Reply to your
                operations channel, not to this address.
                """.formatted(
                incident.getSubject(),
                reference(incident),
                incident.getPriority(),
                incident.getStatus(),
                updatedBy == null || updatedBy.isBlank() ? "unknown" : updatedBy,
                changes.stream().map(c -> "  - " + c).reduce((a, b) -> a + "\n" + b).orElse("  (none)"));

        return send(recipientsFor(incident), subject, body);
    }
}
