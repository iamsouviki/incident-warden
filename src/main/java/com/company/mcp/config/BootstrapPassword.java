package com.company.mcp.config;

import com.company.mcp.model.AppUser;
import com.company.mcp.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Enrols the seeded administrator on its single-use starter password, so that no build of this
 * project ships an administrator credential anyone can look up.
 *
 * <p>One rule, the same one {@code AuthController} applies to every account it creates or
 * resets: the initial password is the username, and the account cannot do anything until that
 * password is replaced at first sign-in. Nothing is committed (the migration inserts the row
 * with a NULL hash, which cannot authenticate), nothing is configurable (an environment
 * variable that overwrites the administrator credential on every boot is the insecure default
 * this class exists to remove), and nothing is logged.
 *
 * <p>Runs once, on the absence of a hash. An administrator who has chosen their own password
 * keeps it across restarts. Lost-password recovery is therefore a deliberate act against the
 * database — {@code UPDATE auth.users SET password_hash = NULL WHERE username = 'admin'} and a
 * restart puts the account back on its starter password with the forced change re-armed.
 */
@Component
public class BootstrapPassword implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapPassword.class);

    private static final String ADMIN = "admin";

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public BootstrapPassword(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        users.findByUsername(ADMIN)
                .filter(admin -> admin.getPasswordHash() == null || admin.getPasswordHash().isBlank())
                .ifPresent(this::enrol);
    }

    private void enrol(AppUser admin) {
        admin.setPasswordHash(encoder.encode(admin.getUsername()));
        admin.setMustChangePassword(true);
        admin.setUpdatedAt(OffsetDateTime.now());
        users.save(admin);
        // The password itself is deliberately absent: a log line is the one place a starter
        // password reliably outlives the process that used it.
        log.info("[BOOTSTRAP] Administrator enrolled on its starter password; a replacement is "
                + "required at first sign-in");
    }
}
