package com.company.mcp.config;

import com.company.mcp.model.AppUser;
import com.company.mcp.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;

@Configuration
@Profile("local")
public class LocalDemoDataConfig {

    @Bean
    CommandLineRunner seedLocalDemoUser(UserRepository users, PasswordEncoder encoder) {
        return args -> {
            if (users.findByUsername("admin").isPresent()) return;

            AppUser admin = new AppUser();
            admin.setUsername("admin");
            admin.setEmail("admin@localhost");
            admin.setPasswordHash(encoder.encode("admin123"));
            admin.setRole("ADMIN");
            admin.setTenantId("tenant-local");
            admin.setTenantName("Local Demo Workspace");
            admin.setEnabled(true);
            admin.setCreatedAt(OffsetDateTime.now());
            admin.setUpdatedAt(OffsetDateTime.now());
            users.save(admin);
        };
    }
}
