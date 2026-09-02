package com.company.warden.repository;

import com.company.warden.model.ExecutionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ExecutionLogRepository extends JpaRepository<ExecutionLog, UUID> {
    List<ExecutionLog> findByScriptIdOrderByTimestampDesc(UUID scriptId);
}
