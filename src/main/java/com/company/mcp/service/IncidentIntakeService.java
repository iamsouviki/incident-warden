package com.company.mcp.service;

import com.company.mcp.config.CurrentUser;
import com.company.mcp.dto.NormalizedIncidentRequest;
import com.company.mcp.model.Incident;
import com.company.mcp.repository.IncidentRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class IncidentIntakeService {
    private static final int MAX_ROWS = 500;
    private static final int RESPONSE_ITEM_LIMIT = 50;
    private final IncidentRepository incidents;
    private final IncidentService incidentService;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public IncidentIntakeService(IncidentRepository incidents, IncidentService incidentService, CurrentUser currentUser, AuditService audit) {
        this.incidents = incidents; this.incidentService = incidentService; this.currentUser = currentUser; this.audit = audit;
    }

    /** One ticket pushed by a third-party system. Eligible for precedent auto-run, like any single ticket. */
    public Map<String, Object> ingest(NormalizedIncidentRequest request) {
        return ingest(request, true);
    }

    private Map<String, Object> ingest(NormalizedIncidentRequest request, boolean considerUnattended) {
        validate(request);
        String tenant = currentUser.tenantId();
        String source = canonicalSource(request.sourceSystem());
        String reference = Optional.ofNullable(request.sourceReference()).filter(v -> !v.isBlank()).orElse(fingerprint(request));
        Optional<Incident> existing = incidents.findFirstByTenantIdAndExternalSourceAndExternalId(tenant, source, reference);
        if (existing.isPresent()) return Map.of("status", "DEDUPLICATED", "incident", existing.get());
        Incident created = incidentService.createIncident(Incident.builder()
                .tenantId(tenant).subject(request.subject().trim()).description(limit(request.description(), 8_000))
                .priority(priority(source, request.priority(), request.severity())).category(blankDefault(request.category(), "Universal"))
                .externalSource(source).externalId(reference).assignedGteam("IT Ops").assignee("Unassigned")
                .reporterEmail(request.reporterEmail()).build(), considerUnattended);
        audit.record(tenant, "INCIDENT", created.getId(), "INTAKE_ACCEPTED", currentUser.username(), Map.of("source", source, "reference", reference));
        return Map.of("status", "CREATED", "incident", created);
    }

    public Map<String, Object> importFile(MultipartFile file, String sourceSystem) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("A non-empty CSV or XLSX file is required");
        String source = canonicalSource(blank(sourceSystem) ? "Custom Import" : sourceSystem);
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        List<NormalizedIncidentRequest> rows = name.endsWith(".csv") ? csv(file, source) : name.endsWith(".xlsx") ? xlsx(file, source) : List.of();
        if (rows.isEmpty() && !(name.endsWith(".csv") || name.endsWith(".xlsx"))) throw new IllegalArgumentException("Only .csv and .xlsx import files are supported");
        int created = 0, deduplicated = 0, rejected = 0;
        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> items = new ArrayList<>();
        for (int index = 0; index < Math.min(MAX_ROWS, rows.size()); index++) {
            try {
                // considerUnattended=false: an imported row goes to the HITL queue like any
                // other unproven incident. One upload must not be able to act on 500 hosts.
                Map<String, Object> outcome = ingest(rows.get(index), false);
                boolean isCreated = "CREATED".equals(outcome.get("status"));
                if (isCreated) created++; else deduplicated++;
                if (items.size() < RESPONSE_ITEM_LIMIT) {
                    Incident incident = (Incident) outcome.get("incident");
                    items.add(Map.of("row", index + 2, "status", outcome.get("status"), "incidentId", incident.getId(), "reference", incident.getExternalId()));
                }
            } catch (Exception e) {
                rejected++; String error = "row " + (index + 2) + ": " + e.getMessage(); errors.add(error);
                if (items.size() < RESPONSE_ITEM_LIMIT) items.add(Map.of("row", index + 2, "status", "REJECTED", "error", e.getMessage()));
            }
        }
        return Map.of("received", Math.min(MAX_ROWS, rows.size()), "created", created, "deduplicated", deduplicated, "rejected", rejected, "errors", errors, "items", items);
    }

    private List<NormalizedIncidentRequest> csv(MultipartFile file, String sourceSystem) {
        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            List<List<String>> records = parseCsv(reader);
            if (records.isEmpty()) return List.of();
            String[] keys = records.get(0).stream().map(this::cleanHeader).toArray(String[]::new);
            List<NormalizedIncidentRequest> result = new ArrayList<>();
            for (int row = 1; row < records.size() && result.size() < MAX_ROWS; row++) {
                List<String> values = records.get(row);
                if (values.stream().allMatch(this::blank)) continue;
                result.add(fromValues(keys, values.toArray(String[]::new), sourceSystem));
            }
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("CSV could not be parsed: " + e.getMessage());
        }
    }

    /** RFC-4180-compatible enough for quoted commas, escaped quotes and multiline fields. */
    private List<List<String>> parseCsv(Reader input) throws java.io.IOException {
        try (java.io.PushbackReader reader = new java.io.PushbackReader(input, 1)) {
            List<List<String>> records = new ArrayList<>(); List<String> row = new ArrayList<>(); StringBuilder field = new StringBuilder();
            boolean quoted = false; int value;
            while ((value = reader.read()) != -1) {
                char ch = (char) value;
                if (quoted) {
                    if (ch == '"') { int next = reader.read(); if (next == '"') field.append('"'); else { quoted = false; if (next != -1) reader.unread(next); } }
                    else field.append(ch);
                } else if (ch == '"' && field.isEmpty()) quoted = true;
                else if (ch == ',') { row.add(field.toString()); field.setLength(0); }
                else if (ch == '\n') { row.add(field.toString()); records.add(row); row = new ArrayList<>(); field.setLength(0); }
                else if (ch != '\r') field.append(ch);
            }
            if (quoted) throw new IllegalArgumentException("unterminated quoted field");
            if (!row.isEmpty() || !field.isEmpty()) { row.add(field.toString()); records.add(row); }
            return records;
        }
    }

    private List<NormalizedIncidentRequest> xlsx(MultipartFile file, String sourceSystem) {
        try (Workbook book = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = book.getSheetAt(0); var iterator = sheet.iterator(); if (!iterator.hasNext()) return List.of();
            Row header = iterator.next(); String[] keys = new String[header.getLastCellNum()]; for (int i = 0; i < keys.length; i++) keys[i] = cleanHeader(cell(header.getCell(i)));
            List<NormalizedIncidentRequest> result = new ArrayList<>();
            while (iterator.hasNext() && result.size() < MAX_ROWS) {
                Row row = iterator.next(); String[] values = new String[keys.length]; for (int i = 0; i < keys.length; i++) values[i] = cell(row.getCell(i));
                boolean empty = true; for (String value : values) if (!blank(value)) { empty = false; break; }
                if (!empty) result.add(fromValues(keys, values, sourceSystem));
            }
            return result;
        } catch (Exception e) { throw new IllegalArgumentException("XLSX could not be parsed: " + e.getMessage()); }
    }

    private NormalizedIncidentRequest fromValues(String[] keys, String[] values, String sourceSystem) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keys.length; i++) map.put(cleanHeader(keys[i]), i < values.length ? values[i].trim() : "");
        String source = first(map, "sourcesystem", "source system", "source", "provider");
        return new NormalizedIncidentRequest(blank(source) ? sourceSystem : source,
                first(map, "sourcereference", "source reference", "number", "incident number", "ticket id", "id", "sys_id"),
                first(map, "subject", "short description", "title", "summary"),
                first(map, "description", "issue", "details", "work notes"),
                first(map, "priority"), first(map, "category", "type"),
                first(map, "target", "configuration item", "asset", "device"), first(map, "severity", "impact"),
                // Header spellings seen across ServiceNow, Freshservice and Jira exports.
                first(map, "reporteremail", "requester email", "requester_email", "caller email",
                        "contact email", "reporter email", "email", "from"));
    }

    private String first(Map<String, String> map, String... names) { for (String name : names) { String value = map.get(cleanHeader(name)); if (!blank(value)) return value; } return ""; }
    private String cleanHeader(String value) { return value == null ? "" : value.replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT); }
    private String cell(Cell cell) { return cell == null ? "" : new DataFormatter().formatCellValue(cell); }
    private void validate(NormalizedIncidentRequest r) { if (r == null || blank(r.sourceSystem()) || blank(r.subject())) throw new IllegalArgumentException("sourceSystem and subject are required"); if (r.sourceSystem().length() > 100 || r.subject().length() > 500) throw new IllegalArgumentException("sourceSystem or subject exceeds supported length"); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String blankDefault(String value, String fallback) { return blank(value) ? fallback : value; }
    private String limit(String value, int max) { return value == null ? "" : value.substring(0, Math.min(max, value.length())); }
    private String canonicalSource(String source) { String clean = source.trim(); return clean.equalsIgnoreCase("freshservice") ? "Freshservice" : clean.equalsIgnoreCase("servicenow") || clean.equalsIgnoreCase("service now") ? "ServiceNow" : clean; }
    private String priority(String source, String priority, String severity) {
        String p = (blank(priority) ? severity : priority).trim().toUpperCase(Locale.ROOT);
        if ("1".equals(p) || "CRITICAL".equals(p) || "URGENT".equals(p) || "P1".equals(p)) return "P1";
        if ("2".equals(p) || "HIGH".equals(p) || "P2".equals(p)) return "P2";
        if ("3".equals(p) || "MEDIUM".equals(p) || "NORMAL".equals(p) || "P3".equals(p)) return "P3";
        if ("4".equals(p) || "5".equals(p) || "LOW".equals(p)) return "P3";
        return "P3";
    }
    private String fingerprint(NormalizedIncidentRequest r) { try { byte[] hash = MessageDigest.getInstance("SHA-256").digest((r.sourceSystem() + "|" + r.subject() + "|" + r.description()).getBytes(StandardCharsets.UTF_8)); StringBuilder out = new StringBuilder(); for (byte b : hash) out.append(String.format("%02x", b)); return out.toString(); } catch (Exception e) { throw new IllegalStateException(e); } }
}
