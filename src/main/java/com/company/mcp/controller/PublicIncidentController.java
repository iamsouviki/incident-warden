package com.company.mcp.controller;

import com.company.mcp.service.PublicReadService;
import com.company.mcp.service.RateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The unauthenticated surface. Two GETs, no writes, no model call, no {@code CurrentUser} —
 * the tenant comes from configuration because there is no principal to read it from.
 *
 * Rate limited per caller address: this is the one route that can be hit without an account,
 * so it is the one route that can be used to walk the ticket table 20 rows at a time.
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicIncidentController {

    /** Generous for a person, tight for a scraper. */
    private static final int REQUESTS_PER_MINUTE = 30;

    private final PublicReadService publicRead;
    private final RateLimiterService rateLimiter;

    public PublicIncidentController(PublicReadService publicRead, RateLimiterService rateLimiter) {
        this.publicRead = publicRead;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats(HttpServletRequest request) {
        ResponseEntity<?> refusal = gate(request);
        return refusal != null ? refusal : ResponseEntity.ok(publicRead.stats());
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam(value = "q", defaultValue = "") String q,
                                    HttpServletRequest request) {
        ResponseEntity<?> refusal = gate(request);
        return refusal != null ? refusal : ResponseEntity.ok(publicRead.search(q));
    }

    /** @return null when the request may proceed, otherwise the response to send instead. */
    private ResponseEntity<?> gate(HttpServletRequest request) {
        // 404, not 403: a disabled public surface should look absent rather than advertise
        // that there is something here to sign in for.
        if (!publicRead.enabled()) return ResponseEntity.status(404).body(Map.of("error", "Not found"));
        // ponytail: remote address, so one proxy in front makes this one shared bucket. Read
        // X-Forwarded-For only once a trusted proxy is actually configured — until then it is
        // a header the caller controls, i.e. a rate limit the caller can reset.
        if (!rateLimiter.allow("public:" + request.getRemoteAddr(), REQUESTS_PER_MINUTE)) {
            return ResponseEntity.status(429).body(Map.of("error", "Too many requests. Try again shortly."));
        }
        return null;
    }
}
