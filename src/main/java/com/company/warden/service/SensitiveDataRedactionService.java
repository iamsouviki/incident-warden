package com.company.warden.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Pattern;

/** Redacts sensitive values before untrusted text enters an LLM prompt. */
@Service
public class SensitiveDataRedactionService {
    private static final String REDACTED = "[REDACTED]";
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern IPV6 = Pattern.compile("(?i)(?<![A-F0-9:])(?:[A-F0-9]{0,4}:){2,7}[A-F0-9]{0,4}(?![A-F0-9:])");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(\\b(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key)\\b\\s*[:=]\\s*)([^\\s,;]+)");
    private static final Pattern CREDENTIAL_ASSIGNMENT = Pattern.compile(
            "(?i)(\\b(?:username|user|login)\\b\\s*[:=]\\s*)([^\\s,;]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)(\\bbearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b");
    private static final Pattern AWS_ACCESS_KEY = Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b");
    private static final Pattern PRIVATE_KEY = Pattern.compile("(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----");
        private static final Pattern PROMPT_INJECTION = Pattern.compile(
            "(?i)ignore\\s+(?:all\\s+)?(?:previous|prior)|disregard\\s+(?:all\\s+)?(?:previous|above)|system\\s+prompt|developer\\s+mode|jailbreak|override\\s+(?:the\\s+)?guardrail|reveal\\s+your\\s+instructions|<\\|im_start\\|>|</?system>");

    public String redact(String value) {
        if (value == null || value.isBlank()) return value == null ? "" : value;
        String redacted = SECRET_ASSIGNMENT.matcher(value).replaceAll("$1" + REDACTED);
        redacted = CREDENTIAL_ASSIGNMENT.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = PRIVATE_KEY.matcher(redacted).replaceAll(REDACTED);
        redacted = BEARER.matcher(redacted).replaceAll("$1" + REDACTED);
        redacted = JWT.matcher(redacted).replaceAll(REDACTED);
        redacted = AWS_ACCESS_KEY.matcher(redacted).replaceAll(REDACTED);
        redacted = EMAIL.matcher(redacted).replaceAll(REDACTED);
        redacted = IPV4.matcher(redacted).replaceAll(REDACTED);
        redacted = IPV6.matcher(redacted).replaceAll(REDACTED);
        return redacted;
    }

    public String redactForLlm(String value) {
        String redacted = redact(value);
        return PROMPT_INJECTION.matcher(redacted).find() ? "[UNTRUSTED_CONTENT_REMOVED]" : redacted;
    }

    public String redactMapValues(Map<?, ?> values) {
        if (values == null || values.isEmpty()) return "{}";
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!first) out.append(", ");
            first = false;
            out.append(String.valueOf(entry.getKey())).append('=').append(redact(String.valueOf(entry.getValue())));
        }
        return out.append('}').toString();
    }
}
