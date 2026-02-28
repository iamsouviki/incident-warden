package com.company.mcp.repository;

import com.company.mcp.model.Tenant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, UUID> {
    Optional<Tenant> findByNameIgnoreCase(String name);

    Integer countByIsActiveTrue();
}
