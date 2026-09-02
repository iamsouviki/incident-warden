package com.company.warden.repository;

import com.company.warden.model.SavedScript;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface SavedScriptRepository extends JpaRepository<SavedScript, UUID> {
}
