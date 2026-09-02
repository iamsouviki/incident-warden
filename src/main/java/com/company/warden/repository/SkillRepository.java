package com.company.warden.repository;

import com.company.warden.model.Skill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Derived queries only — the agent vocabulary for this deployment. */
@Repository
public interface SkillRepository extends JpaRepository<Skill, UUID> {

    List<Skill> findAllByOrderByKindAscSkillKeyAsc();

    List<Skill> findByKindAndEnabledTrueOrderBySkillKeyAsc(String kind);

    Optional<Skill> findByKindAndSkillKey(String kind, String skillKey);
}
