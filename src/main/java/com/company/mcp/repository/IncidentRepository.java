package com.company.mcp.repository;

import com.company.mcp.model.Incident;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

@Repository
public interface IncidentRepository extends JpaRepository<Incident, UUID>, JpaSpecificationExecutor<Incident> {
    Optional<Incident> findFirstByExternalSourceAndExternalId(String externalSource, String externalId);
    Optional<Incident> findByExternalId(String externalId);

    /** Bounded: assistant context must never grow without limit. */
    List<Incident> findTop50ByOrderByUpdatedAtDesc();

    long countByStatus(String status);

    /**
     * Highest internally-issued ticket number, or null when none has been issued.
     */
    @Query("select max(cast(substring(i.externalId, 4) as long)) from Incident i "
            + "where i.externalSource = 'Internal' and i.externalId like 'INC%' and length(i.externalId) = 12")
    Long findMaxInternalTicketNumber();

    // ── Anonymous read surface (PublicReadService) ──────────────────────────────
    // Counted in SQL over the whole table rather than derived from a bounded window: the
    // assistant's 50-row context is a sample, and a sample that is presented as "how many
    // are open" is a wrong answer with a confident face.

    /** {@code [status, count]} rows. */
    @Query("select i.status, count(i) from Incident i group by i.status")
    List<Object[]> countGroupedByStatus();

    /** {@code [priority, count]} rows. */
    @Query("select i.priority, count(i) from Incident i group by i.priority")
    List<Object[]> countGroupedByPriority();

    @Query("select max(i.updatedAt) from Incident i")
    OffsetDateTime findLastUpdatedAt();

    /**
     * The redacted public row, projected in the query itself.
     *
     * Only the five columns an anonymous caller is allowed to see leave the database, so a
     * later change to the response shape cannot accidentally start returning a description.
     * The match is also restricted to those same columns: matching on description would let
     * a stranger confirm a phrase they cannot read.
     */
    @Query("select i.externalId, i.subject, i.description, i.status, i.priority, i.updatedAt from Incident i "
            + "where lower(i.subject) like :like or lower(i.externalId) like :like or lower(i.priority) like :like or lower(coalesce(i.description, '')) like :like "
            + "order by i.updatedAt desc")
    List<Object[]> searchPublicRows(@Param("like") String like, Pageable page);
}
