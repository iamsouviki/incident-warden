package com.company.warden.service;

import com.company.warden.model.AppUser;
import com.company.warden.model.Incident;
import com.company.warden.repository.SystemConfigRepository;
import com.company.warden.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationServiceRecipientsTest {

    private final SystemConfigRepository config = mock(SystemConfigRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final NotificationService service = new NotificationService(config, users);

    private static Incident incident(String reporterEmail, String assignee, String group) {
        Incident incident = new Incident();
        incident.setId(UUID.fromString("00000000-0000-0000-0000-0000000000ff"));
        incident.setSubject("Printer offline");
        incident.setReporterEmail(reporterEmail);
        incident.setAssignee(assignee);
        incident.setAssignedGteam(group);
        return incident;
    }

    private void noLookups() {
        when(users.findByUsername(anyString())).thenReturn(Optional.empty());
    }

    private void user(String username, String email) {
        AppUser appUser = new AppUser();
        appUser.setUsername(username);
        appUser.setEmail(email);
        when(users.findByUsername(username)).thenReturn(Optional.of(appUser));
    }

    @Test
    void resolvesReporterAndAssignee() {
        noLookups();
        user("john_ops", "john.ops@company.com");

        assertThat(service.recipientsFor(incident("caller@company.com", "john_ops", "IT Ops")))
                .containsExactly("caller@company.com", "john.ops@company.com");
    }

    @Test
    void dedupesCaseInsensitively() {
        noLookups();
        user("john_ops", "John.Ops@Company.COM");

        assertThat(service.recipientsFor(incident("john.ops@company.com", "john_ops", "IT Ops")))
                .containsExactly("john.ops@company.com");
    }
}
