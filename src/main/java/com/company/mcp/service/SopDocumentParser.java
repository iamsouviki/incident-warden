package com.company.mcp.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.*;
import java.util.regex.*;

/**
 * SopDocumentParser — extracts SOP fields from uploaded documents.
 *
 * Supports: PDF (.pdf), Word (.docx), Excel (.xlsx, .xls), plain text (.txt, .md)
 *
 * Extraction strategy:
 *   1. Extract raw text from the file
 *   2. Use regex heuristics to locate Title, Category, Description, Resolution Steps
 *   3. Return a {@link ParsedSop} DTO for user validation before saving
 */
@Slf4j
@Service
public class SopDocumentParser {

    public record ParsedSop(
            String title,
            String category,
            String description,
            String resolutionSteps,  // raw text of steps
            String rawText,
            String sourceFileName,
            List<String> warnings
    ) {}

    // ─────────────────────────────────────────────────────────────────────────

    public ParsedSop parse(MultipartFile file) throws Exception {
        String fileName = file.getOriginalFilename() != null
                ? file.getOriginalFilename().toLowerCase() : "";

        String rawText;
        try (InputStream is = file.getInputStream()) {
            if (fileName.endsWith(".pdf")) {
                rawText = extractPdf(is);
            } else if (fileName.endsWith(".docx")) {
                rawText = extractDocx(is);
            } else if (fileName.endsWith(".xlsx") || fileName.endsWith(".xls")) {
                rawText = extractExcel(is, fileName.endsWith(".xls"));
            } else {
                // TXT, MD, or unknown — treat as plain text
                rawText = new String(file.getBytes());
            }
        }

        return extractFields(rawText, file.getOriginalFilename());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Text extraction
    // ─────────────────────────────────────────────────────────────────────────

    private String extractPdf(InputStream is) throws Exception {
        try (PDDocument doc = PDDocument.load(is)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }

    private String extractDocx(InputStream is) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(is)) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                sb.append(p.getText()).append("\n");
            }
            return sb.toString();
        }
    }

    private String extractExcel(InputStream is, boolean legacy) throws Exception {
        Workbook workbook = legacy
                ? WorkbookFactory.create(is)
                : new XSSFWorkbook(is);
        StringBuilder sb = new StringBuilder();
        try (workbook) {
            for (Sheet sheet : workbook) {
                sb.append("=== Sheet: ").append(sheet.getSheetName()).append(" ===\n");
                for (Row row : sheet) {
                    for (Cell cell : row) {
                        String val = switch (cell.getCellType()) {
                            case STRING  -> cell.getStringCellValue();
                            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
                            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                            default      -> "";
                        };
                        if (!val.isBlank()) sb.append(val).append("\t");
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Field extraction heuristics
    // ─────────────────────────────────────────────────────────────────────────

    private ParsedSop extractFields(String text, String filename) {
        List<String> warnings = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");

        String title       = extractField(text, lines, "title",       warnings);
        String category    = extractField(text, lines, "category",    warnings);
        String description = extractField(text, lines, "description", warnings);
        String steps       = extractSteps(text, warnings);

        // Fallback: use first non-blank line as title
        if (title == null) {
            title = Arrays.stream(lines)
                    .map(String::trim)
                    .filter(l -> !l.isBlank() && l.length() > 3)
                    .findFirst()
                    .orElse(stripExtension(filename));
            warnings.add("Title was inferred from document first line");
        }

        // Fallback: infer category from keywords
        if (category == null) {
            category = inferCategory(text);
            warnings.add("Category was inferred from keywords: " + category);
        }

        // Fallback: use first paragraph as description
        if (description == null) {
            description = extractFirstParagraph(text);
            if (description != null) warnings.add("Description was inferred from first paragraph");
        }

        if (steps == null) warnings.add("No resolution steps section found — fill in manually");

        return new ParsedSop(title, category, description, steps, text, filename, warnings);
    }

    /** Extracts a labelled field like "Title: foo" or "## Category\nfoo". */
    private String extractField(String text, String[] lines, String fieldName, List<String> warnings) {
        // Pattern 1: "FieldName: value" (on same line)
        Pattern inlinePattern = Pattern.compile(
                "(?i)^\\s*" + fieldName + "\\s*[:\\-]\\s*(.+)$", Pattern.MULTILINE);
        Matcher m = inlinePattern.matcher(text);
        if (m.find()) return m.group(1).trim();

        // Pattern 2: markdown heading "## FieldName" followed by content
        Pattern headingPattern = Pattern.compile(
                "(?i)#+\\s*" + fieldName + "\\s*\\n+([^#\\n][^\\n]*)", Pattern.MULTILINE);
        m = headingPattern.matcher(text);
        if (m.find()) return m.group(1).trim();

        return null;
    }

    /** Extracts numbered/bulleted resolution steps. */
    private String extractSteps(String text, List<String> warnings) {
        // Look for a "Steps", "Resolution", "Procedure", "How to" section
        Pattern sectionPattern = Pattern.compile(
                "(?i)(resolution steps?|remediation steps?|procedure|how to fix|steps?|actions?)\\s*[:\\-]?\\s*\\n"
                + "([\\s\\S]*?)(?=\\n#{1,3}\\s|\\Z)",
                Pattern.MULTILINE);
        Matcher m = sectionPattern.matcher(text);
        if (m.find()) {
            String steps = m.group(2).trim();
            if (!steps.isBlank()) return steps;
        }

        // Fallback: find any numbered list
        Pattern numberedPattern = Pattern.compile(
                "(?m)^\\s*(?:\\d+[.)\\s]|[*\\-]\\s).+(?:\\n(?!\\s*\\d+[.)\\s]|\\s*[*\\-]\\s).+)*",
                Pattern.MULTILINE);
        m = numberedPattern.matcher(text);
        StringBuilder steps = new StringBuilder();
        int count = 0;
        while (m.find() && count < 10) {
            steps.append(m.group().trim()).append("\n");
            count++;
        }
        return steps.length() > 20 ? steps.toString().trim() : null;
    }

    private String inferCategory(String text) {
        String lower = text.toLowerCase();
        if (containsAny(lower, "database", "postgresql", "mysql", "query", "sql", "vacuum", "cache")) return "DATABASE";
        if (containsAny(lower, "kubernetes", "k8s", "pod", "deployment", "service restart", "replica")) return "INFRASTRUCTURE";
        if (containsAny(lower, "network", "firewall", "dns", "latency", "packet", "connectivity")) return "NETWORK";
        if (containsAny(lower, "memory", "heap", "oom", "out of memory", "jvm")) return "MEMORY";
        if (containsAny(lower, "disk", "storage", "volume", "filesystem", "inode")) return "STORAGE";
        if (containsAny(lower, "security", "cert", "tls", "ssl", "auth", "token", "permission")) return "SECURITY";
        if (containsAny(lower, "deploy", "release", "rollback", "helm", "cicd", "pipeline")) return "DEPLOYMENT";
        return "GENERAL";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) if (text.contains(kw)) return true;
        return false;
    }

    private String extractFirstParagraph(String text) {
        String[] paras = text.split("\\n{2,}");
        for (String p : paras) {
            String trimmed = p.trim();
            if (trimmed.length() > 30 && !trimmed.startsWith("#")) return trimmed;
        }
        return null;
    }

    private String stripExtension(String filename) {
        if (filename == null) return "Untitled SOP";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
