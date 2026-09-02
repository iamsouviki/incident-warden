package com.company.warden.service;

import com.company.warden.config.CurrentUser;
import com.company.warden.model.Skill;
import com.company.warden.repository.SkillRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The editable half of the agent: what it recognises in a ticket, and what it can be asked
 * to run.
 *
 * Reads for the three pipeline stages, plus CRUD for the admin page. One service rather
 * than three because the validation is where the risk is, and three services would be three
 * places to keep the same guards in step.
 *
 * What a skill cannot do is the important part. An execution skill adds a name to the tool
 * allowlist; it does not add a bypass. The key is still split into a fixed number of
 * segments, each segment still has to match {@code SAFE_SEGMENT}, the script is still
 * guardrail-scanned before a human sees it and again at dispatch, the plan is still hashed,
 * and nothing runs without an approval recorded against a person. So the worst an admin can
 * do here is name a tool the executor does not implement, which fails closed.
 *
 * The one exception is {@code mutating}: setting it false says "this cannot change the
 * target", which is what lets a plan skip a mutation review. That write is ADMIN-only and
 * audited.
 *
 * ponytail: every read hits Postgres — no cache. The tables are a few dozen rows and reads
 * happen once per plan, not per request. Add a snapshot with invalidation on write if a
 * profiler ever cares.
 */
@Service
public class SkillService {
    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    public static final String CATEGORIZATION = "CATEGORIZATION";
    public static final String EXTRACTION = "EXTRACTION";
    public static final String EXECUTION = "EXECUTION";
    private static final Set<String> KINDS = Set.of(CATEGORIZATION, EXTRACTION, EXECUTION);

    /** Execution keys are interpolated into scripts, so their shape is fixed here. */
    private static final Pattern TOOL_NAME = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");

    private final SkillRepository skills;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final ObjectMapper json;

    public SkillService(SkillRepository skills, CurrentUser currentUser, AuditService audit, ObjectMapper json) {
        this.skills = skills;
        this.currentUser = currentUser;
        this.audit = audit;
        this.json = json;
    }

    /**
     * Pushes the authored extraction patterns into {@link IncidentTarget} at boot.
     *
     * ponytail: process-global. IncidentTarget is a static utility with ~15 call sites,
     * several on paths that have no request context (intake, sync). The built-in patterns
     * still run first and the authored ones are additive, so the ceiling is "one estate's
     * host conventions", not correctness.
     */
    @PostConstruct
    void publishExtractionPatterns() {
        try {
            IncidentTarget.authoredHostPatterns(compiledHostPatterns());
        } catch (Exception e) {
            // A missing table at boot (fresh DB, migration mid-flight) must not stop startup:
            // the built-in patterns are unaffected, so the platform degrades to what it was
            // before this feature existed.
            log.warn("[SKILL] Authored extraction patterns not loaded: {}", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- reads

    /** Enabled skills of one kind, in key order. */
    public List<Skill> enabled(String kind) {
        String normalizedKind = upper(kind);
        if ("CATEGORISATION".equals(normalizedKind)) normalizedKind = CATEGORIZATION;
        return skills.findByKindAndEnabledTrueOrderBySkillKeyAsc(normalizedKind);
    }

    /** Everything for the admin page, all kinds together. */
    public List<Skill> all() {
        return skills.findAllByOrderByKindAscSkillKeyAsc();
    }

    /**
     * The execution tool table as the registry wants it: name → (segments, mutating, text).
     *
     * Empty when the table is empty, so the caller can fall back to its built-in four and a
     * database that has not been migrated yet keeps remediating exactly as it did before.
     */
    public Map<String, ToolRow> executionTools() {
        Map<String, ToolRow> rows = new LinkedHashMap<>();
        for (Skill skill : enabled(EXECUTION)) {
            String key = skill.getSkillKey().toUpperCase(Locale.ROOT);
            if (!TOOL_NAME.matcher(key).matches()) continue;   // a row written before this guard existed
            rows.put(key, new ToolRow(key, skill.getArgCount(), skill.isMutating(), text(skill)));
        }
        return rows;
    }

    /**
     * Authored host patterns, compiled, skipping any row that does not compile or carries no
     * capturing group.
     *
     * Skipping rather than throwing: a bad regex typed into the admin page must not be able
     * to stop the extractor finding hosts it used to find. The row is logged so the admin can
     * see why their pattern is not firing.
     */
    public List<Pattern> compiledHostPatterns() {
        List<Pattern> compiled = new ArrayList<>();
        for (Skill skill : enabled(EXTRACTION)) {
            String raw = skill.getPattern();
            if (raw == null || raw.isBlank()) continue;
            try {
                Pattern p = Pattern.compile(raw, Pattern.CASE_INSENSITIVE);
                if (p.matcher("").groupCount() >= 1) compiled.add(p);
            } catch (PatternSyntaxException e) {
                log.warn("[SKILL] Extraction pattern for '{}' does not compile: {}",
                        skill.getSkillKey(), e.getMessage());
            }
        }
        return compiled;
    }

    // ---------------------------------------------------------------- writes

    /**
     * Creates or replaces one skill, keyed on (kind, key) so re-posting the same key edits it
     * instead of producing a second row that silently shadows the first.
     */
    public Skill save(Skill submitted) {
        String kind = upper(submitted.getKind());
        if ("CATEGORISATION".equals(kind)) kind = CATEGORIZATION;
        if (!KINDS.contains(kind)) throw new IllegalArgumentException("kind must be one of " + KINDS);
        String key = trim(submitted.getSkillKey());
        if (key.isBlank()) throw new IllegalArgumentException("skillKey is required");

        Skill existing = submitted.getId() == null
                ? skills.findByKindAndSkillKey(kind, key).orElse(null)
                : skills.findById(submitted.getId())
                        .orElseThrow(() -> new IllegalArgumentException("Skill not found"));

        Skill skill = existing == null ? fresh(kind) : existing;
        skill.setKind(kind);
        skill.setSkillKey(EXECUTION.equals(kind) ? key.toUpperCase(Locale.ROOT) : key);
        skill.setPattern(trim(submitted.getPattern()));
        skill.setActionKey(trim(submitted.getActionKey()));
        skill.setDescription(clip(submitted.getDescription()));
        skill.setDefinitionJson(submitted.getDefinitionJson());
        skill.setEnabled(submitted.isEnabled());
        validateShape(skill);
        validateDefinition(skill);

        // The privilege boundary. Everything else on this row widens recognition; this field
        // decides whether a plan is treated as a mutation at all, so downgrading it needs the
        // role that could approve the mutation anyway, and leaves a record.
        boolean wasMutating = existing == null || existing.isMutating();
        if (!submitted.isMutating() && wasMutating && !"ADMIN".equalsIgnoreCase(currentUser.role())) {
            throw new SecurityException("Only an ADMIN can mark a skill as non-mutating");
        }
        skill.setMutating(submitted.isMutating());
        skill.setArgCount(Math.max(0, Math.min(8, submitted.getArgCount())));
        skill.setUpdatedAt(OffsetDateTime.now());
        skill.setUpdatedBy(currentUser.username());

        Skill saved = skills.save(skill);
        audit.record("SKILL", saved.getId(), existing == null ? "SKILL_CREATED" : "SKILL_UPDATED",
                currentUser.username(), Map.of("kind", kind, "key", saved.getSkillKey(),
                        "mutating", String.valueOf(saved.isMutating()), "enabled", String.valueOf(saved.isEnabled())));
        if (EXTRACTION.equals(kind)) publishExtractionPatterns();
        return saved;
    }

    public void delete(UUID id) {
        Skill skill = skills.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Skill not found"));
        skills.delete(skill);
        audit.record("SKILL", id, "SKILL_DELETED", currentUser.username(),
                Map.of("kind", skill.getKind(), "key", skill.getSkillKey()));
        if (EXTRACTION.equals(skill.getKind())) publishExtractionPatterns();
    }

    /**
     * Per-kind shape rules, enforced on the way in rather than tolerated on the way out — a
     * regex that does not compile is a silent hole in extraction, and a tool name with a
     * space in it is a key that can never match.
     */
    private void validateShape(Skill skill) {
        switch (skill.getKind()) {
            case EXECUTION -> {
                if (!TOOL_NAME.matcher(skill.getSkillKey()).matches()) {
                    throw new IllegalArgumentException(
                            "An execution skill key must be UPPER_SNAKE_CASE, 2–40 characters");
                }
            }
            case CATEGORIZATION -> {
                if (skill.getPattern().isBlank()) {
                    throw new IllegalArgumentException("A categorisation skill needs at least one keyword");
                }
            }
            case EXTRACTION -> {
                if (skill.getPattern().isBlank() && !hasFields(skill.getDefinitionJson()))
                    throw new IllegalArgumentException("An extraction skill needs fields with capturing patterns");
                if (!skill.getPattern().isBlank()) validatePattern(skill.getPattern());
            }
            default -> throw new IllegalArgumentException("Unknown kind: " + skill.getKind());
        }
    }

    private void validatePattern(String raw) {
        try {
            if (Pattern.compile(raw).matcher("").groupCount() < 1)
                throw new IllegalArgumentException("The pattern needs one capturing group around the value to extract");
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("The pattern is not a valid regular expression: " + e.getDescription());
        }
    }

    private boolean hasFields(String definition) {
        try { return !json.readTree(definition).path("fields").isEmpty(); }
        catch (Exception e) { return false; }
    }

    private void validateDefinition(Skill skill) {
        if (skill.getDefinitionJson() == null || skill.getDefinitionJson().isBlank()) return;
        if (skill.getDefinitionJson().length() > 50000) throw new IllegalArgumentException("Rule definition is too large");
        try {
            Map<String, Object> root = json.readValue(skill.getDefinitionJson(), new TypeReference<>() {});
            Object path = root.get("script_path");
            if (path != null && (!String.valueOf(path).startsWith("scripts/") || String.valueOf(path).contains("..")))
                throw new IllegalArgumentException("script_path must stay under scripts/ and cannot contain '..'");
            if (EXTRACTION.equals(skill.getKind())) {
                Object rawFields = root.get("fields");
                if (!(rawFields instanceof List<?> fields) || fields.isEmpty())
                    throw new IllegalArgumentException("Extraction definition needs a non-empty fields array");
                for (Object rawField : fields) {
                    if (!(rawField instanceof Map<?, ?> field) || field.get("key") == null)
                        throw new IllegalArgumentException("Every extraction field needs a key");
                    Object rawPattern = field.get("pattern");
                    String pattern = rawPattern == null ? "" : String.valueOf(rawPattern);
                    if (!pattern.isBlank()) validatePattern(pattern);
                }
            }
        } catch (IllegalArgumentException e) { throw e;
        } catch (Exception e) { throw new IllegalArgumentException("Rule definition must be valid JSON"); }
    }

    /** Extracts the category-owned fields; defaults are values, not prompts. */
    public RuleResolution resolve(String category, String text, Map<String, String> overrides) {
        Skill extraction = skills.findByKindAndSkillKey(EXTRACTION, category).filter(Skill::isEnabled).orElse(null);
        if (extraction == null || extraction.getDefinitionJson() == null) return RuleResolution.empty();
        try {
            Map<String, Object> root = json.readValue(extraction.getDefinitionJson(), new TypeReference<>() {});
            List<Map<String, Object>> definitions = (List<Map<String, Object>>) root.getOrDefault("fields", List.of());
            Map<String, String> values = new LinkedHashMap<>();
            String source = text == null ? "" : text;
            for (Map<String, Object> field : definitions) {
                String key = String.valueOf(field.get("key"));
                String value = overrides == null ? null : overrides.get(key);
                boolean booleanField = "boolean".equalsIgnoreCase(String.valueOf(field.get("type")));
                if (value != null && (value.length() > 300 || value.chars().anyMatch(Character::isISOControl)))
                    throw new IllegalArgumentException("Invalid value for extraction field " + key);
                if (booleanField && value != null && !value.isBlank()
                        && !Set.of("true", "false").contains(value.trim().toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException("Boolean extraction field " + key + " must be true or false");
                if (booleanField && (value == null || value.isBlank())) {
                    String pattern = String.valueOf(field.getOrDefault("pattern", ""));
                    value = (!pattern.isBlank() && Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(source).find())
                            ? "true" : String.valueOf(field.getOrDefault("default", "false"));
                } else if (value == null || value.isBlank()) {
                    String pattern = String.valueOf(field.getOrDefault("pattern", ""));
                    if (!pattern.isBlank()) {
                        Matcher matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(source);
                        if (matcher.find()) value = matcher.groupCount() > 0 ? matcher.group(1) : matcher.group();
                    }
                }
                if ((value == null || value.isBlank()) && field.get("default") != null) value = String.valueOf(field.get("default"));
                if (value != null && !value.isBlank()) values.put(key, value.trim());
            }
            List<Map<String, Object>> missing = definitions.stream()
                    .filter(field -> Boolean.TRUE.equals(field.get("required")) && !values.containsKey(String.valueOf(field.get("key"))))
                    .toList();
            return new RuleResolution(definitions, values, missing, root);
        } catch (Exception e) {
            log.warn("[SKILL] Invalid extraction definition for {}: {}", category, e.getMessage());
            return RuleResolution.empty();
        }
    }

    public RuleResolution resolve(String category, String text) {
        return resolve(category, text, Map.of());
    }

    private Skill fresh(String kind) {
        Skill skill = new Skill();
        skill.setId(UUID.randomUUID());
        skill.setKind(kind);
        skill.setCreatedAt(OffsetDateTime.now());
        return skill;
    }

    /** Keywords split the way the classifier splits them, so the page and the agent agree. */
    public static List<String> keywords(Skill skill) {
        List<String> terms = new ArrayList<>();
        if (skill.getPattern() == null) return terms;
        for (String term : skill.getPattern().split("[,;]+")) {
            String clean = term.trim().toLowerCase(Locale.ROOT);
            if (!clean.isEmpty()) terms.add(clean);
        }
        return terms;
    }

    private String text(Skill skill) {
        return skill.getDescription() == null || skill.getDescription().isBlank()
                ? skill.getSkillKey() + " (no description yet)" : skill.getDescription();
    }

    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private String trim(String value) { return value == null ? "" : value.trim(); }
    private String clip(String value) {
        String t = trim(value);
        return t.length() <= 600 ? t : t.substring(0, 600);
    }

    /** What {@link RemediationToolRegistry} needs from a row, without exposing the entity. */
    public record ToolRow(String name, int segments, boolean mutating, String description) {}

    public record RuleResolution(List<Map<String, Object>> fields, Map<String, String> values,
                                 List<Map<String, Object>> missing, Map<String, Object> resolution) {
        public static RuleResolution empty() { return new RuleResolution(List.of(), Map.of(), List.of(), Map.of()); }
    }
}
