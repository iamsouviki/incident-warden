package com.company.warden.repository;

import com.company.warden.model.IncidentComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface IncidentCommentRepository extends JpaRepository<IncidentComment, UUID> {
    List<IncidentComment> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId);

    /**
     * Notes for a whole candidate set in one query. The precedent matcher reads the
     * resolution notes of up to a hundred past incidents; one query per incident would
     * put a hundred round trips on the path of whoever just clicked Save.
     */
    List<IncidentComment> findByIncidentIdInOrderByCreatedAtDesc(Collection<UUID> incidentIds);
}
