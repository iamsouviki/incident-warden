package com.company.mcp.repository;

import com.company.mcp.model.Skill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Derived queries only, and every finder takes a tenant — one workspace's vocabulary must
 * not decide what another workspace's agent recognises.
 */
@Repository
public interface SkillRepository extends JpaRepository<Skill, UUID> {

    List<Skill> findByTenantIdOrderByKindAscSkillKeyAsc(String tenantId);

    List<Skill> findByTenantIdAndKindAndEnabledTrueOrderBySkillKeyAsc(String tenantId, String kind);

    Optional<Skill> findByIdAndTenantId(UUID id, String tenantId);

    Optional<Skill> findByTenantIdAndKindAndSkillKey(String tenantId, String kind, String skillKey);
}
