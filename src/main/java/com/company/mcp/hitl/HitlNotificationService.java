package com.company.mcp.hitl;

import com.company.mcp.model.HitlRequest;
import com.company.mcp.model.Incident;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * HitlNotificationService — spec §7 "Human-in-the-Loop Escalation".
 *
 * Sends notifications to the on-call team when a HITL request is created,
 * decided, or a SLA breach occurs.
 *
 * Supported channels (stubbed — wire real clients in each TODO block):
 *   • Slack   — webhook or Bot API
 *   • Email   — SMTP via Spring Mail
 *   • PagerDuty — Events v2 API
 *
 * All methods are fire-and-forget (exceptions are caught and logged so that
 * a notification failure never breaks the main processing pipeline).
 */
@Slf4j
@Service
public class HitlNotificationService {

    @Value("${mcp.notifications.slack.enabled:false}")
    private boolean slackEnabled;

    @Value("${mcp.notifications.slack.webhook-url:}")
    private String slackWebhookUrl;

    @Value("${mcp.notifications.email.enabled:false}")
    private boolean emailEnabled;

    @Value("${mcp.notifications.pagerduty.enabled:false}")
    private boolean pagerDutyEnabled;

    @Value("${mcp.notifications.pagerduty.routing-key:}")
    private String pagerDutyRoutingKey;

    // -------------------------------------------------------------------------
    // Public notification methods
    // -------------------------------------------------------------------------

    /**
     * Notify on-call team that a new HITL request requires attention.
     */
    public void notifyPendingApproval(HitlRequest request, Incident incident) {
        String msg = buildPendingMessage(request, incident);
        sendSlack(msg);
        sendEmail("HITL Approval Required: " + incident.getTitle(), msg);
        log.info("HITL notification sent: type=PENDING_APPROVAL requestId={} severity={}",
                request.getId(), incident.getSeverity());
    }

    /**
     * Notify that a HITL request was decided (approved or rejected).
     */
    public void notifyDecision(HitlRequest request, String decision) {
        String msg = String.format("[HITL %s] Request %s decided as %s by %s",
                decision, request.getId(), request.getDecision(),
                request.getDecidedBy() != null ? request.getDecidedBy() : "system");
        sendSlack(msg);
        log.info("HITL decision notification: requestId={} decision={}", request.getId(), decision);
    }

    /**
     * Trigger PagerDuty alert for SLA breaches or critical escalations.
     */
    public void triggerPagerDuty(HitlRequest request, String reason) {
        if (!pagerDutyEnabled) {
            log.warn("PagerDuty disabled — would have fired for requestId={} reason={}",
                    request.getId(), reason);
            return;
        }
        try {
            // TODO: inject PagerDuty Events v2 client and POST:
            // {
            //   "routing_key": pagerDutyRoutingKey,
            //   "event_action": "trigger",
            //   "payload": {
            //     "summary": reason,
            //     "severity": "critical",
            //     "source": "mcp-incident-automation",
            //     "custom_details": { "hitlRequestId": request.getId() }
            //   }
            // }
            log.info("PagerDuty STUB: would trigger alert for requestId={} reason={}", request.getId(), reason);
        } catch (Exception e) {
            log.error("PagerDuty trigger failed: {}", e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Channel stubs
    // -------------------------------------------------------------------------

    private void sendSlack(String message) {
        if (!slackEnabled) {
            log.debug("Slack disabled — STUB: {}", message);
            return;
        }
        try {
            // TODO: inject RestTemplate / WebClient and POST to slackWebhookUrl:
            // { "text": message }
            log.info("Slack STUB: would send to {} — {}", slackWebhookUrl, message);
        } catch (Exception e) {
            log.error("Slack notification failed: {}", e.getMessage());
        }
    }

    private void sendEmail(String subject, String body) {
        if (!emailEnabled) {
            log.debug("Email disabled — STUB subject={}", subject);
            return;
        }
        try {
            // TODO: inject JavaMailSender and compose MimeMessage
            log.info("Email STUB: would send subject='{}'", subject);
        } catch (Exception e) {
            log.error("Email send failed: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Message builders
    // -------------------------------------------------------------------------

    private String buildPendingMessage(HitlRequest request, Incident incident) {
        return String.format(
                ":rotating_light: *HITL Approval Required*%n" +
                "• Incident: `%s` (%s)%n" +
                "• Severity: *%s*%n" +
                "• Reason: %s%n" +
                "• SLA expires: %s%n" +
                "• Review: /api/v1/hitl/%s",
                incident.getTitle(), incident.getId(),
                incident.getSeverity(),
                request.getDecisionReason(),
                request.getExpiresAt(),
                request.getId());
    }
}
