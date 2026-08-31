package com.company.mcp.config;

import com.company.mcp.model.AppUser;
import com.company.mcp.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rule being guarded: no build of this project may leave an account on a password that
 * can be read out of this repository, and no restart may take an account away from a password
 * a human deliberately chose. Those two pull in opposite directions, which is the only reason
 * {@link BootstrapPassword#run} has a branch at all.
 */
class BootstrapPasswordTest {

    /** The 1.20 default's hash, published in this repository's own changelog. */
    private static final String PUBLISHED = "$2a$10$YxcGXgC5cSAQRpjtBy6FVOOcoQwqVHrQNIFgYut9gBWAWgMJeVQWO";
    private static final String CHOSEN_BY_A_HUMAN = "$2a$10$someOtherSaltAndHashEntirelyItsOwn";

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);

    private BootstrapPassword bootstrap(String configured, String storedHash) {
        AppUser admin = new AppUser();
        admin.setUsername("admin");
        admin.setPasswordHash(storedHash);
        when(users.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(encoder.encode(any())).thenReturn("$2a$10$freshlyEncodedHash");
        when(encoder.matches(any(), any())).thenReturn(false);
        return new BootstrapPassword(configured, users, encoder);
    }

    @Test
    void aConfiguredPasswordIsUsedVerbatim() {
        assertThat(bootstrap("chosen-by-the-operator", PUBLISHED).value()).isEqualTo("chosen-by-the-operator");
    }

    @Test
    void withNothingConfiguredThePasswordIsGeneratedAndUnguessable() {
        String first = bootstrap("", PUBLISHED).value();
        String second = bootstrap("   ", PUBLISHED).value();
        assertThat(first).hasSizeGreaterThanOrEqualTo(20).isNotEqualTo(second);
    }

    /** The finding that started this: a hash anyone can read off GitHub must not survive a boot. */
    @Test
    void aPublishedSeedHashIsAlwaysReplaced() {
        bootstrap("", PUBLISHED).run(null);
        verify(users).save(any(AppUser.class));
    }

    /**
     * The opposite failure. A generated password is gone at the next restart, so overwriting a
     * password someone chose with one nobody recorded would be a lockout dressed as hardening.
     */
    @Test
    void aChosenPasswordSurvivesWhenNothingIsConfigured() {
        bootstrap("", CHOSEN_BY_A_HUMAN).run(null);
        verify(users, never()).save(any(AppUser.class));
    }

    /** Setting the variable is the documented way back in, so it must win over a stored hash. */
    @Test
    void aConfiguredPasswordIsTheRecoveryPath() {
        bootstrap("set-after-losing-the-password", CHOSEN_BY_A_HUMAN).run(null);
        verify(users).save(any(AppUser.class));
    }

    /** Idempotent: the same variable across ten restarts must not mean ten writes. */
    @Test
    void anAdminAlreadyOnTheConfiguredPasswordIsLeftAlone() {
        AppUser admin = new AppUser();
        admin.setUsername("admin");
        admin.setPasswordHash(CHOSEN_BY_A_HUMAN);
        when(users.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(encoder.matches("already-set", CHOSEN_BY_A_HUMAN)).thenReturn(true);

        new BootstrapPassword("already-set", users, encoder).run(null);
        verify(users, never()).save(any(AppUser.class));
    }
}
