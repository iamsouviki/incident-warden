package com.company.warden.service;

import com.company.warden.model.Incident;
import com.company.warden.repository.IncidentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The third retriever: relationships, alongside vector similarity and full-text search.
 *
 * <p>Vector and lexical retrieval both answer "what text looks like this question". Neither can
 * answer "what else is on that host", "what fixed this last time" or "which procedure grounded
 * it", because those facts are edges rather than words. This walks {@code incident.graph_edges}
 * (see {@code db/changelog/versions/1.2/incident_graph.sql}) and hands the assistant a bounded,
 * quotable list of them.
 *
 * <p>Four properties the graph lane has to hold, because it feeds an LLM prompt:
 *
 * <ul>
 *   <li><b>Bounded</b> — at most {@value #MAX_SEEDS} seed tickets, two hops, {@value #MAX_LINES}
 *       rendered relationships. No traversal is unrolled without a ceiling.
 *   <li><b>Deterministic</b> — expansion starts only from ticket references the user typed, and
 *       the output is sorted. The same question produces the same context, which is what makes a
 *       retrieval-quality test possible at all.
 *   <li><b>Explainable</b> — every line names both endpoints and the relationship, and the
 *       footer names the seeds and depth. Provenance travels with the text into the stored
 *       assistant message, so an operator reading the answer later can see what it was built on.
 *   <li><b>Resistant to unrelated expansion</b> — hub nodes are dropped. {@code CATEGORY:Universal}
 *       or a shared jump host touches most of the estate; following it does not relate two
 *       incidents, it just pulls the whole board into the prompt. See {@link #HUB_DEGREE}.
 * </ul>
 *
 * <p>No ticket reference in the question means no graph expansion. That is the deliberate
 * choice, not a gap: guessing a seed from loose wording is exactly how the graph would start
 * contributing noise, and the vector and lexical lanes already cover "questions that sound like
 * this document".
 */
@Service
public class GraphRetrievalService {
    private static final Logger log = LoggerFactory.getLogger(GraphRetrievalService.class);

    /** Ticket shapes this platform issues or imports: INC0000001234, FS-1001, SNOW-42, JIRA-7. */
    private static final Pattern TICKET = Pattern.compile("\\b([A-Z][A-Z0-9]{1,9}-?\\d{2,12})\\b");

    private static final int MAX_SEEDS = 2;
    private static final int DEPTH = 2;
    private static final int MAX_LINES = 40;

    /**
     * A node joined to more than this many others in one neighbourhood is a hub, not a
     * relationship, and is dropped along with every edge through it.
     *
     * <p>ponytail: degree measured inside the returned neighbourhood, not globally. It costs no
     * extra query and catches the cases that matter — a category every ticket carries, a store
     * with fifty open tickets. A global degree table would be more precise and would need
     * maintaining; revisit only if a real deployment shows hubs slipping through.
     */
    private static final int HUB_DEGREE = 8;

    private final IncidentRepository incidents;
    private final IncidentGraphService graph;

    public GraphRetrievalService(IncidentRepository incidents, IncidentGraphService graph) {
        this.incidents = incidents;
        this.graph = graph;
    }

    /**
     * Relationships around the tickets named in {@code question}, as prompt-ready text.
     *
     * @return "" when the question names no ticket, the ticket is unknown, or it has no mapped
     *         relationships. Empty is the common case and must stay cheap.
     */
    public String forQuestion(String question) {
        List<Incident> seeds = seeds(question);
        if (seeds.isEmpty()) return "";

        Set<String> lines = new LinkedHashSet<>();
        List<String> provenance = new ArrayList<>();
        for (Incident seed : seeds) {
            Map<String, Object> hood = graph.neighbourhood(seed.getId(), DEPTH);
            List<String> rendered = render(hood, "INCIDENT:" + seed.getId());
            if (rendered.isEmpty()) continue;
            provenance.add(reference(seed));
            lines.addAll(rendered);
        }
        if (lines.isEmpty()) return "";

        List<String> sorted = new ArrayList<>(lines);
        sorted.sort(null);   // deterministic prompt for a deterministic question
        boolean truncated = sorted.size() > MAX_LINES;
        if (truncated) sorted = sorted.subList(0, MAX_LINES);

        String footer = "(Source: incident knowledge graph, " + DEPTH + " hops from "
                + String.join(", ", provenance) + (truncated ? ", truncated to " + MAX_LINES + " relationships" : "") + ")";
        log.info("[RAG-GRAPH] {} relationships from {}", sorted.size(), provenance);
        return String.join("\n", sorted) + "\n" + footer;
    }

    /** Tickets the user actually named, resolved against the incident table. */
    private List<Incident> seeds(String question) {
        if (question == null || question.isBlank()) return List.of();
        Matcher matcher = TICKET.matcher(question.toUpperCase(java.util.Locale.ROOT));
        List<Incident> found = new ArrayList<>();
        Set<String> tried = new LinkedHashSet<>();
        while (matcher.find() && found.size() < MAX_SEEDS) {
            String reference = matcher.group(1);
            if (!tried.add(reference)) continue;
            try {
                incidents.findByExternalId(reference).ifPresent(found::add);
            } catch (Exception e) {
                // A retrieval lane must never take the answer down with it.
                log.warn("[RAG-GRAPH] Seed lookup failed for {}: {}", reference, e.getMessage());
            }
        }
        return found;
    }

    /**
     * One line per relationship, hubs removed.
     *
     * <p>Labels come from incident subjects, host names and SOP titles — all operator- or
     * vendor-supplied text that ends up inside an LLM prompt. Newlines are stripped so a crafted
     * subject cannot open a fake section in the prompt; the caller's delimiters do the rest.
     */
    private List<String> render(Map<String, Object> hood, String seedKey) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) hood.getOrDefault("edges", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) hood.getOrDefault("nodes", List.of());
        if (edges.isEmpty()) return List.of();

        Map<String, Integer> degree = new HashMap<>();
        for (Map<String, Object> edge : edges) {
            degree.merge(String.valueOf(edge.get("source")), 1, Integer::sum);
            degree.merge(String.valueOf(edge.get("target")), 1, Integer::sum);
        }
        Map<String, String> labels = new HashMap<>();
        for (Map<String, Object> node : nodes) {
            labels.put(String.valueOf(node.get("key")),
                    String.valueOf(node.get("type")) + " " + safe(String.valueOf(node.get("label"))));
        }

        List<String> lines = new ArrayList<>();
        for (Map<String, Object> edge : edges) {
            String source = String.valueOf(edge.get("source"));
            String target = String.valueOf(edge.get("target"));
            if (isHub(degree, source, seedKey) || isHub(degree, target, seedKey)) continue;
            lines.add("- " + labels.getOrDefault(source, source) + " —" + edge.get("edge") + "→ "
                    + labels.getOrDefault(target, target));
        }
        return lines;
    }

    /**
     * The ticket the user named is never a hub.
     *
     * <p>The defect this closes: at two hops the seed is usually the highest-degree node in its
     * own neighbourhood — host, store, category, procedure, plan, plus every precedent. Measuring
     * it like any other node crossed the threshold and dropped every edge touching it, which is
     * every relationship that was asked for. Hub suppression is there to stop a shared node
     * pulling in the estate; the seed cannot be that node.
     */
    private static boolean isHub(Map<String, Integer> degree, String key, String seedKey) {
        return !key.equals(seedKey) && degree.getOrDefault(key, 0) > HUB_DEGREE;
    }

    private static String reference(Incident incident) {
        return incident.getExternalId() == null || incident.getExternalId().isBlank()
                ? String.valueOf(incident.getId()) : incident.getExternalId();
    }

    /** Graph labels are untrusted text on their way into a prompt. */
    private static String safe(String label) {
        String flat = label.replaceAll("[\\r\\n]+", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "…";
    }
}
