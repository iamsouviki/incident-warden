package com.company.mcp.service;

import com.company.mcp.config.CurrentUser;
import com.company.mcp.dto.NormalizedIncidentRequest;
import com.company.mcp.model.Incident;
import com.company.mcp.repository.IncidentRepository;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class IncidentIntakeService {
    private static final int MAX_ROWS = 500;
    private final IncidentRepository incidents;
    private final IncidentService incidentService;
    private final CurrentUser currentUser;
    private final AuditService audit;

    public IncidentIntakeService(IncidentRepository incidents, IncidentService incidentService, CurrentUser currentUser, AuditService audit) {
        this.incidents = incidents; this.incidentService = incidentService; this.currentUser = currentUser; this.audit = audit;
    }

    public Map<String, Object> ingest(NormalizedIncidentRequest request) {
        validate(request);
        String tenant = currentUser.tenantId();
        String reference = Optional.ofNullable(request.sourceReference()).filter(v -> !v.isBlank()).orElse(fingerprint(request));
        Optional<Incident> existing = incidents.findFirstByTenantIdAndExternalSourceAndExternalId(tenant, request.sourceSystem(), reference);
        if (existing.isPresent()) return Map.of("status", "DEDUPLICATED", "incident", existing.get());
        Incident created = incidentService.createIncident(Incident.builder()
                .tenantId(tenant).subject(request.subject().trim()).description(limit(request.description(), 8_000))
                .priority(priority(request.priority(), request.severity())).category(blankDefault(request.category(), "Universal"))
                .externalSource(request.sourceSystem().trim()).externalId(reference).assignedGteam("IT Ops").assignee("Unassigned").build());
        audit.record(tenant, "INCIDENT", created.getId(), "INTAKE_ACCEPTED", currentUser.username(), Map.of("source", request.sourceSystem(), "reference", reference));
        return Map.of("status", "CREATED", "incident", created);
    }

    public Map<String, Object> importFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("A non-empty CSV or XLSX file is required");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        List<NormalizedIncidentRequest> rows = name.endsWith(".csv") ? csv(file) : name.endsWith(".xlsx") ? xlsx(file) : List.of();
        if (rows.isEmpty() && !(name.endsWith(".csv") || name.endsWith(".xlsx"))) throw new IllegalArgumentException("Only .csv and .xlsx import files are supported");
        int created = 0, deduplicated = 0, rejected = 0;
        List<String> errors = new ArrayList<>();
        for (int index = 0; index < Math.min(MAX_ROWS, rows.size()); index++) {
            try { if ("CREATED".equals(ingest(rows.get(index)).get("status"))) created++; else deduplicated++; }
            catch (Exception e) { rejected++; errors.add("row " + (index + 2) + ": " + e.getMessage()); }
        }
        return Map.of("received", Math.min(MAX_ROWS, rows.size()), "created", created, "deduplicated", deduplicated, "rejected", rejected, "errors", errors);
    }

    private List<NormalizedIncidentRequest> csv(MultipartFile file) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String header = reader.readLine(); if (header == null) return List.of(); String[] keys = header.split(",", -1);
            List<NormalizedIncidentRequest> result = new ArrayList<>(); String line;
            while ((line = reader.readLine()) != null && result.size() < MAX_ROWS) result.add(fromValues(keys, line.split(",", -1)));
            return result;
        } catch (Exception e) { throw new IllegalArgumentException("CSV could not be parsed"); }
    }

    private List<NormalizedIncidentRequest> xlsx(MultipartFile file) {
        try (Workbook book = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = book.getSheetAt(0); Iterator<Row> iterator = sheet.iterator(); if (!iterator.hasNext()) return List.of();
            Row header = iterator.next(); String[] keys = new String[header.getLastCellNum()]; for (int i = 0; i < keys.length; i++) keys[i] = cell(header.getCell(i));
            List<NormalizedIncidentRequest> result = new ArrayList<>(); while (iterator.hasNext() && result.size() < MAX_ROWS) { Row row = iterator.next(); String[] values = new String[keys.length]; for(int i=0;i<keys.length;i++) values[i]=cell(row.getCell(i)); result.add(fromValues(keys, values)); }
            return result;
        } catch (Exception e) { throw new IllegalArgumentException("XLSX could not be parsed"); }
    }

    private NormalizedIncidentRequest fromValues(String[] keys, String[] values) { Map<String,String> map = new HashMap<>(); for(int i=0;i<keys.length;i++) map.put(keys[i].trim().toLowerCase(Locale.ROOT), i < values.length ? values[i].trim() : ""); return new NormalizedIncidentRequest(map.get("sourcesystem"), map.get("sourcereference"), map.get("subject"), map.get("description"), map.get("priority"), map.get("category"), map.get("target"), map.get("severity")); }
    private String cell(Cell cell) { return cell == null ? "" : new DataFormatter().formatCellValue(cell); }
    private void validate(NormalizedIncidentRequest r) { if (r == null || blank(r.sourceSystem()) || blank(r.subject())) throw new IllegalArgumentException("sourceSystem and subject are required"); if (r.sourceSystem().length() > 100 || r.subject().length() > 500) throw new IllegalArgumentException("sourceSystem or subject exceeds supported length"); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String blankDefault(String value, String fallback) { return blank(value) ? fallback : value; }
    private String limit(String value, int max) { return value == null ? "" : value.substring(0, Math.min(max, value.length())); }
    private String priority(String priority, String severity) { String p = blank(priority) ? severity : priority; return "CRITICAL".equalsIgnoreCase(p) ? "P1" : "HIGH".equalsIgnoreCase(p) ? "P2" : "P1".equalsIgnoreCase(p) || "P2".equalsIgnoreCase(p) || "P3".equalsIgnoreCase(p) ? p.toUpperCase() : "P3"; }
    private String fingerprint(NormalizedIncidentRequest r) { try { byte[] hash = MessageDigest.getInstance("SHA-256").digest((r.sourceSystem()+"|"+r.subject()+"|"+r.description()).getBytes(StandardCharsets.UTF_8)); StringBuilder out = new StringBuilder(); for(byte b:hash) out.append(String.format("%02x",b)); return out.toString(); } catch (Exception e) { throw new IllegalStateException(e); } }
}
