package com.company.mcp.repository;

import com.company.mcp.model.CustomTool;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomToolRepository extends JpaRepository<CustomTool, UUID> {

    List<CustomTool> findByEnabledTrue();

    List<CustomTool> findByCategoryIgnoreCase(String category);

    Optional<CustomTool> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);
}
