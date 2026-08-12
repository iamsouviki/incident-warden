package com.company.mcp.repository;

import com.company.mcp.model.VectorStoreEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VectorStoreEntityRepository extends JpaRepository<VectorStoreEntity, UUID> {

    @Query(value = "SELECT id, content, metadata FROM sop.vector_store " +
                   "WHERE fts_vector @@ plainto_tsquery('english', :queryStr) LIMIT :lim", nativeQuery = true)
    List<VectorStoreEntity> findByFullTextSearch(@Param("queryStr") String queryStr, @Param("lim") int lim);

    @Query(value = "SELECT id, content, metadata FROM sop.vector_store WHERE metadata->>'doc_type' = 'SOP'", nativeQuery = true)
    List<VectorStoreEntity> findAllSops();

    @Query(value = "SELECT id, content, metadata FROM sop.vector_store " +
                   "WHERE metadata->>'doc_type' = 'SOP' " +
                   "AND metadata->>'tenant_id' = :tenantId " +
                   "AND COALESCE(metadata->>'approval_status', 'APPROVED') = 'APPROVED' " +
                   "AND fts_vector @@ plainto_tsquery('english', :queryStr) " +
                   "LIMIT :lim", nativeQuery = true)
    List<VectorStoreEntity> findApprovedSopsByTenantAndFullTextSearch(@Param("tenantId") String tenantId,
                                                                        @Param("queryStr") String queryStr,
                                                                        @Param("lim") int lim);

    @Query(value = "SELECT id, content, metadata FROM sop.vector_store " +
                   "WHERE metadata->>'doc_type' = 'SOP' AND metadata->>'tenant_id' = :tenantId", nativeQuery = true)
    List<VectorStoreEntity> findAllSopsByTenant(@Param("tenantId") String tenantId);
}
