package com.company.warden.config;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import com.company.warden.service.PublicReadService;

/**
 * The same masking applied to stack traces.
 *
 * <p>{@link MaskingConverter} only sees {@code %msg}; Logback renders the throwable through a
 * separate converter, so without this one a masked log file still leaks through the exception —
 * and exceptions are the worst offender. An {@code HttpClientErrorException} from a vendor carries
 * the response body, a {@code JdbcSQLException} carries the connection URL with its userinfo, and
 * both go straight to the file untouched.
 *
 * <p>Frame lines are left alone. Only the exception messages are masked, because only they carry
 * data — a frame is a class, a method and a line number. Masking them too turned
 * {@code org.hibernate.tool.schema.internal} into {@code org.hibernate.tool.****.internal} (the
 * internal-hostname rule reads {@code schema.internal} as an FQDN) and cost every stack trace in
 * the file its readability to redact nothing.
 *
 * <p>Registered as {@code %maskEx} in {@code logback-spring.xml}, replacing {@code %ex}.
 */
public class MaskingThrowableConverter extends ThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        StringBuilder masked = new StringBuilder();
        for (String line : super.throwableProxyToString(tp).split("\n", -1)) {
            // "\tat com.foo.Bar.baz(Bar.java:42)" and "\t... 117 common frames omitted".
            masked.append(line.startsWith("\tat ") || line.startsWith("\t... ")
                    ? line
                    : PublicReadService.maskSensitive(line)).append('\n');
        }
        return masked.toString();
    }
}
