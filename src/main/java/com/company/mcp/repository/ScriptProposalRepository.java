package com.company.mcp.repository;

import com.company.mcp.model.ScriptProposal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScriptProposalRepository extends JpaRepository<ScriptProposal, UUID> {

    List<ScriptProposal> findByThreadIdOrderByCreatedAtDesc(UUID threadId);

    Optional<ScriptProposal> findFirstByThreadIdOrderByCreatedAtDesc(UUID threadId);
}
