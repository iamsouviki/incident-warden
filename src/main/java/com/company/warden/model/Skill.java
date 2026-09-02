package com.company.warden.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One thing the agent knows how to recognise, editable from the UI.
 *
 * Three stages of the pipeline used to be Java constants — the classifier's keyword
 * ladder, the host regexes, the four-entry tool table. Each is the same shape of
 * decision (text in, a decision out), so each is a row here, discriminated by
 * {@link #kind}:
 *
 *   CATEGORIZATION  {@code pattern} = keywords, {@code skillKey} = the category,
 *                   {@code actionKey} = the action to propose.
 *   EXTRACTION      {@code pattern} = a regex whose first group is the value,
 *                   {@code skillKey} = the field it fills.
 *   EXECUTION       {@code skillKey} = the tool name, {@code argCount} = segments
 *                   required after it, {@code mutating} = whether it changes the host.
 *
 * A row widens what the platform can recognise, never what it may do: an execution
 * skill is still parsed segment by segment, guardrail-scanned, hash-pinned into a plan
 * and refused without a human approval. {@code mutating} is the one field that could
 * quietly reclassify a restart as safe, so {@code SkillService} accepts a write to it
 * only from an ADMIN and records the change in the audit trail.
 */
@Entity
@Table(name = "skills", schema = "tools")
public class Skill {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    /** CATEGORIZATION | EXTRACTION | EXECUTION. */
    @Column(name = "kind", nullable = false, length = 24)
    private String kind;

    /** The tool name, the category, or the field name — depending on {@link #kind}. */
    @Column(name = "skill_key", nullable = false, length = 120)
    private String skillKey;

    /** Keywords (categorisation) or a regex (extraction). Unused by execution skills. */
    @Column(name = "pattern", length = 600)
    private String pattern;

    /** The remediation action a categorisation match should propose. */
    @Column(name = "action_key", length = 120)
    private String actionKey;

    /** Colon-separated segments an execution key must carry after the tool name. */
    @Column(name = "arg_count", nullable = false)
    private int argCount;

    /** Whether running this changes the target. Defaults to true: unknown means dangerous. */
    @Column(name = "mutating", nullable = false)
    private boolean mutating = true;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** Shown to the reviewer on the plan, so it is written for a person, not for a log. */
    @Column(name = "description", length = 600)
    private String description;

    /** Versioned JSON for category extraction and resolution rules. */
    @Column(name = "definition_json", columnDefinition = "TEXT")
    private String definitionJson;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getSkillKey() { return skillKey; }
    public void setSkillKey(String skillKey) { this.skillKey = skillKey; }
    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }
    public String getActionKey() { return actionKey; }
    public void setActionKey(String actionKey) { this.actionKey = actionKey; }
    public int getArgCount() { return argCount; }
    public void setArgCount(int argCount) { this.argCount = argCount; }
    public boolean isMutating() { return mutating; }
    public void setMutating(boolean mutating) { this.mutating = mutating; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getDefinitionJson() { return definitionJson; }
    public void setDefinitionJson(String definitionJson) { this.definitionJson = definitionJson; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
