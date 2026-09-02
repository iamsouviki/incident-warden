package com.company.warden.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The log masking, at the sink. Every {@code log.info} in this application goes through these
 * two converters, so this is the one place that proves a credential cannot reach the file —
 * whichever call site was careless.
 */
class MaskingConverterTest {

    private static LoggingEvent event(String message) {
        LoggingEvent event = new LoggingEvent();
        event.setMessage(message);
        event.setLevel(Level.INFO);
        return event;
    }

    @Test
    void secretsPiiAndHostsAreMaskedOutOfTheMessage() {
        String masked = new MaskingConverter().convert(event(
                "Connecting as password=hunter2 for alice@example.com at 10.4.12.9 on store-0042-pos-01"));

        assertFalse(masked.contains("hunter2"), masked);
        assertFalse(masked.contains("alice@example.com"), masked);
        assertFalse(masked.contains("10.4.12.9"), masked);
        assertFalse(masked.contains("store-0042-pos-01"), masked);
        assertTrue(masked.contains("****"), masked);
    }

    /**
     * The reason the throwable needs its own converter: the message never held the credential,
     * the exception did.
     */
    @Test
    void aCredentialInAnExceptionMessageIsMaskedButTheFramesSurvive() {
        LoggingEvent event = event("Integration call failed");
        event.setThrowableProxy(new ThrowableProxy(new IllegalStateException(
                "jdbc:postgresql://warden_user:s3cr3t@db.internal:5432/incident_warden_db")));

        MaskingThrowableConverter converter = new MaskingThrowableConverter();
        converter.start();
        String masked = converter.convert(event);

        assertFalse(masked.contains("s3cr3t"), masked);
        // A frame line is a class, a method and a line number. Masking it redacts nothing and
        // makes the trace unreadable — org.hibernate.tool.schema.internal became ****.internal.
        assertTrue(masked.contains("com.company.warden.config.MaskingConverterTest"), masked);
    }
}
