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

class BootstrapPasswordTest {

    private static final String STORED_HASH = "$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG";

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
        assertThat(bootstrap("chosen-by-the-operator", STORED_HASH).value()).isEqualTo("chosen-by-the-operator");
    }

    @Test
    void withNothingConfiguredThePasswordDefaultsToAdmin() {
        String first = bootstrap("", STORED_HASH).value();
        String second = bootstrap("   ", STORED_HASH).value();
        assertThat(first).isEqualTo("admin");
        assertThat(second).isEqualTo("admin");
    }

    @Test
    void alignsAdminPasswordIfMismatch() {
        bootstrap("admin", STORED_HASH).run(null);
        verify(users).save(any(AppUser.class));
    }

    @Test
    void anAdminAlreadyOnTheConfiguredPasswordIsLeftAlone() {
        AppUser admin = new AppUser();
        admin.setUsername("admin");
        admin.setPasswordHash(STORED_HASH);
        when(users.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(encoder.matches("admin", STORED_HASH)).thenReturn(true);

        new BootstrapPassword("admin", users, encoder).run(null);
        verify(users, never()).save(any(AppUser.class));
    }
}
