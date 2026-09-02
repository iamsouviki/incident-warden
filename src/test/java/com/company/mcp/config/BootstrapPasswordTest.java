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
 * The contract this covers is a security one: a fresh install must not ship a credential anyone
 * can read off the repository, and a restart must not undo the password an administrator chose.
 */
class BootstrapPasswordTest {

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);

    private AppUser seeded(String storedHash) {
        AppUser admin = new AppUser();
        admin.setUsername("admin");
        admin.setPasswordHash(storedHash);
        when(users.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(encoder.encode(any())).thenReturn("$2a$10$freshlyEncodedHash");
        return admin;
    }

    /** The migration inserts a NULL hash; this is the boot that turns it into a usable account. */
    @Test
    void anAdminWithNoHashIsEnrolledOnTheUsernameAndForcedToChangeIt() {
        AppUser admin = seeded(null);

        new BootstrapPassword(users, encoder).run(null);

        verify(encoder).encode("admin");
        assertThat(admin.getPasswordHash()).isEqualTo("$2a$10$freshlyEncodedHash");
        assertThat(admin.isMustChangePassword()).isTrue();
        verify(users).save(admin);
    }

    /** Same, for a row whose hash was cleared by hand to recover a lost password. */
    @Test
    void aBlankHashIsTreatedAsAbsent() {
        seeded("   ");

        new BootstrapPassword(users, encoder).run(null);

        verify(users).save(any(AppUser.class));
    }

    /**
     * The defect this pins: the previous version re-forced the admin hash to a configured value on
     * every boot, so a restart silently reverted a chosen password back to a published default.
     */
    @Test
    void anAdminWhoHasChosenAPasswordKeepsItAcrossRestarts() {
        AppUser admin = seeded("$2a$10$aHashTheOperatorChose");

        new BootstrapPassword(users, encoder).run(null);

        assertThat(admin.getPasswordHash()).isEqualTo("$2a$10$aHashTheOperatorChose");
        assertThat(admin.isMustChangePassword()).isFalse();
        verify(users, never()).save(any(AppUser.class));
    }

    @Test
    void noAdminRowAtAllIsNotAnError() {
        when(users.findByUsername("admin")).thenReturn(Optional.empty());

        new BootstrapPassword(users, encoder).run(null);

        verify(users, never()).save(any(AppUser.class));
    }
}
