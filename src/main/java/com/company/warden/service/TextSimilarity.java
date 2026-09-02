package com.company.warden.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Term overlap between two pieces of incident/procedure text.
 *
 * Deliberately not embeddings. Two reasons: the auto-run lane below decides whether to
 * touch a production host without asking anybody, and a decision like that has to be
 * reproducible and explainable in an audit entry ("these four terms matched"), which a
 * cosine distance against a model that may be swapped in the UI is not. Second, it costs
 * no model call, so intake stays fast and works with the LLM offline.
 *
 * ponytail: token sets in Java over one incident's text. Fine per incident; the
 * candidate list it is applied to is what must stay bounded, not this.
 */
final class TextSimilarity {

    /** Words too common to carry signal when matching incident text. */
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "that", "this", "from", "have", "has", "are", "was", "were",
            "not", "but", "all", "any", "can", "will", "would", "there", "their", "been", "being",
            "when", "what", "which", "while", "into", "onto", "over", "under", "after", "before",
            "issue", "problem", "error", "please", "help", "user", "users", "ticket", "incident");

    private TextSimilarity() {}

    static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (raw.length() >= 3 && !STOP_WORDS.contains(raw)) tokens.add(raw);
        }
        return tokens;
    }

    /**
     * Coverage of {@code query}'s signal terms by {@code candidate}, in [0,1].
     *
     * Coverage rather than Jaccard: a past incident with a long resolution note is not a
     * worse match for a one-line ticket than a terse one, and Jaccard would punish it for
     * its extra terms. The question being asked is "is everything this new ticket says
     * already accounted for by that old one", and coverage is that question.
     */
    static double coverage(Set<String> query, Set<String> candidate) {
        if (query.isEmpty() || candidate.isEmpty()) return 0.0;
        return (double) matched(query, candidate).size() / query.size();
    }

    /**
     * Which of {@code query}'s terms appear in {@code candidate}.
     *
     * The terms themselves, not a count: an unattended action has to be able to say
     * "these four words matched" in its audit entry, and a score on its own explains
     * nothing to whoever reads that entry six months later.
     */
    static List<String> matched(Set<String> query, Set<String> candidate) {
        List<String> hits = new ArrayList<>();
        for (String term : query) if (candidate.contains(term)) hits.add(term);
        return hits;
    }
}
