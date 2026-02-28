package com.company.mcp.repository;

import com.company.mcp.model.ClassificationRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for classification rules.
 * Provides queries for rule-based incident classification.
 */
@Repository
public interface ClassificationRulesRepository extends JpaRepository<ClassificationRule, UUID> {

    /**
     * Find all active rules for a tenant, ordered by priority.
     */
    @Query("""
        SELECT r FROM ClassificationRule r
        WHERE r.tenantId = :tenantId AND r.isActive = true
        ORDER BY r.priority ASC
        """)
    List<ClassificationRule> findActivePrioritized(@Param("tenantId") UUID tenantId);

    /**
     * Find rules for a specific category.
     */
    @Query("""
        SELECT r FROM ClassificationRule r
        WHERE r.tenantId = :tenantId AND r.category = :category AND r.isActive = true
        ORDER BY r.priority ASC
        """)
    List<ClassificationRule> findByTenantIdAndCategoryAndIsActiveTrue(
        @Param("tenantId") UUID tenantId,
        @Param("category") String category);

    /**
     * Count active rules for a tenant.
     */
    @Query("SELECT COUNT(r) FROM ClassificationRule r WHERE r.tenantId = :tenantId AND r.isActive = true")
    long countActiveRules(@Param("tenantId") UUID tenantId);
}
