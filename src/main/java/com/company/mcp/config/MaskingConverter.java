package com.company.mcp.config;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.company.mcp.service.PublicReadService;

/**
 * Routes every log message through the masking regex before it is written.
 *
 * <p>Registered as {@code %mask} in {@code logback-spring.xml}. The requirement is that PII,
 * tokens and passwords never appear in a log file, and the only way to guarantee that for lines
 * nobody has audited — a stack trace, a vendor error body, an SOP excerpt echoed into a warning —
 * is to mask at the sink rather than at each of the several hundred call sites.
 *
 * <p>Deliberately reuses {@link PublicReadService#maskSensitive} rather than owning a second copy
 * of the patterns. Two regex sets covering the same secrets drift, and the one nobody is looking
 * at is the one that stops matching.
 *
 * <p>ponytail: runs ~10 regexes per log line, which is fine at INFO for this service's volume.
 * If a debug-level firehose ever makes this hot, gate it on level or precompile into a single
 * alternation — but measure before assuming it matters.
 */
public class MaskingConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return PublicReadService.maskSensitive(event.getFormattedMessage());
    }
}
