package com.company.mcp.repository;

import com.company.mcp.model.TeamEmployee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface TeamEmployeeRepository extends JpaRepository<TeamEmployee, UUID> {
    List<TeamEmployee> findByTeamId(UUID teamId);
    List<TeamEmployee> findByTeamName(String teamName);
}
