package com.company.mcp.config;

import com.company.mcp.model.AppUser;
import com.company.mcp.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Set;

/**
 * The password the seeded admin account starts with — and the reason no build of this project
 * ships an administrator password that anyone can look up.
 *
 * This used to be a compiled-in constant, {@code AuthController.DEFAULT_PASSWORD}, whose
 * BCrypt hash was written onto the seeded admin by changelog 1.20. In a private repository
 * that was merely untidy. In a public one it means every fresh clone stands up an
 * administrator account whose password is readable off GitHub, and it means the same literal
 * gets copied into every script, test and screenshot until a secret scanner finds all of
 * them — which is exactly what happened.
 *
 * Now there are two cases, and neither puts a usable password in source control:
 *
 * <ul>
 *   <li>{@code MCP_DEFAULT_PASSWORD} set — that is the value, and the admin account is held
 *       at it. Deterministic, and the way back in if a password is lost.</li>
 *   <li>unset — a random one per process, logged once at startup. Convenient for a first
 *       run; the log line is the only place it exists.</li>
 * </ul>
 */
@Component
public class BootstrapPassword implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapPassword.class);

    /**
     * Password hashes this repository has published in its own migrations — admin123 from
     * the 1.8/1.9 seed, and the 1.20 default. An account still carrying one of these has
     * whatever password a reader of the changelog decides it has, so {@link #run} replaces it.
     *
     * BCrypt salts every hash, so string equality here can only ever match the committed
     * values. An operator who deliberately chose the same password from the UI has a
     * different hash and is left alone.
     */
    private static final Set<String> PUBLISHED_SEED_HASHES = Set.of(
            "$2a$10$W9jPu.BKQ7IFJoaE86m3Sun.d4qqKfD4gRd24EikE6Cjp5xbkh3f.",
            "$2a$10$YxcGXgC5cSAQRpjtBy6FVOOcoQwqVHrQNIFgYut9gBWAWgMJeVQWO");

    private final String value;
    private final boolean generated;
    private final UserRepository users;
    private final PasswordEncoder encoder;

    /**
     * The password an admin hands to somebody they just added, and the one thing in this class
     * that is deliberately a literal.
     *
     * A starter password has to be sayable over a desk or a phone call, which a random string
     * is not, and the admin needs to know it without reading a server log. What makes a known
     * value acceptable here is {@code must_change_password}: an account created with it cannot
     * do anything until it is replaced at first sign-in, so this is an enrolment token with a
     * single use rather than a credential anyone keeps. It is never applied to the seeded
     * admin — that account is held at {@link #value()}.
     */
    public static final String STARTER_PASSWORD = "michaels@1";

    public BootstrapPassword(@Value("${MCP_DEFAULT_PASSWORD:}") String configured,
                             UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
        this.generated = configured == null || configured.isBlank();
        this.value = this.generated ? random() : configured.trim();
    }

    /** The bootstrap password for the seeded admin. Never a literal out of source control. */
    public String value() {
        return value;
    }

    /** The password a newly added account starts on, which it must replace at first sign-in. */
    public String starter() {
        return STARTER_PASSWORD;
    }

    @Override
    public void run(ApplicationArguments args) {
        users.findByUsername("admin").ifPresent(this::alignAdmin);
        if (generated) {
            log.warn("[BOOTSTRAP] MCP_DEFAULT_PASSWORD is not set, so the admin account is held "
                    + "at this generated password, which is different after every restart: {}", value);
        }
    }

    /**
     * With a configured password the admin account is held at it, because that doubles as the
     * documented recovery path — lose the password and you set the variable, restart, and you
     * are back in. With a generated one only a published hash is touched, since overwriting a
     * password somebody actually chose with a value that dies at the next restart would lock
     * them out rather than help them.
     */
    private void alignAdmin(AppUser admin) {
        String hash = admin.getPasswordHash();
        boolean published = hash != null && PUBLISHED_SEED_HASHES.contains(hash);
        if (generated && !published) return;
        if (hash != null && encoder.matches(value, hash)) return;

        admin.setPasswordHash(encoder.encode(value));
        admin.setUpdatedAt(OffsetDateTime.now());
        users.save(admin);
        log.warn("[BOOTSTRAP] The admin password has been set from {}.{}",
                generated ? "the generated bootstrap password" : "MCP_DEFAULT_PASSWORD",
                published ? " It previously carried a hash published in this repository." : "");
    }

    /** 15 random bytes, URL-safe. Long enough that the startup log is the only way to learn it. */
    private static String random() {
        byte[] bytes = new byte[15];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
