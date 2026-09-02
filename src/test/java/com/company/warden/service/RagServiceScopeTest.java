package com.company.warden.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scope list now gates two surfaces: the chat assistant and {@code
 * IncidentService.analyzeIncident}. What this protects is spend and reputation — analyze
 * runs two to three model calls per request, so anything that gets
 * past this list is answered on the operator's credit, wearing the platform's badge.
 *
 * Both directions matter. Too tight and real tickets are refused, which reads as the
 * product being broken; too loose and it is a free general-purpose assistant.
 */
class RagServiceScopeTest {

    private final RagService rag = new RagService();

    @Test
    void realTicketsAreAnalysed() {
        assertTrue(rag.isWithinSopScope("Tomcat down at store 42"));
        assertTrue(rag.isWithinSopScope("Card payments declining at the till"));
        assertTrue(rag.isWithinSopScope("Storefront returning 502 Bad Gateway from nginx"));
        assertTrue(rag.isWithinSopScope("Overnight stock sync did not run"));
        assertTrue(rag.isWithinSopScope("Receipt printer offline in lane 3"));
        // Wording an agent would actually type, with no jargon at all.
        assertTrue(rag.isWithinSopScope("the pos register keeps freezing"));
        // Named infrastructure, none of which the generic nouns catch. The first of these was
        // refused in live testing, which is how the gap was found.
        assertTrue(rag.isWithinSopScope("Kafka consumer lag climbing on the orders topic"));
        assertTrue(rag.isWithinSopScope("TLS handshake failing against the payments endpoint"));
        assertTrue(rag.isWithinSopScope("Tomcat heap keeps growing until the pod is evicted"));
    }

    @Test
    void offTopicRequestsAreRefusedBeforeAnyModelCall() {
        assertFalse(rag.isWithinSopScope("write me a poem about cats"));
        assertFalse(rag.isWithinSopScope("what is the capital of France"));
        assertFalse(rag.isWithinSopScope("summarise the plot of Hamlet"));
        assertFalse(rag.isWithinSopScope("who should I vote for"));
        assertFalse(rag.isWithinSopScope(""));
        assertFalse(rag.isWithinSopScope(null));
    }

    /**
     * One gate, three reasons, both callers. The length cap in particular used to exist only
     * on the analysis path, so the same oversized paste was refused by one endpoint and turned
     * into an unbounded prompt by the other.
     */
    @Test
    void oneGateCoversBlankOversizedAndOffTopic() {
        assertEquals(RagService.Refusal.BLANK, rag.refuse("   "));
        assertEquals(RagService.Refusal.BLANK, rag.refuse(null));
        assertEquals(RagService.Refusal.OUT_OF_SCOPE, rag.refuse("write me a poem about cats"));
        assertEquals(RagService.Refusal.TOO_LONG, rag.refuse("printer offline ".repeat(400)));
        assertNull(rag.refuse("Receipt printer offline in lane 3"));
        // Scope is checked after length, so an oversized in-scope paste is still refused.
        assertEquals(RagService.Refusal.TOO_LONG, rag.refuse("x".repeat(RagService.MAX_TEXT_CHARS + 1)));
    }

    /**
     * A count taken from a filtered subset is silently wrong, so aggregate wording must keep
     * the whole ticket window while a specific fault gets only the matching rows.
     */
    @Test
    void aggregateQuestionsKeepEveryTicketRow() {
        assertTrue(RagService.isAggregateQuestion("how many tickets are open"));
        assertTrue(RagService.isAggregateQuestion("how many incident we have"));
        assertTrue(RagService.isAggregateQuestion("how many incidents do we have"));
        assertTrue(RagService.isAggregateQuestion("incident count"));
        assertTrue(RagService.isAggregateQuestion("give me a summary of this week"));
        assertTrue(RagService.isAggregateQuestion("list all incidents by priority"));
        assertTrue(RagService.isAggregateQuestion("status summary"));
        assertFalse(RagService.isAggregateQuestion("why is the till in lane 3 down"));
        assertFalse(RagService.isAggregateQuestion("tomcat is not starting on store 42"));
    }

    /**
     * Guards the {@code unless} clause on the answer cache. A provider timeout used to be
     * cached like a real answer, so the retry a user immediately makes returned the same
     * apology from memory and kept doing so — one bad minute broke the question for good.
     * Refusals must stay cacheable: they are stable, and re-deciding them costs a model call.
     */
    @Test
    void providerFailuresAreNotCachedButRefusalsAre() {
        assertTrue(RagService.isTransientAnswer(RagService.ERROR_ANSWER));
        assertTrue(RagService.isTransientAnswer(RagService.NO_ANSWER));
        assertTrue(RagService.isTransientAnswer(RagService.SERVICE_UNAVAILABLE));
        assertFalse(RagService.isTransientAnswer("Restart the print spooler on lane 3, per SOP-POS-04."));
        assertFalse(RagService.isTransientAnswer(null));
    }

    /**
     * A keyboard mash is not an off-topic question. Answering it with the guardrail refusal
     * tells the user their question was rejected, when the truth is it never arrived — so the
     * two get different replies and the split is decided here.
     *
     * The heuristic is only consulted once scope has already failed, so an in-scope word can
     * never be called unreadable. It is still checked directly below, because a false positive
     * would relabel a genuine off-topic question as a typo and invite the user to resend it.
     */
    @Test
    void keyboardMashIsSeparatedFromOffTopic() {
        assertEquals(RagService.Refusal.UNINTELLIGIBLE, rag.refuse("hhhjdhghjs"));
        assertEquals(RagService.Refusal.UNINTELLIGIBLE, rag.refuse("zxcvbnm"));
        assertEquals(RagService.Refusal.UNINTELLIGIBLE, rag.refuse("asdfgh"));
        // A real question about the wrong subject stays a guardrail refusal.
        assertEquals(RagService.Refusal.OUT_OF_SCOPE, rag.refuse("write me a poem about cats"));
        assertEquals(RagService.Refusal.OUT_OF_SCOPE, rag.refuse("what is the capital of France"));

        assertFalse(RagService.isGibberish("printer"));
        assertFalse(RagService.isGibberish("tomcat"));
        assertFalse(RagService.isGibberish("what is the capital of France"));
        // References and acronyms: a digit or a dash means it was typed on purpose, and three
        // letters is too little to judge ("dns", "sso", "vpn").
        assertFalse(RagService.isGibberish("fs-1001"));
        assertFalse(RagService.isGibberish("INC000000001"));
        assertFalse(RagService.isGibberish("dns"));
        assertFalse(RagService.isGibberish(null));
    }
}
